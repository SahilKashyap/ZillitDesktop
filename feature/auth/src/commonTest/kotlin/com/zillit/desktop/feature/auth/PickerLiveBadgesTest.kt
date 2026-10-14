package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.feature.auth.domain.AuthSession
import com.zillit.desktop.feature.auth.domain.CodeLookup
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.DeviceStatus
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.ui.AuthEvent
import com.zillit.desktop.feature.auth.ui.PickerBadges
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The listing is a live screen.
 *
 * Reported 2026-10-14: sitting on the project list, a production that had
 * just been messaged went on showing the number it had when the list
 * loaded. Everything behind the cards was working — the socket fills the
 * ledger while the listing is up — and nothing told the cards to repaint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PickerLiveBadgesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val projects = listOf(
        Project("p1", "One", "P1", null, null),
        Project("p2", "Two", "P2", null, null),
    )

    private class LinkedDeviceStub : AuthRepository {
        override val session = flowOf<AuthSession?>(null)
        override suspend fun restoreDevice() = ZillitResult.Success(DeviceIdentity("device-1", "", false))
        override suspend fun requestOtp(email: String, language: String) = ZillitResult.Success(Unit)
        override suspend fun verifyOtp(email: String, otp: String) = ZillitResult.Success("C")
        override suspend fun registerDevice(email: String, confirmCode: String) =
            ZillitResult.Success(DeviceIdentity("d", "e", false))
        override suspend fun requestRecovery(email: String) = ZillitResult.Success(Unit)
        override suspend fun recoverWithCode(code: String) = ZillitResult.Success(DeviceIdentity("d", "e", false))
        override suspend fun rememberDevice(identity: DeviceIdentity) = ZillitResult.Success(kotlin.Unit)
        override suspend fun confirmDevice() = DeviceStatus.Valid
        override suspend fun signOut() = ZillitResult.Success(Unit)
    }

    private class ProjectsStub(private val answer: () -> ZillitResult<List<Project>>) : ProjectRepository {
        private val one = Project("p1", "One", "P1", null, null)
        override suspend fun listProjects() = answer()
        override suspend fun findByCode(code: String) = ZillitResult.Success(CodeLookup.NeedsDetails(one))
        override suspend fun create(
            draft: NewProductionDraft,
            selectedType: ProductionType?,
            confirmCode: String,
        ) = ZillitResult.Success(one)
        override suspend fun setFavourite(projectId: String, favourite: Boolean) = ZillitResult.Success(kotlin.Unit)
        override suspend fun leaveProject() = ZillitResult.Success(kotlin.Unit)
        override suspend fun requestJoin(projectId: String, draft: JoinDraft) = ZillitResult.Success(JoinStatus.Pending)
        override suspend fun departments(projectId: String) = ZillitResult.Success(emptyList<Department>())
        override suspend fun joinStatus(projectId: String) = ZillitResult.Success(JoinStatus.Approved)
        override suspend fun listUnits(projectId: String) =
            ZillitResult.Success(emptyList<com.zillit.desktop.feature.auth.domain.Unit>())
        override suspend fun selectProject(project: Project, unit: com.zillit.desktop.feature.auth.domain.Unit?) =
            ZillitResult.Success(kotlin.Unit)
    }

    @Test
    fun `a badge arriving while the listing is up repaints the card`() = runTest(dispatcher) {
        val ledger = MutableSharedFlow<Unit>()
        var counts = mapOf("p1" to 1)
        var seeds = 0
        val viewModel = AuthViewModel(
            LinkedDeviceStub(),
            ProjectsStub { ZillitResult.Success(projects) },
            qrLoginRepository = null,
            projectUnread = PickerBadges(
                refresh = { seeds++; counts },
                now = { counts },
                changes = ledger,
            ),
        )
        advanceUntilIdle()
        assertEquals(AuthStep.ProjectSelection, viewModel.currentState.step)
        assertEquals(mapOf("p1" to 1), viewModel.currentState.projectUnread)
        assertEquals(1, seeds, "the list load seeds once")

        // A message lands for the other production.
        counts = mapOf("p1" to 1, "p2" to 3)
        ledger.emit(Unit)
        advanceUntilIdle()

        assertEquals(mapOf("p1" to 1, "p2" to 3), viewModel.currentState.projectUnread)
        assertEquals(1, seeds, "the live read never seeds — a seed signals the ledger and would answer itself")
    }

    @Test
    fun `the ledger is not re-read behind an open production`() = runTest(dispatcher) {
        val ledger = MutableSharedFlow<Unit>()
        var reads = 0
        val viewModel = AuthViewModel(
            LinkedDeviceStub(),
            ProjectsStub { ZillitResult.Success(projects) },
            qrLoginRepository = null,
            projectUnread = PickerBadges(
                refresh = { mapOf("p1" to 1) },
                now = { reads++; mapOf("p1" to 9) },
                changes = ledger,
            ),
        )
        advanceUntilIdle()
        viewModel.onEvent(AuthEvent.SelectProject(projects.first()))
        advanceUntilIdle()

        ledger.emit(Unit)
        advanceUntilIdle()

        assertEquals(0, reads, "the shell draws its own counts; this map is for the listing")
    }
}
