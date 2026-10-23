@file:Suppress("MaxLineLength","LongParameterList","TooManyFunctions") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketClient
import com.zillit.desktop.core.socket.SocketConfig
import com.zillit.desktop.core.socket.SocketConnectionState
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.feature.tasks.domain.AttachmentStep
import com.zillit.desktop.feature.tasks.domain.AttachmentWrite
import com.zillit.desktop.feature.tasks.domain.DraftAttachmentWrite
import com.zillit.desktop.feature.tasks.domain.DraftWrite
import com.zillit.desktop.feature.tasks.domain.PreparedAttachment
import com.zillit.desktop.feature.tasks.domain.SavedDraftData
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAssignees
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.TaskWrite
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.ui.TaskPeople
import com.zillit.desktop.feature.tasks.ui.TasksViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * What the Tasks view-model tests are driven with: a fake service that holds
 * its tasks and drafts in memory and records what was asked of it, a socket
 * whose frames a test sends, and the fixtures around them.
 *
 * Shared rather than nested in one test class, because the tool's rules split
 * across more than one class of tests (the list and the panel; drafts).
 */

internal fun task(
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

internal val granted = TasksViewer(resolved = true, enabled = true, canView = true, canPost = true)

internal fun model(
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
internal class FakeSocket : SocketClient {
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

/** A fake service holding its tasks in memory and recording what was asked of it. */
internal class FakeTasksRepository : TasksRepository {
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

    // -- drafts -------------------------------------------------------

    var savedDrafts: List<SavedTaskDraft> = emptyList()
    var draftReads = 0
    val draftWrites = mutableListOf<Pair<String?, SavedDraftData>>()
    val deletedDrafts = mutableListOf<String>()
    val draftAttachmentsRemoved = mutableListOf<Pair<String, String>>()
    val draftAttachmentsAdded = mutableListOf<Pair<String, PreparedAttachment>>()
    var nextDraftId = 1
    var refuseDraftAsGone = false

    /** The Public | Private each quick add carried, if any. */
    val quickVisibility = mutableListOf<TaskVisibility?>()

    override suspend fun drafts(): ZillitResult<List<SavedTaskDraft>> {
        draftReads += 1
        return ZillitResult.Success(savedDrafts)
    }

    override suspend fun draft(draftId: String): ZillitResult<SavedTaskDraft> =
        savedDrafts.firstOrNull { it.id == draftId }?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Http(404, "task_draft_not_exists"))

    override suspend fun createDraft(isSelf: Boolean, parentId: String?, data: SavedDraftData): DraftWrite {
        draftWrites += null to data
        val made = SavedTaskDraft(
            id = "dr-${nextDraftId++}", isSelf = isSelf, parentId = parentId, data = data, updatedMillis = 100,
        )
        savedDrafts = savedDrafts + made
        return DraftWrite.Saved(made, "task_draft_saved")
    }

    override suspend fun updateDraft(draftId: String, data: SavedDraftData): DraftWrite {
        draftWrites += draftId to data
        if (refuseDraftAsGone) {
            refuseDraftAsGone = false
            savedDrafts = savedDrafts.filterNot { it.id == draftId }
            return DraftWrite.Refused("task_draft_not_exists", 404, ZillitError.Http(404, "task_draft_not_exists"))
        }
        val now = savedDrafts.first { it.id == draftId }.copy(data = data, updatedMillis = 200)
        savedDrafts = savedDrafts.map { if (it.id == draftId) now else it }
        return DraftWrite.Saved(now, "task_draft_saved")
    }

    override suspend fun deleteDraft(draftId: String): ZillitResult<String?> {
        deletedDrafts += draftId
        savedDrafts = savedDrafts.filterNot { it.id == draftId }
        return ZillitResult.Success("task_draft_deleted")
    }

    override suspend fun addDraftAttachment(draftId: String, item: PreparedAttachment): DraftAttachmentWrite {
        draftAttachmentsAdded += draftId to item
        val now = savedDrafts.first { it.id == draftId }.let { saved ->
            saved.copy(
                attachments = saved.attachments + TaskAttachment(
                    id = "dratt-${draftAttachmentsAdded.size}", name = item.name, kind = item.kind,
                ),
            )
        }
        savedDrafts = savedDrafts.map { if (it.id == draftId) now else it }
        return DraftAttachmentWrite.Saved(now, null)
    }

    override suspend fun removeDraftAttachment(draftId: String, attachmentId: String): DraftAttachmentWrite {
        draftAttachmentsRemoved += draftId to attachmentId
        val now = savedDrafts.first { it.id == draftId }.let { saved ->
            saved.copy(attachments = saved.attachments.filterNot { it.id == attachmentId })
        }
        savedDrafts = savedDrafts.map { if (it.id == draftId) now else it }
        return DraftAttachmentWrite.Saved(now, null)
    }

    override suspend fun tasks(): ZillitResult<TasksRepository.TaskList> {
        listCalls += 1
        return ZillitResult.Success(TasksRepository.TaskList(held, features))
    }

    override suspend fun assignees() = ZillitResult.Success(TaskAssignees(setOf("u1"), emptySet()))

    /** What was attached, in order, and what the fake answers with. */
    val attached = mutableListOf<Pair<String, PreparedAttachment>>()
    val removedAttachments = mutableListOf<Pair<String, String>>()
    val linksAsked = mutableListOf<Triple<String, String, Boolean>>()
    var refuseAttachments = false
    var attachmentsOn: List<TaskAttachment> = emptyList()

    override suspend fun addAttachment(taskId: String, item: PreparedAttachment): AttachmentWrite {
        attached += taskId to item
        if (refuseAttachments) {
            return AttachmentWrite.Refused(
                step = AttachmentStep.Attach,
                message = "task_attachment_type",
                httpStatus = 422,
                error = ZillitError.Http(422, "task_attachment_type"),
            )
        }
        attachmentsOn = attachmentsOn + TaskAttachment(
            id = "att-${attached.size}",
            name = item.name,
            kind = item.kind,
            fileSizeBytes = item.sizeBytes,
            uploadedBy = "me",
        )
        return answerWith(taskId)
    }

    override suspend fun removeAttachment(taskId: String, attachmentId: String): AttachmentWrite {
        removedAttachments += taskId to attachmentId
        attachmentsOn = attachmentsOn.filterNot { it.id == attachmentId }
        return answerWith(taskId)
    }

    override suspend fun attachmentLink(taskId: String, attachmentId: String, download: Boolean): ZillitResult<String> {
        linksAsked += Triple(taskId, attachmentId, download)
        return ZillitResult.Success("https://storage.test/$attachmentId")
    }

    override suspend fun attachmentBytes(url: String): ByteArray? = ByteArray(4)

    /** Every attachment write answers with the task as the service now has it. */
    private fun answerWith(taskId: String): AttachmentWrite {
        val task = held.firstOrNull { it.id == taskId } ?: Task(id = taskId, title = "T")
        val fresh = task.copy(attachments = attachmentsOn)
        held = held.map { if (it.id == taskId) fresh else it }
        return AttachmentWrite.Saved(fresh, "task_attachment_added")
    }

    override suspend fun departments() = ZillitResult.Success(listOf(TaskDepartment("d1", "Art")))

    override suspend fun task(taskId: String): ZillitResult<Task> =
        held.firstOrNull { it.id == taskId }?.let { ZillitResult.Success(it.copy(comments = emptyList(), history = emptyList())) }
            ?: ZillitResult.Failure(ZillitError.Http(404, "task_not_exists"))

    val createFeatures = mutableListOf<TasksFeatures>()

    /** The `draft_id` each create carried (null when it came from no draft). */
    val createDraftIds = mutableListOf<String?>()
    var refuseCreateAsDraftGone = false

    override suspend fun create(
        draft: TaskDraft,
        uniqueId: String,
        quick: Boolean,
        features: TasksFeatures,
        draftId: String?,
    ): TaskWrite {
        createAttempts += uniqueId
        createFeatures += features
        createDraftIds += draftId
        if (quick) {
            quickCreates += 1
            quickVisibility += draft.visibility.takeIf { features.visibility }
        }
        if (refuseCreateAsDraftGone) {
            refuseCreateAsDraftGone = false
            return TaskWrite.Refused("task_draft_not_exists", 404, ZillitError.Http(404, "task_draft_not_exists"))
        }
        if (failNextCreate) {
            failNextCreate = false
            return TaskWrite.Refused("boom", 500, ZillitError.Http(500, "boom"))
        }
        // The service moves the draft's images and files onto the task and deletes the draft.
        draftId?.let { id -> savedDrafts = savedDrafts.filterNot { it.id == id } }
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
