package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import com.zillit.desktop.feature.auth.domain.DeviceStatus
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.QrLoginRepository
import com.zillit.desktop.feature.auth.domain.QrLoginSession
import com.zillit.desktop.feature.auth.ui.AuthEvent
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

/**
 * The QR sign-in loop: display, poll, go stale.
 *
 * Time is injected, so the poll window is tested in milliseconds of wall clock
 * rather than by waiting 30 real seconds.
 *
 * The behaviour pinned here is the **web** client's (`Login.jsx`), which this
 * screen reproduces: poll every 3s, stop after 30s, and leave the code on
 * screen behind a reload control rather than discarding it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QrLoginTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * The app clock IS the scheduler's virtual clock.
     *
     * Keeping them separate deadlocks the tests: the poll loop always has a
     * pending `delay`, so `advanceUntilIdle()` keeps running it, and an
     * independent clock never reaches expiry — the loop spins forever in virtual
     * time. Tying them means advancing time also ages the session.
     */
    private val clock: Long get() = dispatcher.scheduler.currentTime

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the code is fetched on construction, without a user action`() = runTest(dispatcher) {
        // QR is the landing screen: waiting for a click would show an empty
        // panel to a user who has nothing to click.
        val viewModel = viewModel()

        advance()

        val step = viewModel.currentState.step
        assertIs<AuthStep.QrLogin>(step)
        assertEquals("code-1", step.session?.code)
        assertFalse(step.stale, "a fresh code must not render behind the reload overlay")
    }

    @Test
    fun `the frame is drawn before the code arrives`() = runTest(dispatcher) {
        // The instructions are readable during the round trip rather than the
        // whole page being a spinner.
        val viewModel = viewModel()

        val step = viewModel.currentState.step
        assertIs<AuthStep.QrLogin>(step)
        assertEquals(null, step.session)
    }

    @Test
    fun `a scan completes sign-in`() = runTest(dispatcher) {
        val qr = FakeQrRepository(scannedAfterPolls = 2)
        val viewModel = viewModel(qr)

        viewModel.onEvent(AuthEvent.StartQrLogin)
        advanceTime(POLL * 3)

        assertEquals(1, qr.completeCount, "a scan must finalise the link exactly once")
        assertEquals(AuthStep.Complete, viewModel.currentState.step)
        // The link call needs the scanning device's id for its header; without
        // it the server answers 406.
        assertEquals(SCANNER_ID, qr.linkedWithScannerId)
    }

    @Test
    fun `polling continues through a transient failure`() = runTest(dispatcher) {
        // A dropped packet must not kill a session the user is mid-way through.
        val qr = FakeQrRepository(scannedAfterPolls = 3, failPollsAt = setOf(1, 2))
        val viewModel = viewModel(qr)

        viewModel.onEvent(AuthEvent.StartQrLogin)
        advanceTime(POLL * 4)

        assertEquals(AuthStep.Complete, viewModel.currentState.step)
    }

    @Test
    fun `an unscanned code goes stale but stays on screen`() = runTest(dispatcher) {
        // Never scanned. The loop must stop rather than poll forever — but the
        // web leaves the QR visible under a reload control, and a blank panel
        // would read as a broken page.
        val qr = FakeQrRepository(scannedAfterPolls = Int.MAX_VALUE)
        val viewModel = viewModel(qr)

        viewModel.onEvent(AuthEvent.StartQrLogin)
        advanceTime(QrLoginSession.LIFETIME_MILLIS + POLL * 2)

        val step = viewModel.currentState.step
        assertIs<AuthStep.QrLogin>(step)
        assertTrue(step.stale, "the reload control must appear once the window closes")
        assertEquals("code-1", step.session?.code, "the stale code must stay on screen")
        assertFalse(qr.completeCount > 0, "an unscanned code must not link a device")
    }

    @Test
    fun `polling stops once the window closes`() = runTest(dispatcher) {
        val qr = FakeQrRepository(scannedAfterPolls = Int.MAX_VALUE)
        val viewModel = viewModel(qr)

        viewModel.onEvent(AuthEvent.StartQrLogin)
        advanceTime(QrLoginSession.LIFETIME_MILLIS + POLL * 2)
        val pollsAtExpiry = qr.pollCount

        advanceTime(POLL * 20)

        assertEquals(pollsAtExpiry, qr.pollCount, "the loop kept polling a dead session")
    }

    @Test
    fun `requesting a new code replaces the old session`() = runTest(dispatcher) {
        val qr = FakeQrRepository(scannedAfterPolls = Int.MAX_VALUE)
        val viewModel = viewModel(qr)

        viewModel.onEvent(AuthEvent.StartQrLogin)
        advance()
        val first = (viewModel.currentState.step as AuthStep.QrLogin).session

        viewModel.onEvent(AuthEvent.RefreshQrCode)
        advance()
        val second = (viewModel.currentState.step as AuthStep.QrLogin).session

        assertTrue(first?.id != second?.id, "refresh must issue a new session")
    }

    @Test
    fun `navigating away stops the poll loop`() = runTest(dispatcher) {
        // Otherwise the desktop keeps asking about a code nobody is looking at.
        val qr = FakeQrRepository(scannedAfterPolls = Int.MAX_VALUE)
        val viewModel = viewModel(qr)

        viewModel.onEvent(AuthEvent.StartQrLogin)
        advanceTime(POLL * 2)
        viewModel.onEvent(AuthEvent.Back)
        val pollsAtExit = qr.pollCount

        advanceTime(POLL * 10)

        assertEquals(pollsAtExit, qr.pollCount, "polling continued after leaving the screen")
        assertEquals(AuthStep.Email, viewModel.currentState.step)
    }

    @Test
    fun `a failure to start is reported`() = runTest(dispatcher) {
        val qr = FakeQrRepository(startResult = ZillitResult.Failure(ZillitError.NoConnection()))
        val viewModel = viewModel(qr)

        viewModel.onEvent(AuthEvent.StartQrLogin)
        advance()

        // Stays on the QR page with no session: the page renders the reload
        // control in that state, so a failed fetch is recoverable rather than a
        // dead end.
        val step = viewModel.currentState.step
        assertIs<AuthStep.QrLogin>(step)
        assertEquals(null, step.session)
        assertTrue(viewModel.currentState.error != null)
    }

    @Test
    fun `the session never prints its code`() {
        // The code is a credential — anyone who photographs it can link a
        // device. It must not reach a log line via toString().
        val session = QrLoginSession(id = "s1", code = "super-secret-code", expiresAtMillis = 1_000)

        assertFalse(session.toString().contains("super-secret-code"), "the code leaked into toString()")
    }

    // -- helpers ----------------------------------------------------------

    /**
     * Runs pending work without advancing time.
     *
     * Deliberately not `advanceUntilIdle()` — the poll loop is never idle, so
     * that would run until the session expires (or forever, if it could not).
     */
    private fun TestScope.advance() = testScheduler.runCurrent()

    private fun TestScope.advanceTime(millis: Long) {
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
    }

    private fun viewModel(
        qr: FakeQrRepository = FakeQrRepository(scannedAfterPolls = 1),
    ) = AuthViewModel(
        authRepository = FakeAuthRepositoryForQr(),
        projectRepository = FakeProjectRepositoryForQr(),
        qrLoginRepository = qr,
        nowMillis = { clock },
    )

    private companion object {
        /** Must track `QR_POLL_INTERVAL_MILLIS`; the web polls at 3s. */
        const val POLL = 3_000L
        const val SCANNER_ID = "scanner-device-77"
    }

    private inner class FakeQrRepository(
        private val scannedAfterPolls: Int = 1,
        private val failPollsAt: Set<Int> = emptySet(),
        private val startResult: ZillitResult<QrLoginSession>? = null,
    ) : QrLoginRepository {

        var pollCount = 0
            private set
        var completeCount = 0
            private set
        private var sessions = 0

        override suspend fun startSession(): ZillitResult<QrLoginSession> {
            startResult?.let { return it }
            pollCount = 0
            sessions++
            return ZillitResult.Success(
                QrLoginSession(
                    id = "session-$sessions",
                    code = "code-$sessions",
                    expiresAtMillis = clock + QrLoginSession.LIFETIME_MILLIS,
                ),
            )
        }

        override suspend fun pollScanned(session: QrLoginSession): ZillitResult<String?> {
            pollCount++
            if (pollCount in failPollsAt) {
                return ZillitResult.Failure(ZillitError.NoConnection("simulated"))
            }
            // Null until scanned, then the scanning device's id — the shape the
            // real endpoint returns via `scanner_device_id`.
            return ZillitResult.Success(SCANNER_ID.takeIf { pollCount >= scannedAfterPolls })
        }

        var linkedWithScannerId: String? = null
            private set

        override suspend fun completeLink(
            session: QrLoginSession,
            scannerDeviceId: String,
        ): ZillitResult<DeviceIdentity> {
            completeCount++
            linkedWithScannerId = scannerDeviceId
            return ZillitResult.Success(DeviceIdentity("device-1", "crew@production.com", isPrimary = false))
        }
    }
}

private class FakeAuthRepositoryForQr : com.zillit.desktop.feature.auth.domain.AuthRepository {
    override val session = kotlinx.coroutines.flow.flowOf<com.zillit.desktop.feature.auth.domain.AuthSession?>(null)
    override suspend fun requestOtp(email: String, language: String) = ZillitResult.Success(Unit)
    override suspend fun verifyOtp(email: String, otp: String) = ZillitResult.Success("CONFIRM")
    override suspend fun registerDevice(email: String, confirmCode: String) =
        ZillitResult.Success(DeviceIdentity("d", "e", false))
    override suspend fun requestRecovery(email: String) = ZillitResult.Success(Unit)
    override suspend fun recoverWithCode(code: String) = ZillitResult.Success(DeviceIdentity("d", "e", false))
    var storedDevice: DeviceIdentity? = null
    override suspend fun restoreDevice() = ZillitResult.Success(storedDevice)

    override suspend fun rememberDevice(identity: DeviceIdentity) = ZillitResult.Success(kotlin.Unit)

    override suspend fun confirmDevice() = DeviceStatus.Valid

    override suspend fun signOut() = ZillitResult.Success(Unit)
}

private class FakeProjectRepositoryForQr : com.zillit.desktop.feature.auth.domain.ProjectRepository {
    private val only = Project(id = "p1", name = "Only Production", code = "P1", type = null, region = null)
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

    override suspend fun listProjects() = ZillitResult.Success(listOf(only))
    override suspend fun findByCode(code: String) = ZillitResult.Success(only)
    override suspend fun requestJoin(projectId: String, draft: JoinDraft) =
        ZillitResult.Success(com.zillit.desktop.feature.auth.domain.JoinStatus.Pending)

    override suspend fun departments(projectId: String) =
        ZillitResult.Success(emptyList<Department>())
    override suspend fun joinStatus(projectId: String) =
        ZillitResult.Success(com.zillit.desktop.feature.auth.domain.JoinStatus.Approved)
    override suspend fun listUnits(projectId: String) =
        ZillitResult.Success(emptyList<com.zillit.desktop.feature.auth.domain.Unit>())
    override suspend fun selectProject(
        project: Project,
        unit: com.zillit.desktop.feature.auth.domain.Unit?,
    ) = ZillitResult.Success(Unit)
}
