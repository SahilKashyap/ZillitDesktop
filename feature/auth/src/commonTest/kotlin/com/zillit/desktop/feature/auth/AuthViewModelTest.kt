package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.CodeLookup
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.feature.auth.domain.AuthSession
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.DeviceStatus
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.Unit as DomainUnit
import com.zillit.desktop.feature.auth.ui.AuthEvent
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `submitting an email moves to the code step`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(AuthEvent.EmailChanged("crew@production.com"))
        viewModel.onEvent(AuthEvent.SubmitEmail)
        advance()

        val step = viewModel.currentState.step
        assertIs<AuthStep.Otp>(step)
        assertEquals("crew@production.com", step.email)
        assertFalse(viewModel.currentState.isBusy)
    }

    @Test
    fun `an invalid email surfaces the message and stays put`() = runTest(dispatcher) {
        val auth = FakeAuthRepository(
            requestOtpResult = ZillitResult.Failure(ZillitError.Validation("Enter a valid email address.")),
        )
        val viewModel = viewModel(auth)

        // QR is the landing step now; the email path is reached by backing out
        // of it.
        viewModel.onEvent(AuthEvent.Back)
        viewModel.onEvent(AuthEvent.EmailChanged("nope"))
        viewModel.onEvent(AuthEvent.SubmitEmail)
        advance()

        assertEquals(AuthStep.Email, viewModel.currentState.step)
        assertEquals("Enter a valid email address.", viewModel.currentState.error)
    }

    @Test
    fun `the error shows the user message, never the technical detail`() = runTest(dispatcher) {
        // `technical` carries exception names and server internals and belongs
        // only in logs (plan §8.4).
        val auth = FakeAuthRepository(
            requestOtpResult = ZillitResult.Failure(
                ZillitError.Http(status = 500, serverMessage = null, technical = "NullPointerException at Foo.kt:42"),
            ),
        )
        val viewModel = viewModel(auth)

        viewModel.onEvent(AuthEvent.EmailChanged("crew@production.com"))
        viewModel.onEvent(AuthEvent.SubmitEmail)
        advance()

        val error = viewModel.currentState.error.orEmpty()
        assertFalse(error.contains("NullPointerException"), "technical detail leaked to the UI: $error")
        assertTrue(error.isNotBlank())
    }

    @Test
    fun `the code field accepts only digits`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(AuthEvent.OtpChanged("12a3-b4"))

        assertEquals("1234", viewModel.currentState.otp)
    }

    @Test
    fun `verifying registers the device and loads projects in one action`() = runTest(dispatcher) {
        // The confirm code from `verify` is single-use and short-lived, so an
        // intermediate "now press register" step would let it expire.
        val auth = FakeAuthRepository()
        val projects = FakeProjectRepository(listOf(project("p1"), project("p2")))
        val viewModel = viewModel(auth, projects)

        signInTo(viewModel, "crew@production.com", "123456")

        assertEquals(1, auth.verifyCount)
        assertEquals(1, auth.registerCount)
        assertEquals(AuthStep.ProjectSelection, viewModel.currentState.step)
        assertEquals(2, viewModel.currentState.projects.size)
    }

    @Test
    fun `a single production is selected automatically`() = runTest(dispatcher) {
        // Making someone choose from a list of one is pure friction.
        val projects = FakeProjectRepository(listOf(project("only")))
        val viewModel = viewModel(projects = projects)

        signInTo(viewModel, "crew@production.com", "123456")

        assertEquals(AuthStep.Complete, viewModel.currentState.step)
        assertEquals("only", projects.selected?.id)
    }

    @Test
    fun `several productions require a choice`() = runTest(dispatcher) {
        val projects = FakeProjectRepository(listOf(project("a"), project("b")))
        val viewModel = viewModel(projects = projects)

        signInTo(viewModel, "crew@production.com", "123456")

        assertEquals(AuthStep.ProjectSelection, viewModel.currentState.step)
        assertNull(projects.selected)

        viewModel.onEvent(AuthEvent.SelectProject(project("b")))
        advance()

        assertEquals("b", projects.selected?.id)
        assertEquals(AuthStep.Complete, viewModel.currentState.step)
    }

    @Test
    fun `the project filter matches name and code`() = runTest(dispatcher) {
        val projects = FakeProjectRepository(
            listOf(
                project("a", name = "Night Shoot", code = "NS-1"),
                project("b", name = "Desert Feature", code = "DF-9"),
            ),
        )
        val viewModel = viewModel(projects = projects)
        signInTo(viewModel, "crew@production.com", "123456")

        viewModel.onEvent(AuthEvent.ProjectFilterChanged("night"))
        assertEquals(listOf("a"), viewModel.currentState.visibleProjects.map { it.id })

        viewModel.onEvent(AuthEvent.ProjectFilterChanged("DF-9"))
        assertEquals(listOf("b"), viewModel.currentState.visibleProjects.map { it.id })
    }

    @Test
    fun `back from the code step returns to email and clears the code`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(AuthEvent.EmailChanged("crew@production.com"))
        viewModel.onEvent(AuthEvent.SubmitEmail)
        advance()
        viewModel.onEvent(AuthEvent.OtpChanged("1234"))

        viewModel.onEvent(AuthEvent.Back)

        assertEquals(AuthStep.Email, viewModel.currentState.step)
        assertEquals("", viewModel.currentState.otp)
    }

    @Test
    fun `recovery signs the device in`() = runTest(dispatcher) {
        val projects = FakeProjectRepository(listOf(project("only")))
        val viewModel = viewModel(projects = projects)

        viewModel.onEvent(AuthEvent.StartRecovery)
        assertEquals(AuthStep.Recovery, viewModel.currentState.step)

        viewModel.onEvent(AuthEvent.RecoveryCodeChanged("RECOVER-1"))
        viewModel.onEvent(AuthEvent.SubmitRecovery)
        advance()

        assertEquals(AuthStep.Complete, viewModel.currentState.step)
    }

    @Test
    fun `submit is blocked while a request is in flight`() = runTest(dispatcher) {
        // Otherwise an impatient double-click sends two codes and invalidates
        // the first.
        val viewModel = viewModel()
        viewModel.onEvent(AuthEvent.EmailChanged("crew@production.com"))

        assertTrue(viewModel.currentState.canSubmitEmail)
        viewModel.onEvent(AuthEvent.SubmitEmail)

        assertFalse(viewModel.currentState.canSubmitEmail, "submit must be disabled while busy")
    }

    @Test
    fun `a short code cannot be submitted`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(AuthEvent.OtpChanged("12"))

        assertFalse(viewModel.currentState.canSubmitOtp)

        viewModel.onEvent(AuthEvent.OtpChanged("123456"))
        assertTrue(viewModel.currentState.canSubmitOtp)
    }

    // -- helpers ----------------------------------------------------------

    private fun TestScope.advance() = testScheduler.advanceUntilIdle()

    private fun TestScope.signInTo(
        viewModel: AuthViewModel,
        email: String,
        otp: String,
    ) {
        viewModel.onEvent(AuthEvent.EmailChanged(email))
        viewModel.onEvent(AuthEvent.SubmitEmail)
        advance()
        viewModel.onEvent(AuthEvent.OtpChanged(otp))
        viewModel.onEvent(AuthEvent.SubmitOtp)
        advance()
    }

    private fun viewModel(
        auth: AuthRepository = FakeAuthRepository(),
        projects: ProjectRepository = FakeProjectRepository(listOf(project("a"), project("b"))),
    ) = AuthViewModel(auth, projects)

    private fun project(id: String, name: String = "Project $id", code: String = "CODE-$id") =
        Project(id = id, name = name, code = code, type = "Feature", region = "UK")
}

