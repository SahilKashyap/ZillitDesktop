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
import com.zillit.desktop.feature.auth.domain.ProjectListStore
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker without a network: the last list this device was given is
 * shown, and a failed refresh keeps it rather than replacing it with "you
 * are on no production".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineProjectListTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class LinkedDevice : AuthRepository {
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

    private class Projects(private val answer: () -> ZillitResult<List<Project>>) : ProjectRepository {
        private val one = Project("p1", "One", "P1", null, null)
        override suspend fun listProjects() = answer()
        override suspend fun findByCode(code: String) = ZillitResult.Success(one)
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

    private class MemoryStore(var saved: List<Project>) : ProjectListStore {
        override fun load() = saved
        override fun save(projects: List<Project>) {
            saved = projects
        }
    }

    private val saved = listOf(Project("a", "Alpha", "A1", null, null), Project("b", "Beta", "B1", null, null))

    @Test
    fun `offline, the saved list is shown and a failed refresh keeps it`() = runTest(dispatcher) {
        val store = MemoryStore(saved)
        val viewModel = AuthViewModel(
            LinkedDevice(),
            Projects { ZillitResult.Failure(ZillitError.NoConnection()) },
            qrLoginRepository = null,
            projectListStore = store,
        )
        advanceUntilIdle()

        val state = viewModel.currentState
        assertEquals(AuthStep.ProjectSelection, state.step)
        assertEquals(listOf("a", "b"), state.projects.map { it.id })
        assertTrue(state.isShowingSavedProjects)
        assertFalse(state.isBusy)
        // No red banner: the productions are real, only the refresh is missing.
        assertNull(state.error)
        assertEquals(saved, store.saved)
    }

    @Test
    fun `online, the server's list replaces the saved one and is saved back`() = runTest(dispatcher) {
        val store = MemoryStore(saved)
        val fresh = listOf(Project("a", "Alpha", "A1", null, null), Project("c", "Gamma", "C1", null, null))
        val viewModel = AuthViewModel(
            LinkedDevice(),
            Projects { ZillitResult.Success(fresh) },
            qrLoginRepository = null,
            projectListStore = store,
        )
        advanceUntilIdle()

        val state = viewModel.currentState
        assertEquals(listOf("a", "c"), state.projects.map { it.id })
        assertFalse(state.isShowingSavedProjects)
        assertEquals(fresh, store.saved)
    }

    @Test
    fun `offline, a production this computer has never opened says so instead of opening`() = runTest(dispatcher) {
        val projects = Projects { ZillitResult.Failure(ZillitError.NoConnection()) }
        val viewModel = AuthViewModel(
            LinkedDevice(),
            projects,
            qrLoginRepository = null,
            projectListStore = MemoryStore(saved),
            isOnline = { false },
            hasOfflineData = { id -> id == "a" },
        )
        advanceUntilIdle()

        viewModel.onEvent(com.zillit.desktop.feature.auth.ui.AuthEvent.SelectProject(saved[1]))
        advanceUntilIdle()
        assertEquals(AuthStep.ProjectSelection, viewModel.currentState.step, "Beta was never visited: not opened")
        assertEquals(
            "No offline data available for this project. Connect to the internet to open it.",
            viewModel.currentState.error,
        )
        assertFalse(viewModel.currentState.isBusy)

        viewModel.onEvent(com.zillit.desktop.feature.auth.ui.AuthEvent.SelectProject(saved[0]))
        advanceUntilIdle()
        assertEquals(AuthStep.Complete, viewModel.currentState.step, "Alpha was saved: it opens from disk")
        assertNull(viewModel.currentState.error)
    }

    @Test
    fun `with nothing saved, a failed load still explains itself`() = runTest(dispatcher) {
        val viewModel = AuthViewModel(
            LinkedDevice(),
            Projects { ZillitResult.Failure(ZillitError.NoConnection()) },
            qrLoginRepository = null,
            projectListStore = MemoryStore(emptyList()),
        )
        advanceUntilIdle()

        val state = viewModel.currentState
        assertTrue(state.projects.isEmpty())
        assertFalse(state.isShowingSavedProjects)
        assertEquals("No internet connection.", state.error)
    }
}
