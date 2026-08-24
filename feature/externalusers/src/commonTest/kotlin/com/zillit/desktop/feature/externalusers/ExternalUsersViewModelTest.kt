package com.zillit.desktop.feature.externalusers

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersRepository
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersViewer
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersEffect
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersEvent
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * The QA report behind these: "shows all project users — show them project
 * wise". The view model is built once per sign-in and outlives production
 * switches, so it must wipe and refetch rather than keep serving the last
 * production's roster.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalUsersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val rowA = ExternalUser(id = "a1", fullName = "Alpha Grip", email = "a@a.co", createdBy = "me")
    private val rowB = ExternalUser(id = "b1", fullName = "Beta Vendor", email = "b@b.co", createdBy = "them")

    /** Answers each `list` call from a queue, so late answers can differ. */
    private class FakeRepository : ExternalUsersRepository {
        val answers = ArrayDeque<List<ExternalUser>>()
        var listCalls = 0
        override suspend fun list(
            bucket: ExternalUserBucket,
            timestampMillis: Long,
            older: Boolean,
        ): ZillitResult<List<ExternalUser>> {
            listCalls++
            return ZillitResult.Success(answers.removeFirstOrNull().orEmpty())
        }
        override suspend fun create(user: ExternalUser) = ZillitResult.Success(Unit)
        override suspend fun update(user: ExternalUser) = ZillitResult.Success(Unit)
        override suspend fun delete(id: String) = ZillitResult.Success(Unit)
    }

    private val poster = ExternalUsersViewer(userId = "me", canView = true, canPost = true, ready = true)

    private fun viewModel(
        repository: FakeRepository,
        viewer: ExternalUsersViewer = poster,
        projectId: () -> String? = { "prod-a" },
    ) = ExternalUsersViewModel(
        repository = repository,
        resolveViewer = { viewer },
        loadDepartments = { emptyList() },
        nowMillis = { 0L },
        projectId = projectId,
    )

    @Test
    fun `switching productions wipes the roster before the refetch answers`() = runTest {
        val repository = FakeRepository()
        var project = "prod-a"
        repository.answers += listOf(rowA)
        val vm = viewModel(repository, projectId = { project })

        vm.start()
        runCurrent()
        assertEquals(listOf(rowA), vm.currentState.users)

        // The production switches; the tool window reopens and starts again.
        project = "prod-b"
        repository.answers += listOf(rowB)
        vm.start()
        // Before the fetch answers: A's rows are already gone, Loading shows.
        assertTrue(vm.currentState.users.isEmpty())
        assertTrue(vm.currentState.isLoading)

        runCurrent()
        assertEquals(listOf(rowB), vm.currentState.users)
        assertEquals(2, repository.listCalls)
    }

    @Test
    fun `a late answer from the last production is dropped`() = runTest {
        val repository = FakeRepository()
        var project = "prod-a"
        val vm = viewModel(repository, projectId = { project })

        vm.start() // A's fetch is queued but has not answered yet.
        project = "prod-b"
        vm.start() // The switch wipes; B's fetch is queued behind A's.
        repository.answers += listOf(rowA) // A's fetch answers first…
        repository.answers += listOf(rowB) // …then B's.
        runCurrent()

        assertEquals(listOf(rowB), vm.currentState.users)
    }

    @Test
    fun `reopening under the same production keeps rows while refreshing`() = runTest {
        val repository = FakeRepository()
        repository.answers += listOf(rowA)
        val vm = viewModel(repository)

        vm.start()
        runCurrent()
        repository.answers += listOf(rowA)
        vm.start()

        // Same production: no blank flash, just a refresh in place.
        assertEquals(listOf(rowA), vm.currentState.users)
        assertTrue(vm.currentState.isLoading)
        runCurrent()
        assertEquals(2, repository.listCalls)
    }

    @Test
    fun `onProjectChanged wipes at once, without waiting for a reopen`() = runTest {
        val repository = FakeRepository()
        repository.answers += listOf(rowA)
        val vm = viewModel(repository)
        vm.start()
        runCurrent()

        vm.onProjectChanged()

        assertTrue(vm.currentState.users.isEmpty())
    }

    @Test
    fun `an unknown production is treated as changed`() = runTest {
        val repository = FakeRepository()
        repository.answers += listOf(rowA)
        val vm = viewModel(repository, projectId = { null })
        vm.start()
        runCurrent()
        assertEquals(listOf(rowA), vm.currentState.users)

        repository.answers += listOf(rowB)
        vm.start()

        // No id to compare: wipe rather than risk another production's rows.
        assertTrue(vm.currentState.users.isEmpty())
    }

    @Test
    fun `a view-only viewer gets no edit affordances and edits are refused`() = runTest {
        val repository = FakeRepository()
        repository.answers += listOf(rowB)
        val readOnly = ExternalUsersViewer(userId = "me", canView = true, canPost = false, ready = true)
        val vm = viewModel(repository, viewer = readOnly)
        val effects = mutableListOf<ExternalUsersEffect>()
        val collector = launch { vm.effects.collect { effects += it } }

        vm.start()
        runCurrent()

        // The state the screen gates on: no Add button, no row pencil.
        assertFalse(vm.currentState.viewer.canPost || vm.currentState.viewer.isAdmin)
        assertFalse(vm.currentState.viewer.mayEdit(rowB))

        vm.onEvent(ExternalUsersEvent.New)
        vm.onEvent(ExternalUsersEvent.Edit(rowB))
        vm.onEvent(ExternalUsersEvent.Delete(rowB))
        runCurrent()

        assertNull(vm.currentState.editing)
        assertNull(vm.currentState.confirmDelete)
        assertEquals(3, effects.filterIsInstance<ExternalUsersEffect.Notice>().size)
        collector.cancel()
    }

    @Test
    fun `a poster may edit only their own rows unless admin - the web's rule`() {
        assertTrue(poster.mayEdit(rowA))
        assertFalse(poster.mayEdit(rowB))
        assertTrue(poster.copy(isAdmin = true).mayEdit(rowB))
    }
}