private class FakeAuthRepository(
    private val requestOtpResult: ZillitResult<Unit> = ZillitResult.Success(Unit),
    private val verifyResult: ZillitResult<String> = ZillitResult.Success("CONFIRM-1"),
) : AuthRepository {

    var verifyCount = 0
        private set
    var registerCount = 0
        private set

    private val identity = DeviceIdentity(deviceId = "device-1", email = "crew@production.com", isPrimary = true)

    override val session: Flow<AuthSession?> = flowOf(null)

    override suspend fun requestOtp(email: String, language: String) = requestOtpResult

    override suspend fun verifyOtp(email: String, otp: String): ZillitResult<String> {
        verifyCount++
        return verifyResult
    }

    override suspend fun registerDevice(email: String, confirmCode: String): ZillitResult<DeviceIdentity> {
        registerCount++
        return ZillitResult.Success(identity)
    }

    override suspend fun requestRecovery(email: String) = ZillitResult.Success(Unit)

    override suspend fun recoverWithCode(code: String) = ZillitResult.Success(identity)

    var storedDevice: DeviceIdentity? = null
    override suspend fun restoreDevice() = ZillitResult.Success(storedDevice)

    override suspend fun rememberDevice(identity: DeviceIdentity) = ZillitResult.Success(kotlin.Unit)

    override suspend fun confirmDevice() = DeviceStatus.Valid

    override suspend fun signOut() = ZillitResult.Success(Unit)
}

private class FakeProjectRepository(private val projects: List<Project>) : ProjectRepository {

    var selected: Project? = null
        private set

    override suspend fun create(
        draft: com.zillit.desktop.feature.auth.domain.NewProductionDraft,
        selectedType: com.zillit.desktop.feature.auth.domain.ProductionType?,
        confirmCode: String,
    ) = ZillitResult.Success(
        Project(id = "new", name = draft.productionName, code = "NEW-1", type = null, region = null),
    )

    override suspend fun leaveProject() = ZillitResult.Success(kotlin.Unit)

    override suspend fun setFavourite(projectId: String, favourite: Boolean) =
        ZillitResult.Success(kotlin.Unit)

    override suspend fun listProjects() = ZillitResult.Success(projects)

    override suspend fun findByCode(code: String) =
        projects.firstOrNull { it.code == code }
            ?.let { ZillitResult.Success(CodeLookup.NeedsDetails(it)) }
            ?: ZillitResult.Failure(ZillitError.Validation("not found"))

    override suspend fun requestJoin(projectId: String, draft: JoinDraft) = ZillitResult.Success(JoinStatus.Pending)


    override suspend fun departments(projectId: String) =
        ZillitResult.Success(emptyList<Department>())
    override suspend fun joinStatus(projectId: String) = ZillitResult.Success(JoinStatus.Approved)

    override suspend fun listUnits(projectId: String) = ZillitResult.Success(emptyList<DomainUnit>())

    override suspend fun selectProject(project: Project, unit: DomainUnit?): ZillitResult<Unit> {
        selected = project
        return ZillitResult.Success(Unit)
    }
}
