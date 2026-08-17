package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitError
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
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Resuming a device that is already linked.
 *
 * The device id is written to the keychain at link time and was never read
 * back, so every launch demanded a fresh QR scan — a papercut the web does not
 * have, since a browser session persists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionResumeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth(
        private val stored: DeviceIdentity?,
        private val fail: Boolean = false,
    ) : AuthRepository {
        override val session = flowOf<AuthSession?>(null)
        override suspend fun restoreDevice(): ZillitResult<DeviceIdentity?> =
            if (fail) ZillitResult.Failure(ZillitError.Crypto("keychain locked"))
            else ZillitResult.Success(stored)

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

    private class FakeProjects : ProjectRepository {
        private val only = Project("p1", "Only", "P1", null, null)
        override suspend fun listProjects() = ZillitResult.Success(listOf(only, only.copy(id = "p2")))
        override suspend fun findByCode(code: String) = ZillitResult.Success(only)
        override suspend fun create(
            draft: NewProductionDraft,
            selectedType: ProductionType?,
            confirmCode: String,
        ) = ZillitResult.Success(only)
        override suspend fun setFavourite(projectId: String, favourite: Boolean) =
            ZillitResult.Success(kotlin.Unit)
        override suspend fun leaveProject() = ZillitResult.Success(kotlin.Unit)
        override suspend fun requestJoin(projectId: String, draft: JoinDraft) =
            ZillitResult.Success(JoinStatus.Pending)

        override suspend fun departments(projectId: String) =
            ZillitResult.Success(emptyList<Department>())
        override suspend fun joinStatus(projectId: String) = ZillitResult.Success(JoinStatus.Approved)
        override suspend fun listUnits(projectId: String) =
            ZillitResult.Success(emptyList<com.zillit.desktop.feature.auth.domain.Unit>())
        override suspend fun selectProject(
            project: Project,
            unit: com.zillit.desktop.feature.auth.domain.Unit?,
        ) = ZillitResult.Success(kotlin.Unit)
    }

    private fun viewModel(auth: AuthRepository) =
        AuthViewModel(auth, FakeProjects(), qrLoginRepository = null, nowMillis = { 0L })

    @Test
    fun `a linked device goes straight to the production picker`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeAuth(DeviceIdentity("device-1", "", false)))

        advanceUntilIdle()

        assertEquals(AuthStep.ProjectSelection, viewModel.currentState.step)
    }

    @Test
    fun `an unlinked machine still gets the QR screen`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeAuth(stored = null))

        advanceUntilIdle()

        assertIs<AuthStep.QrLogin>(viewModel.currentState.step)
    }

    @Test
    fun `a keychain that will not open falls back to signing in`() = runTest(dispatcher) {
        // Being unable to read the keychain must not strand the user on a blank
        // screen — the QR flow does not need it.
        val viewModel = viewModel(FakeAuth(stored = null, fail = true))

        advanceUntilIdle()

        assertIs<AuthStep.QrLogin>(viewModel.currentState.step)
    }
}
