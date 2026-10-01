@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAssignees
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskWrite
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.ui.TaskPeople
import com.zillit.desktop.feature.tasks.ui.TasksEvent
import com.zillit.desktop.feature.tasks.ui.TasksViewModel
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.Todo,
        parent: String? = null,
        updated: Long = 1,
        canDelete: Boolean = true,
        due: String? = null,
    ) = Task(
        id = id, title = id, status = status, parentId = parent, updatedMillis = updated, canDelete = canDelete,
        createdBy = "me", dueDate = due,
    )

    private val granted = TasksViewer(resolved = true, enabled = true, canView = true, canPost = true)

    private fun model(
        repository: FakeTasksRepository,
        viewer: TasksViewer = granted,
    ) = TasksViewModel(
        repository = repository,
        viewer = { viewer },
        people = flowOf(TaskPeople("me", listOf(TaskPerson("me", "Me Myself"), TaskPerson("u1", "Una One")))),
        newId = { "id-${repository.created.size}" },
    )

    /** A production without the tool: the service would answer 403, which reads as "removed from the project". */
    @Test
    fun `nothing is requested until the tool's rights confirm it`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        val model = model(repository, viewer = TasksViewer(resolved = true, enabled = false))

        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        assertEquals(0, repository.listCalls)
        assertEquals(false, model.state.value.loaded)
    }

    @Test
    fun `it loads the list, the assignees and the departments`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"), task("b"))
        val model = model(repository)

        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(listOf("a", "b"), state.tasks.map { it.id })
        assertEquals(setOf("u1"), state.assignableIds)
        assertEquals("Art", state.departmentName("d1"))
        assertEquals("me", state.me)
    }

    /** The web refuses before sending; so does this, and says why. */
    @Test
    fun `completing a main task with an open subtask is refused before anything is sent`() = runTest(dispatcher) {
        val main = task("m")
        val repository = FakeTasksRepository.holding(main, task("s", parent = "m"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.SetStatus(main, TaskStatus.Done))
        advanceUntilIdle()

        assertTrue(repository.updates.isEmpty())
        assertNotNull(model.state.value.notice)
    }

    @Test
    fun `a one-click status change saves and refreshes the list`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.SetStatus(task("a"), TaskStatus.Done))
        advanceUntilIdle()

        assertEquals(Triple<String, Map<String, String?>, Long?>("a", mapOf("status" to "done"), null), repository.updates.single())
        assertEquals(TaskStatus.Done, model.state.value.tasks.single().status)
        // The write may have moved other tasks: the list is read again.
        assertEquals(2, repository.listCalls)
    }

    @Test
    fun `without posting rights a status change does nothing`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository, viewer = granted.copy(canPost = false))
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.SetStatus(task("a"), TaskStatus.Done))
        advanceUntilIdle()

        assertTrue(repository.updates.isEmpty())
    }

    @Test
    fun `opening a task reads it, and closing with edits is refused`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.OpenTask("a"))
        advanceUntilIdle()
        val opened = model.state.value.panel!!
        assertTrue(opened.ready)

        model.onEvent(TasksEvent.DraftChanged(opened.draft!!.copy(title = "Renamed")))
        model.onEvent(TasksEvent.ClosePanel)
        advanceUntilIdle()

        // Closing, and moving to another task, are both refused while there are unsaved edits.
        assertTrue(model.state.value.panel!!.nag)
        model.onEvent(TasksEvent.OpenTask("a"))
        advanceUntilIdle()
        assertEquals(opened.key, model.state.value.panel!!.key)

        model.onEvent(TasksEvent.Discard)
        model.onEvent(TasksEvent.ClosePanel)
        assertNull(model.state.value.panel)
    }

    @Test
    fun `a save sends only the change, with the version it started from`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a", updated = 77))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        model.onEvent(TasksEvent.OpenTask("a"))
        advanceUntilIdle()

        model.onEvent(TasksEvent.DraftChanged(model.state.value.panel!!.draft!!.copy(scenes = "12")))
        model.onEvent(TasksEvent.Save())
        advanceUntilIdle()

        assertEquals(Triple("a", mapOf<String, String?>("scenes" to "12"), 77L), repository.updates.single())
        assertEquals(false, model.state.value.panel!!.dirty)
    }

    /** Someone else saved first: their version is offered, mine is kept. */
    @Test
    fun `a conflict offers their version and keeps my edit`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        model.onEvent(TasksEvent.OpenTask("a"))
        advanceUntilIdle()
        repository.conflictWith = task("a", updated = 99).copy(scenes = "theirs")

        model.onEvent(TasksEvent.DraftChanged(model.state.value.panel!!.draft!!.copy(scenes = "mine")))
        model.onEvent(TasksEvent.Save())
        advanceUntilIdle()

        val panel = model.state.value.panel!!
        assertEquals("theirs", panel.conflict?.scenes)
        assertEquals("mine", panel.draft?.scenes)

        model.onEvent(TasksEvent.LoadTheirs)
        assertEquals("theirs", model.state.value.panel!!.draft?.scenes)
        assertNull(model.state.value.panel!!.conflict)
    }

    @Test
    fun `a new subtask starts in its main task's department and goes back to it once created`() = runTest(dispatcher) {
        val main = task("m").copy(departmentId = "d1")
        val repository = FakeTasksRepository.holding(main)
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.NewSubtask(main))
        val form = model.state.value.panel!!
        assertEquals("d1", form.draft?.departmentId)
        assertEquals("m", form.draft?.parentId)

        model.onEvent(TasksEvent.DraftChanged(form.draft!!.copy(title = "Buy paint")))
        model.onEvent(TasksEvent.Create)
        advanceUntilIdle()

        assertEquals("m", repository.created.single().first.parentId)
        // Back on the main task, not closed.
        assertEquals("m", model.state.value.panel?.taskId)
    }

    @Test
    fun `a subtask cannot be due after its main task`() = runTest(dispatcher) {
        val main = task("m", due = "2026-10-10")
        val repository = FakeTasksRepository.holding(main)
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        model.onEvent(TasksEvent.NewSubtask(main))

        model.onEvent(TasksEvent.DraftChanged(model.state.value.panel!!.draft!!.copy(title = "x", dueDate = "2026-10-11")))
        model.onEvent(TasksEvent.Create)
        advanceUntilIdle()

        assertTrue(repository.created.isEmpty())
        assertNotNull(model.state.value.notice)
    }

    /** A lost reply must not make two tasks: the same id is sent until a create succeeds. */
    @Test
    fun `a quick add reuses its id until one succeeds`() = runTest(dispatcher) {
        val repository = FakeTasksRepository().also { it.failNextCreate = true }
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.QuickAdd("Call the vet", "2026-10-02", null))
        advanceUntilIdle()
        model.onEvent(TasksEvent.QuickAdd("Call the vet", "2026-10-02", null))
        advanceUntilIdle()
        model.onEvent(TasksEvent.QuickAdd("Another", null, null))
        advanceUntilIdle()

        val ids = repository.createAttempts
        assertEquals(ids[0], ids[1])
        assertTrue(ids[2] != ids[1])
        assertTrue(model.state.value.tasks.all { it.isSelf })
    }

    @Test
    fun `a comment sends the mentions it names and clears the box`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        model.onEvent(TasksEvent.OpenTask("a"))
        advanceUntilIdle()

        model.onEvent(TasksEvent.CommentChanged("@Una One please look"))
        model.onEvent(TasksEvent.SendComment)
        advanceUntilIdle()

        assertEquals(Triple("a", "@Una One please look", listOf("u1")), repository.comments.single())
        assertEquals("", model.state.value.panel!!.comment)
    }

    @Test
    fun `deleting from a subtask's panel returns to its main task`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("m"), task("s", parent = "m"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        model.onEvent(TasksEvent.OpenTask("s"))
        advanceUntilIdle()

        model.onEvent(TasksEvent.DeleteOpenTask)
        advanceUntilIdle()

        assertEquals(listOf("s"), repository.deleted)
        assertEquals(listOf("m"), model.state.value.tasks.map { it.id })
        assertEquals("m", model.state.value.panel?.taskId)
    }

    @Test
    fun `a task that is gone closes its panel`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        repository.held = emptyList()

        model.onEvent(TasksEvent.OpenTask("a"))
        advanceUntilIdle()

        assertNull(model.state.value.panel)
        assertTrue(model.state.value.tasks.isEmpty())
    }

    /** A fake service holding its tasks in memory and recording what was asked of it. */
    private class FakeTasksRepository : TasksRepository {
        companion object {
            fun holding(vararg tasks: Task) = FakeTasksRepository().also { it.held = tasks.toList() }
        }

        var held: List<Task> = emptyList()
        var listCalls = 0
        val updates = mutableListOf<Triple<String, Map<String, String?>, Long?>>()
        val created = mutableListOf<Pair<TaskDraft, String>>()
        val createAttempts = mutableListOf<String>()
        val comments = mutableListOf<Triple<String, String, List<String>>>()
        val deleted = mutableListOf<String>()
        var conflictWith: Task? = null
        var failNextCreate = false

        override suspend fun tasks(): ZillitResult<List<Task>> {
            listCalls += 1
            return ZillitResult.Success(held)
        }

        override suspend fun assignees() = ZillitResult.Success(TaskAssignees(setOf("u1"), emptySet()))

        override suspend fun departments() = ZillitResult.Success(listOf(TaskDepartment("d1", "Art")))

        override suspend fun task(taskId: String): ZillitResult<Task> =
            held.firstOrNull { it.id == taskId }?.let { ZillitResult.Success(it.copy(comments = emptyList(), history = emptyList())) }
                ?: ZillitResult.Failure(ZillitError.Http(404, "task_not_exists"))

        override suspend fun create(draft: TaskDraft, uniqueId: String): TaskWrite {
            createAttempts += uniqueId
            if (failNextCreate) {
                failNextCreate = false
                return TaskWrite.Refused("boom", 500, ZillitError.Http(500, "boom"))
            }
            created += draft to uniqueId
            val made = Task(
                id = "new-${created.size}", title = draft.title, parentId = draft.parentId, isSelf = draft.isSelf,
                dueDate = draft.dueDate, createdBy = "me", status = draft.status,
            )
            held = held + made
            return TaskWrite.Saved(made)
        }

        override suspend fun update(taskId: String, changes: Map<String, String?>, expectedUpdated: Long?): TaskWrite {
            updates += Triple(taskId, changes, expectedUpdated)
            conflictWith?.let { return TaskWrite.Conflict(it) }
            val current = held.first { it.id == taskId }
            val next = current.copy(
                status = changes["status"]?.let { TaskStatus.fromWire(it) } ?: current.status,
                scenes = changes["scenes"] ?: current.scenes,
                updatedMillis = current.updatedMillis + 1,
            )
            held = held.map { if (it.id == taskId) next else it }
            return TaskWrite.Saved(next)
        }

        override suspend fun delete(taskId: String): ZillitResult<Unit> {
            deleted += taskId
            held = held.filter { it.id != taskId && it.parentId != taskId }
            return ZillitResult.Success(Unit)
        }

        override suspend fun comment(taskId: String, text: String, mentions: List<String>): ZillitResult<Task> {
            comments += Triple(taskId, text, mentions)
            return ZillitResult.Success(held.first { it.id == taskId }.copy(comments = emptyList(), history = emptyList()))
        }
    }
}
