@file:Suppress("MaxLineLength") // Fixtures read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.tasks.domain.AttachmentBytes
import com.zillit.desktop.feature.tasks.domain.AttachmentKind
import com.zillit.desktop.feature.tasks.domain.PickedTaskFile
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAssignees
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskWrite
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.domain.AttachmentStep
import com.zillit.desktop.feature.tasks.domain.AttachmentWrite
import com.zillit.desktop.feature.tasks.domain.PreparedAttachment
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adding, waiting and removing an image or a file — the behaviour the web's
 * `useAttachments` has and the gotchas its wiki page lists:
 *
 *  - on a task that exists, each one goes up at once;
 *  - on a **new** task they wait for Create and go up after it;
 *  - nothing is offered where the service cannot keep them;
 *  - the limit of 20 is checked before anything is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskAttachmentsFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val granted = TasksViewer(resolved = true, enabled = true, canView = true, canPost = true)

    private fun model(repo: Repo, picked: List<PickedTaskFile> = emptyList()) = TasksViewModel(
        repository = repo,
        viewer = { granted },
        people = flowOf(TaskPeople("me", listOf(TaskPerson("me", "Me Myself")), projectId = "p1")),
        newId = { "unique" },
        pickFiles = { picked },
    )

    /** A PDF is the simplest thing to prepare: no decoder involved. */
    private fun pdf(name: String = "Reel.pdf", size: Int = 1_000) =
        PickedTaskFile(name, "application/pdf", ByteArray(size) { 1 })

    @Test
    fun `a file added to a task that exists goes up at once`() = runTest(dispatcher) {
        val repo = Repo(listOf(Task(id = "t1", title = "Dress the set")), TasksFeatures(attachments = true))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenTask("t1"))
        advanceUntilIdle()

        vm.onEvent(TasksEvent.AddAttachments(listOf(pdf())))
        advanceUntilIdle()

        assertEquals(listOf("Reel.pdf"), repo.attached.map { it.second.name })
        assertEquals("t1", repo.attached.single().first)
        // The panel has the task the write answered, and nothing waits.
        assertEquals(listOf("Reel.pdf"), vm.currentState.panel?.attachments?.map { it.name })
        assertEquals(emptyList(), vm.currentState.panel?.pending)
        assertEquals(0, vm.currentState.panel?.uploading)
        // And the list has it, so the card can show its count.
        assertEquals(1, vm.currentState.tasks.single { it.id == "t1" }.attachments.size)
    }

    @Test
    fun `on a new task they wait for Create and go up after it`() = runTest(dispatcher) {
        val repo = Repo(emptyList(), TasksFeatures(attachments = true))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.NewTask(TaskDraft(title = "New one")))
        advanceUntilIdle()

        vm.onEvent(TasksEvent.AddAttachments(listOf(pdf(), pdf("Notes.txt"))))
        advanceUntilIdle()

        // Nothing has been sent: they are waiting, with a word about why.
        assertEquals(listOf("Reel.pdf", "Notes.txt"), vm.currentState.panel?.pending?.map { it.name })
        assertTrue(repo.attached.isEmpty())
        assertTrue(vm.currentState.notice?.contains("create") == true)

        vm.onEvent(TasksEvent.Create)
        advanceUntilIdle()

        // Both went up, to the task that was just made.
        assertEquals(listOf("Reel.pdf", "Notes.txt"), repo.attached.map { it.second.name })
        assertTrue(repo.attached.all { it.first == "made-1" })
    }

    @Test
    fun `one that waited can be taken away before Create`() = runTest(dispatcher) {
        val repo = Repo(emptyList(), TasksFeatures(attachments = true))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.NewTask(TaskDraft(title = "New one")))
        advanceUntilIdle()
        vm.onEvent(TasksEvent.AddAttachments(listOf(pdf())))
        advanceUntilIdle()
        val waiting = vm.currentState.panel?.pending?.single()!!

        vm.onEvent(TasksEvent.RemovePendingAttachment(waiting.key))
        advanceUntilIdle()

        assertEquals(emptyList(), vm.currentState.panel?.pending)
        vm.onEvent(TasksEvent.Create)
        advanceUntilIdle()
        assertTrue(repo.attached.isEmpty(), "nothing was waiting to go up")
    }

    /** Off means gone: a service that cannot keep them is never sent one. */
    @Test
    fun `nothing is added where the service cannot keep them`() = runTest(dispatcher) {
        val repo = Repo(listOf(Task(id = "t1", title = "T")), TasksFeatures(attachments = false))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenTask("t1"))
        advanceUntilIdle()

        vm.onEvent(TasksEvent.AddAttachments(listOf(pdf())))
        vm.onEvent(TasksEvent.PickAttachments(imagesOnly = true))
        advanceUntilIdle()

        assertTrue(repo.attached.isEmpty())
        assertEquals(emptyList(), vm.currentState.panel?.pending)
    }

    @Test
    fun `a task already holding twenty takes no more, and says so once`() = runTest(dispatcher) {
        val full = (1..20).map { TaskAttachment(id = "a$it", name = "file$it.pdf") }
        val repo = Repo(listOf(Task(id = "t1", title = "T", attachments = full)), TasksFeatures(attachments = true))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenTask("t1"))
        advanceUntilIdle()

        vm.onEvent(TasksEvent.AddAttachments(listOf(pdf())))
        advanceUntilIdle()

        assertTrue(repo.attached.isEmpty())
        assertTrue(vm.currentState.notice?.contains("at most 20") == true, "the limit is said: ${vm.currentState.notice}")
    }

    @Test
    fun `removing one takes it off the panel and the list`() = runTest(dispatcher) {
        val held = Task(id = "t1", title = "T", attachments = listOf(TaskAttachment(id = "a1", name = "one.pdf", uploadedBy = "me")))
        val repo = Repo(listOf(held), TasksFeatures(attachments = true))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenTask("t1"))
        advanceUntilIdle()

        vm.onEvent(TasksEvent.RemoveAttachment(held.attachments.single()))
        advanceUntilIdle()

        assertEquals(listOf("t1" to "a1"), repo.removed)
        assertEquals(emptyList(), vm.currentState.panel?.attachments)
    }

    @Test
    fun `a download asks for a download link and hands it to the host once`() = runTest(dispatcher) {
        val held = Task(id = "t1", title = "T", attachments = listOf(TaskAttachment(id = "a1", name = "one.pdf")))
        val repo = Repo(listOf(held), TasksFeatures(attachments = true))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenTask("t1"))
        advanceUntilIdle()

        vm.onEvent(TasksEvent.DownloadAttachment(held.attachments.single()))
        advanceUntilIdle()

        assertEquals(listOf(Triple("t1", "a1", true)), repo.links)
        assertEquals("https://storage.test/a1", vm.currentState.panel?.openUrl)

        // The panel says it has opened it, and it is not opened twice.
        vm.onEvent(TasksEvent.OpenedUrl)
        advanceUntilIdle()
        assertNull(vm.currentState.panel?.openUrl)
    }

    @Test
    fun `an image's bytes are fetched once and kept`() = runTest(dispatcher) {
        val image = TaskAttachment(id = "a1", name = "one.jpg", kind = AttachmentKind.Image, url = "https://storage.test/a1")
        val repo = Repo(listOf(Task(id = "t1", title = "T", attachments = listOf(image))), TasksFeatures(attachments = true))
        val vm = model(repo)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenTask("t1"))
        advanceUntilIdle()

        vm.onEvent(TasksEvent.LoadAttachment(image))
        vm.onEvent(TasksEvent.LoadAttachment(image))
        advanceUntilIdle()

        assertEquals(1, repo.fetched.size, "asked once, however often the thumbnail recomposes")
        assertTrue(vm.currentState.panel?.imageBytes?.containsKey("a1") == true)
        // The link it had was good, so no fresh one was asked for.
        assertTrue(repo.links.isEmpty())
    }

    /** A repository that keeps what it was asked, and answers with the task. */
    private class Repo(
        private var held: List<Task>,
        private val features: TasksFeatures,
    ) : TasksRepository {
        val attached = mutableListOf<Pair<String, PreparedAttachment>>()
        val removed = mutableListOf<Pair<String, String>>()
        val links = mutableListOf<Triple<String, String, Boolean>>()
        val fetched = mutableListOf<String>()
        private var made = 0

        override suspend fun tasks() = ZillitResult.Success(TasksRepository.TaskList(held, features))
        override suspend fun assignees() = ZillitResult.Success(TaskAssignees(emptySet(), emptySet()))
        override suspend fun departments() = ZillitResult.Success(emptyList<TaskDepartment>())

        override suspend fun task(taskId: String): ZillitResult<Task> =
            held.firstOrNull { it.id == taskId }?.let { ZillitResult.Success(it.copy(comments = emptyList(), history = emptyList())) }
                ?: ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Http(404, "task_not_exists"))

        override suspend fun create(draft: TaskDraft, uniqueId: String, quick: Boolean, features: TasksFeatures): TaskWrite {
            made += 1
            val task = Task(id = "made-$made", title = draft.title, isSelf = draft.isSelf, parentId = draft.parentId)
            held = held + task
            return TaskWrite.Saved(task, null)
        }

        override suspend fun update(taskId: String, changes: Map<String, String?>, expectedUpdated: Long?): TaskWrite =
            TaskWrite.Saved(held.first { it.id == taskId }, null)

        override suspend fun delete(taskId: String) = ZillitResult.Success(null)

        override suspend fun comment(taskId: String, text: String, mentions: List<String>) =
            ZillitResult.Success(held.first { it.id == taskId })

        override suspend fun addAttachment(taskId: String, item: PreparedAttachment): AttachmentWrite {
            attached += taskId to item
            return answer(taskId) { it + TaskAttachment(id = "att-${attached.size}", name = item.name, kind = item.kind, uploadedBy = "me") }
        }

        override suspend fun removeAttachment(taskId: String, attachmentId: String): AttachmentWrite {
            removed += taskId to attachmentId
            return answer(taskId) { list -> list.filterNot { it.id == attachmentId } }
        }

        override suspend fun attachmentLink(taskId: String, attachmentId: String, download: Boolean): ZillitResult<String> {
            links += Triple(taskId, attachmentId, download)
            return ZillitResult.Success("https://storage.test/$attachmentId")
        }

        override suspend fun attachmentBytes(url: String): ByteArray? {
            fetched += url
            return ByteArray(8)
        }

        private fun answer(taskId: String, change: (List<TaskAttachment>) -> List<TaskAttachment>): AttachmentWrite {
            val task = held.firstOrNull { it.id == taskId } ?: Task(id = taskId, title = "T")
            val fresh = task.copy(attachments = change(task.attachments))
            held = if (held.any { it.id == taskId }) held.map { if (it.id == taskId) fresh else it } else held + fresh
            return AttachmentWrite.Saved(fresh, null)
        }

        /** Unused here, but a refusal has to be constructible from the same shapes. */
        @Suppress("unused")
        fun refusal() = AttachmentWrite.Refused(
            step = AttachmentStep.Upload,
            message = null,
            httpStatus = 0,
            error = com.zillit.desktop.core.common.ZillitError.Validation("no"),
        )
    }
}
