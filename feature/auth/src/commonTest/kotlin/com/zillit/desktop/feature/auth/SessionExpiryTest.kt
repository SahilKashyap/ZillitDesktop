package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.feature.auth.domain.AuthSession
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.DeviceStatus
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.QrLoginRepository
import com.zillit.desktop.feature.auth.domain.QrLoginSession
import com.zillit.desktop.feature.auth.ui.AuthEvent
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import com.zillit.desktop.feature.auth.ui.isEstablished
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.zillit.desktop.feature.auth.domain.Unit as DomainUnit

/**
 * What happens when the server stops accepting this device.
 *
 * The trap here is that signing in produces 401s of its own — a wrong code, an
 * address the server has never seen. Treating those as an expired session would
 * throw the user back to the QR screen mid-sign-in and discard what they had
 * typed, which is a worse bug than the one being fixed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionExpiryTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        auth: FakeAuth = FakeAuth(),
        projects: FakeProjects = FakeProjects(),
        qr: FakeQr = FakeQr(),
    ) = AuthViewModel(
        authRepository = auth,
        projectRepository = projects,
        qrLoginRepository = qr,
        nowMillis = { dispatcher.scheduler.currentTime },
    )

    /** Drives the ViewModel to a signed-in state, as a stored device would. */
    private fun signedIn(
        projects: FakeProjects,
        qr: FakeQr = FakeQr(),
        auth: FakeAuth = FakeAuth(stored = DEVICE),
    ): AuthViewModel {
        val model = viewModel(auth, projects, qr)
        dispatcher.scheduler.advanceUntilIdle()
        check(model.state.value.step == AuthStep.Complete) {
            "expected a signed-in fixture, got ${model.state.value.step}"
        }
        return model
    }

    // -- the redirect ------------------------------------------------------

    @Test
    fun `a confirmed revocation goes back to the QR screen`() = runTest(dispatcher) {
        val model = signedIn(FakeProjects())

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        assertIs<AuthStep.QrLogin>(model.state.value.step)
    }

    @Test
    fun `it says what happened`() = runTest(dispatcher) {
        // Otherwise the user is dumped at a QR screen with no explanation,
        // which reads as the app having lost their place.
        val model = signedIn(FakeProjects())

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        val message = model.state.value.error
        assertNotNull(message)
        assertTrue("session" in message.lowercase(), "unhelpful message: $message")
    }

    @Test
    fun `the open production is left behind`() = runTest(dispatcher) {
        // Badges, cached rights, third-party credentials and the socket all
        // belong to a production. Left behind, the next sign-in would read the
        // previous one's answers.
        val projects = FakeProjects()
        val model = signedIn(projects)

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        assertEquals(1, projects.leaveCount)
        assertEquals(null, model.state.value.activeProject)
    }

    @Test
    fun `a fresh code is offered to sign back in with`() = runTest(dispatcher) {
        val qr = FakeQr()
        val model = signedIn(FakeProjects(), qr)
        val before = qr.startCount

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        assertEquals(before + 1, qr.startCount)
        assertNotNull((model.state.value.step as AuthStep.QrLogin).session)
    }

    // -- the check that stops spurious sign-outs ---------------------------

    @Test
    fun `a rejection the server does not confirm keeps the session`() = runTest(dispatcher) {
        // The reason this check exists. One endpoint having a bad minute used
        // to cost the user a QR scan and whatever they were in the middle of.
        val auth = FakeAuth(stored = DEVICE, status = DeviceStatus.Valid)
        val projects = FakeProjects()
        val model = signedIn(projects, auth = auth)

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        assertEquals(AuthStep.Complete, model.state.value.step)
        assertEquals(0, projects.leaveCount, "the production must not be torn down")
    }

    @Test
    fun `an unreachable server keeps the session`() = runTest(dispatcher) {
        // Being unable to ask is not an answer. A unit on location whose signal
        // drops must not come back to a sign-in screen.
        val auth = FakeAuth(stored = DEVICE, status = DeviceStatus.Unknown)
        val model = signedIn(FakeProjects(), auth = auth)

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        assertEquals(AuthStep.Complete, model.state.value.step)
    }

    @Test
    fun `the check is what decides, not the failed call`() = runTest(dispatcher) {
        val auth = FakeAuth(stored = DEVICE, status = DeviceStatus.Revoked)
        val model = signedIn(FakeProjects(), auth = auth)

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        assertEquals(1, auth.confirmCount)
        assertIs<AuthStep.QrLogin>(model.state.value.step)
    }

    // -- what must NOT trigger it ------------------------------------------

    @Test
    fun `a 401 while signing in is ignored`() = runTest(dispatcher) {
        // A wrong code 401s. Bouncing to the QR screen would discard the email
        // the user is halfway through the flow with.
        val auth = FakeAuth()
        val model = viewModel(auth)
        advanceUntilIdle()
        model.onEvent(AuthEvent.EmailChanged("crew@production.com"))

        model.onEvent(AuthEvent.SessionExpired)
        advanceUntilIdle()

        assertEquals("crew@production.com", model.state.value.email)
        assertEquals(0, auth.confirmCount, "nothing to confirm before a device is linked")
    }

    @Test
    fun `only an established session can expire`() {
        assertTrue(AuthStep.Complete.isEstablished)
        assertTrue(AuthStep.ProjectSelection.isEstablished)

        // Everything before the device has proved itself.
        listOf(
            AuthStep.Email,
            AuthStep.Otp("crew@production.com"),
            AuthStep.Recovery,
            AuthStep.QrLogin(),
        ).forEach { step ->
            assertTrue(!step.isEstablished, "$step should not count as established")
        }
    }

    // -- the stampede ------------------------------------------------------

    @Test
    fun `many failures at once produce one sign-out`() = runTest(dispatcher) {
        // When a session dies every in-flight call fails together — a badge
        // count, a mail sync, a reminder refresh. Without a guard the QR code
        // would be torn down and refetched once per failed request, and the
        // user would watch it flicker.
        val projects = FakeProjects()
        val qr = FakeQr()
        val auth = FakeAuth(stored = DEVICE, status = DeviceStatus.Revoked)
        val model = signedIn(projects, qr, auth)
        val startsBefore = qr.startCount

        repeat(5) { model.onEvent(AuthEvent.SessionExpired) }
        advanceUntilIdle()

        assertEquals(1, auth.confirmCount, "five failures must not mean five checks")
        assertEquals(1, projects.leaveCount)
        assertEquals(startsBefore + 1, qr.startCount)
    }

    // -- fakes -------------------------------------------------------------

    // -- signing out on purpose ------------------------------------------

    /**
     * Sign-out from anywhere (Settings, the rail) clears the repository's
     * session; the screen must follow — back to the QR, the production left,
     * nothing of the last person on the state. Found in QA: it used to stay
     * on the project list and say "token invalid".
     */
    @Test
    fun `a cleared session takes an established sign-in back to the QR screen`() = runTest(dispatcher) {
        val auth = FakeAuth(stored = DEVICE)
        val projects = FakeProjects()
        val qr = FakeQr()
        val model = signedIn(projects, qr, auth)
        val codesBefore = qr.startCount

        auth.signOut()
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<AuthStep.QrLogin>(model.state.value.step)
        assertEquals(1, projects.leaveCount, "the production is left")
        assertEquals(codesBefore + 1, qr.startCount, "a fresh code to sign back in with")
        assertEquals(null, model.state.value.activeProject)
    }

    @Test
    fun `a session that was never established does not react to being null`() = runTest(dispatcher) {
        val qr = FakeQr()
        val model = viewModel(FakeAuth(stored = null), FakeProjects(), qr)
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<AuthStep.QrLogin>(model.state.value.step)
        assertEquals(1, qr.startCount, "one code for the sign-in screen, not a second from a phantom sign-out")
    }

    private companion object {
        val DEVICE = DeviceIdentity("device-1", "crew@production.com", isPrimary = false)
        val ONLY = Project(id = "p1", name = "Only Production", code = "P1", type = null, region = null)
    }

    private class FakeAuth(
        private val stored: DeviceIdentity? = null,
        private val status: DeviceStatus = DeviceStatus.Revoked,
    ) : AuthRepository {
        var confirmCount = 0
            private set

        override suspend fun confirmDevice(): DeviceStatus {
            confirmCount++
            return status
        }

        /** Live, so a test can sign the person out the way `signOut()` does: by clearing it. */
        override val session = MutableStateFlow<AuthSession?>(
            stored?.let { AuthSession(it, activeProject = null, activeUnit = null) },
        )
        override suspend fun requestOtp(email: String, language: String) = ZillitResult.Success(Unit)
        override suspend fun verifyOtp(email: String, otp: String) = ZillitResult.Success("CONFIRM")
        override suspend fun registerDevice(email: String, confirmCode: String) =
            ZillitResult.Success(DEVICE)
        override suspend fun requestRecovery(email: String) = ZillitResult.Success(Unit)
        override suspend fun recoverWithCode(code: String) = ZillitResult.Success(DEVICE)
        override suspend fun restoreDevice() = ZillitResult.Success(stored)
        override suspend fun rememberDevice(identity: DeviceIdentity) = ZillitResult.Success(Unit)
        override suspend fun signOut(): ZillitResult<Unit> {
            session.value = null
            return ZillitResult.Success(Unit)
        }
    }

    private class FakeProjects : ProjectRepository {
        var leaveCount = 0
            private set

        override suspend fun leaveProject(): ZillitResult<Unit> {
            leaveCount++
            return ZillitResult.Success(Unit)
        }

        override suspend fun create(
            draft: NewProductionDraft,
            selectedType: ProductionType?,
            confirmCode: String,
        ) = ZillitResult.Success(ONLY)

        override suspend fun setFavourite(projectId: String, favourite: Boolean) =
            ZillitResult.Success(Unit)

        override suspend fun listProjects() = ZillitResult.Success(listOf(ONLY))
        override suspend fun findByCode(code: String) = ZillitResult.Success(ONLY)
        override suspend fun requestJoin(projectId: String, draft: JoinDraft) =
            ZillitResult.Success(JoinStatus.Pending)

        override suspend fun departments(projectId: String) =
            ZillitResult.Success(emptyList<Department>())
        override suspend fun joinStatus(projectId: String) = ZillitResult.Success(JoinStatus.Approved)
        override suspend fun listUnits(projectId: String) = ZillitResult.Success(emptyList<DomainUnit>())
        override suspend fun selectProject(project: Project, unit: DomainUnit?) =
            ZillitResult.Success(Unit)
    }

    private class FakeQr : QrLoginRepository {
        var startCount = 0
            private set

        override suspend fun startSession(): ZillitResult<QrLoginSession> {
            startCount++
            return ZillitResult.Success(
                QrLoginSession(
                    id = "session-$startCount",
                    code = "code-$startCount",
                    expiresAtMillis = QrLoginSession.LIFETIME_MILLIS,
                ),
            )
        }

        // Never scanned: these tests are about getting *to* the code, not past it.
        override suspend fun pollScanned(session: QrLoginSession) = ZillitResult.Success(null)

        override suspend fun completeLink(session: QrLoginSession, scannerDeviceId: String) =
            ZillitResult.Success(DEVICE)
    }
}
