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
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.ui.TaskPeople
import com.zillit.desktop.feature.tasks.ui.TasksEvent
import com.zillit.desktop.feature.tasks.ui.TasksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
        rights: (() -> TasksViewer)? = null,
        socket: FakeSocket? = null,
    ) = TasksViewModel(
        repository = repository,
        viewer = rights ?: { viewer },
        people = flowOf(TaskPeople("me", listOf(TaskPerson("me", "Me Myself"), TaskPerson("u1", "Una One")), projectId = "p1")),
        events = socket?.let { SocketEventBus(it) },
        newId = { "id-${repository.created.size}" },
    )

    /** A socket whose frames the test sends. */
    private class FakeSocket : SocketClient {
        val frames = MutableSharedFlow<SocketMessage>(extraBufferCapacity = 8)
        override val connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Connected("fake"))
        override val messages: Flow<SocketMessage> = frames
        override suspend fun connect(config: SocketConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun <T> emit(event: SocketEventName, payload: T, serializer: KSerializer<T>) = ZillitResult.Success(Unit)
        override suspend fun emit(event: SocketEventName): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun <T> emitForAck(event: SocketEventName, payload: T, serializer: KSerializer<T>): ZillitResult<JsonElement> =
            ZillitResult.Success(Json.parseToJsonElement("{}"))

        fun send(project: String, task: String) {
            frames.tryEmit(SocketMessage(SocketEventName("tasks:updated"), Json.parseToJsonElement("""{"project_id":"$project","task_id":"$task"}""")))
        }
    }

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
        assertEquals(false, model.state.value.panelDirty)
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

    @Test
    fun `a dropped card waits in its new column until the server answers`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.Move(task("a"), TaskStatus.Progress))
        assertEquals(TaskStatus.Progress, model.state.value.moving["a"])
        advanceUntilIdle()

        assertTrue(model.state.value.moving.isEmpty())
        assertEquals(TaskStatus.Progress, model.state.value.byId.getValue("a").status)
    }

    @Test
    fun `a refused drop lets the card go back at once`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a")).also { it.refuseUpdates = true }
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.Move(task("a"), TaskStatus.Done))
        advanceUntilIdle()

        assertTrue(model.state.value.moving.isEmpty())
        assertEquals(TaskStatus.Todo, model.state.value.byId.getValue("a").status)
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `a main task with open subtasks is not moved to Done at all`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("m"), task("s", parent = "m"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.Move(task("m"), TaskStatus.Done))
        advanceUntilIdle()

        assertTrue(model.state.value.moving.isEmpty())
        assertTrue(repository.updates.isEmpty())
        assertNotNull(model.state.value.notice)
    }

    @Test
    fun `a saved write shows what the server announced`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.SetStatus(task("a"), TaskStatus.Progress))
        advanceUntilIdle()

        assertNotNull(model.state.value.success)
        assertNull(model.state.value.error)
    }

    @Test
    fun `quick add sends only its own fields and lets go of the text only when saved`() = runTest(dispatcher) {
        val repository = FakeTasksRepository().also { it.failNextCreate = true }
        val model = model(repository)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        model.onEvent(TasksEvent.QuickAdd("Call rental house", "2026-10-12", null))
        advanceUntilIdle()
        assertEquals(0, model.state.value.quickAdded)

        model.onEvent(TasksEvent.QuickAdd("Call rental house", "2026-10-12", null))
        advanceUntilIdle()
        assertEquals(1, model.state.value.quickAdded)
        // Both attempts went as quick adds, and the retry reused the refused one's id.
        assertEquals(2, repository.quickCreates)
        assertEquals(1, repository.createAttempts.distinct().size)
    }

    @Test
    fun `rights granted while the tool is open load it, and taken away close the panel`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        var rights = TasksViewer(resolved = true, enabled = true, canView = false)
        val model = model(repository, rights = { rights })
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        assertEquals(0, repository.listCalls)

        rights = granted
        model.onRightsChanged()
        advanceUntilIdle()
        assertEquals(1, repository.listCalls)
        model.onEvent(TasksEvent.OpenTask("a"))
        advanceUntilIdle()
        assertNotNull(model.state.value.panel)

        rights = TasksViewer(resolved = true, enabled = true, canView = false)
        model.onRightsChanged()
        assertNull(model.state.value.panel)
    }

    @Test
    fun `a socket frame for another production is ignored`() = runTest(dispatcher) {
        val repository = FakeTasksRepository.holding(task("a"))
        val socket = FakeSocket()
        val model = model(repository, socket = socket)
        model.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        val before = repository.listCalls

        socket.send(project = "other", task = "a")
        advanceUntilIdle()
        assertEquals(before, repository.listCalls)

        socket.send(project = "p1", task = "a")
        advanceUntilIdle()
        assertEquals(before + 1, repository.listCalls)
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
        var refuseUpdates = false
        var quickCreates = 0

        /** What the fake service says it can keep; off, as a service from before the fields is. */
        var features = TasksFeatures()

        override suspend fun tasks(): ZillitResult<TasksRepository.TaskList> {
            listCalls += 1
            return ZillitResult.Success(TasksRepository.TaskList(held, features))
        }

        override suspend fun assignees() = ZillitResult.Success(TaskAssignees(setOf("u1"), emptySet()))

        override suspend fun departments() = ZillitResult.Success(listOf(TaskDepartment("d1", "Art")))

        override suspend fun task(taskId: String): ZillitResult<Task> =
            held.firstOrNull { it.id == taskId }?.let { ZillitResult.Success(it.copy(comments = emptyList(), history = emptyList())) }
                ?: ZillitResult.Failure(ZillitError.Http(404, "task_not_exists"))

        val createFeatures = mutableListOf<TasksFeatures>()

        override suspend fun create(draft: TaskDraft, uniqueId: String, quick: Boolean, features: TasksFeatures): TaskWrite {
            createAttempts += uniqueId
            createFeatures += features
            if (quick) quickCreates += 1
            if (failNextCreate) {
                failNextCreate = false
                return TaskWrite.Refused("boom", 500, ZillitError.Http(500, "boom"))
            }
            created += draft to uniqueId
            val made = Task(
                id = "new-${created.size}", title = draft.title, parentId = draft.parentId, isSelf = draft.isSelf,
                dueDate = draft.dueDate, createdBy = "me", status = draft.status,
                visibility = draft.visibility.takeIf { features.visibility },
            )
            held = held + made
            return TaskWrite.Saved(made, "task_created")
        }

        override suspend fun update(taskId: String, changes: Map<String, String?>, expectedUpdated: Long?): TaskWrite {
            updates += Triple(taskId, changes, expectedUpdated)
            conflictWith?.let { return TaskWrite.Conflict(it) }
            if (refuseUpdates) return TaskWrite.Refused("task_open_subtasks", 422, ZillitError.Http(422, "task_open_subtasks"))
            val current = held.first { it.id == taskId }
            val next = current.copy(
                status = changes["status"]?.let { TaskStatus.fromWire(it) } ?: current.status,
                scenes = changes["scenes"] ?: current.scenes,
                updatedMillis = current.updatedMillis + 1,
            )
            held = held.map { if (it.id == taskId) next else it }
            return TaskWrite.Saved(next, "task_updated")
        }

        override suspend fun delete(taskId: String): ZillitResult<String?> {
            deleted += taskId
            held = held.filter { it.id != taskId && it.parentId != taskId }
            return ZillitResult.Success("task_deleted")
        }

        override suspend fun comment(taskId: String, text: String, mentions: List<String>): ZillitResult<Task> {
            comments += Triple(taskId, text, mentions)
            return ZillitResult.Success(held.first { it.id == taskId }.copy(comments = emptyList(), history = emptyList()))
        }
    }
}
