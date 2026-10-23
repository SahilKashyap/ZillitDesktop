package com.zillit.desktop.feature.tasks.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.AttachmentKind
import com.zillit.desktop.feature.tasks.domain.AttachmentStep
import com.zillit.desktop.feature.tasks.domain.AttachmentWrite
import com.zillit.desktop.feature.tasks.domain.MAX_ATTACHMENTS
import com.zillit.desktop.feature.tasks.domain.PickedTaskFile
import com.zillit.desktop.feature.tasks.domain.PrepareOutcome
import com.zillit.desktop.feature.tasks.domain.PreparedAttachment
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.message
import com.zillit.desktop.feature.tasks.domain.prepareAttachment
import com.zillit.desktop.feature.tasks.domain.roomForAttachments
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The images and files of the task the panel has open — the web's
 * `useAttachments`.
 *
 * ## They save on their own, never with Save changes
 *
 * On a task that exists each one goes up the moment it is added, one after
 * another, with an "Uploading…" tile meanwhile. On a **new** task they wait
 * ([TaskPanel.pending], "Saved on create") and go up one by one once Create
 * has succeeded, while the button still says "Creating…". A failure is said
 * and the rest carry on.
 *
 * ## The checks happen here first
 *
 * The type, the size and the limit of 20 are the service's rules, checked
 * before anything is sent so the user hears about a 12 MB file at once (see
 * `domain/TaskAttachments`). The server checks all of it again, and **its**
 * refusals are its own messages; only storage refusing the bytes has no
 * message of its own, which is why that one sentence is the client's.
 *
 * Its own class rather than more of [TasksViewModel]: this is a small flow
 * with a queue and three calls per item, and that class is already the list,
 * the panel and every write.
 */
internal class TaskAttachmentsWork(
    private val vm: TasksViewModel,
    private val repository: TasksRepository,
    private val pickFiles: suspend (imagesOnly: Boolean) -> List<PickedTaskFile>,
) {

    private var keys = 0L

    /**
     * Images whose bytes are being fetched.
     *
     * Marked before the fetch starts, not after it lands: a thumbnail asks on
     * every composition, and the state cannot say "already asked" until the
     * first answer arrives — which would be one request per recomposition.
     */
    private val asking = mutableSetOf<String>()

    /** "Add image" / "Attach file" — the host's dialog, then the same path as a drop. */
    fun pick(imagesOnly: Boolean) {
        if (!canAdd()) return
        vm.work {
            val picked = pickFiles(imagesOnly)
            if (picked.isNotEmpty()) add(picked)
        }
    }

    /**
     * Files chosen, pasted or dropped.
     *
     * Prepared one at a time (an image is decoded, shrunk and re-encoded,
     * which is not free), and only as many as there is room for: the ones
     * past the limit are not prepared at all, and the limit is said once.
     */
    fun add(files: List<PickedTaskFile>) {
        if (files.isEmpty() || !canAdd()) return
        val open = vm.currentState.panel ?: return
        // A new task made from a saved draft already holds the draft's own:
        // they count towards the limit of 20 just as the task's do.
        val room = roomForAttachments(
            open.attachments.size + open.keptDraftFiles.size,
            open.pending.size,
            open.uploading,
        )
        if (room <= 0) {
            vm.warn(str(S.desktop_tasks_attachment_limit, MAX_ATTACHMENTS))
            return
        }
        vm.work { prepareAndSend(files, room, open.key) }
    }

    private suspend fun prepareAndSend(files: List<PickedTaskFile>, room: Int, panelKey: Long) {
        val prepared = mutableListOf<PreparedAttachment>()
        var next = 0
        while (next < files.size && prepared.size < room) {
            val file = files[next]
            next += 1
            when (val outcome = prepare(file)) {
                is PrepareOutcome.Ready -> prepared += outcome.item
                // Said per file: three files refused in one drop are three
                // different sentences, and the last must not hide the others.
                is PrepareOutcome.Refused -> vm.warn(outcome.why.message())
            }
        }
        if (next < files.size) vm.warn(str(S.desktop_tasks_attachment_limit, MAX_ATTACHMENTS))
        if (prepared.isEmpty()) return
        val open = vm.currentState.panel
        if (open == null || open.key != panelKey) return
        val taskId = open.task?.id
        if (taskId == null || open.creating) {
            queue(prepared, panelKey)
            return
        }
        upload(prepared, taskId, panelKey)
    }

    private fun prepare(file: PickedTaskFile): PrepareOutcome = prepareAttachment(
        name = file.name,
        mimeType = file.mimeType,
        bytes = file.bytes,
        key = "att-${++keys}",
        // A screenshot arrives with no name of its own; the web names it the
        // same way, with the time it was pasted.
        pastedName = str(S.desktop_tasks_pasted_image, pastedStamp()),
    )

    /** A new task: they wait under the box until Create. */
    private fun queue(prepared: List<PreparedAttachment>, panelKey: Long) {
        vm.panelEdit { if (key == panelKey) copy(pending = pending + prepared) else this }
        vm.warn(
            when {
                prepared.size > 1 -> str(S.desktop_tasks_attachment_pending_many, prepared.size)
                prepared.single().kind == AttachmentKind.Image -> str(S.desktop_tasks_attachment_pending_image)
                else -> str(S.desktop_tasks_attachment_pending_file)
            },
        )
    }

    /**
     * One after another, as the web does it: each is three calls, and a
     * service that refuses the second file must not have had the third sent
     * at it in parallel.
     */
    private suspend fun upload(items: List<PreparedAttachment>, taskId: String, panelKey: Long) {
        vm.panelEdit { if (key == panelKey) copy(uploading = uploading + items.size) else this }
        var saved: AttachmentWrite.Saved? = null
        items.forEach { item ->
            val answer = repository.addAttachment(taskId, item)
            vm.panelEdit {
                if (key == panelKey) copy(uploading = (uploading - 1).coerceAtLeast(0)) else this
            }
            when (answer) {
                is AttachmentWrite.Saved -> {
                    saved = answer
                    vm.absorbAttachments(answer.task)
                }

                is AttachmentWrite.Refused -> refused(answer)
            }
        }
        // One word from the server for the lot, not one per file.
        saved?.let { vm.announceMessage(it.message) }
    }

    /**
     * What waited for Create, to the task just made.
     *
     * Awaited by the create itself, so the panel cannot close over an upload
     * in flight and the button goes on saying "Creating…" meanwhile. A
     * failure is said and the rest carry on.
     */
    suspend fun sendQueued(taskId: String, panelKey: Long) {
        val waiting = vm.currentState.panel?.takeIf { it.key == panelKey }?.pending.orEmpty()
        if (waiting.isEmpty()) return
        vm.panelEdit { if (key == panelKey) copy(pending = emptyList()) else this }
        waiting.forEach { item ->
            when (val answer = repository.addAttachment(taskId, item)) {
                is AttachmentWrite.Saved -> vm.absorbAttachments(answer.task)
                is AttachmentWrite.Refused -> refused(answer)
            }
        }
    }

    fun removePending(key: String) =
        vm.panelEdit { copy(pending = pending.filterNot { it.key == key }) }

    /** Removes one the task has. The panel has already asked. */
    fun remove(attachment: TaskAttachment) {
        val open = vm.currentState.panel ?: return
        val taskId = open.task?.id ?: return
        vm.work {
            when (val answer = repository.removeAttachment(taskId, attachment.id)) {
                is AttachmentWrite.Saved -> {
                    vm.absorbAttachments(answer.task)
                    vm.announceMessage(answer.message)
                }

                is AttachmentWrite.Refused -> {
                    // Already gone — someone else removed it, or the task:
                    // read the task again rather than leave a row that is not there.
                    if (answer.httpStatus == NOT_FOUND) vm.rereadOpenTask()
                    refused(answer)
                }
            }
        }
    }

    /**
     * The bytes behind one image, for its thumbnail and the full-size view.
     *
     * The link in the single-task answer is tried first; when it has run out
     * a fresh one is asked for, once. Only then does the row say "Couldn't
     * load" — and it is marked failed so nothing asks again on every
     * recomposition.
     */
    fun load(attachment: TaskAttachment) {
        val open = vm.currentState.panel ?: return
        if (attachment.id in open.imageBytes || attachment.id in open.imagesFailed) return
        if (!asking.add(attachment.id)) return
        fetch(attachment, open.key, renew = attachment.url.isBlank())
    }

    /** A thumbnail that failed to load: ask for a fresh link and try once more. */
    fun reload(attachment: TaskAttachment) {
        val open = vm.currentState.panel ?: return
        if (!asking.add(attachment.id)) return
        vm.panelEdit { copy(imagesFailed = imagesFailed - attachment.id) }
        fetch(attachment, open.key, renew = true)
    }

    private fun fetch(attachment: TaskAttachment, panelKey: Long, renew: Boolean) {
        vm.work {
            val taskId = vm.currentState.panel?.task?.id
            val url = if (renew && taskId != null) freshLink(taskId, attachment.id) else attachment.url
            val bytes = url?.takeIf { it.isNotBlank() }?.let { repository.attachmentBytes(it) }
            asking -= attachment.id
            vm.panelEdit {
                when {
                    key != panelKey -> this
                    bytes != null -> copy(imageBytes = imageBytes + (attachment.id to bytes))
                    else -> copy(imagesFailed = imagesFailed + attachment.id)
                }
            }
        }
    }

    private suspend fun freshLink(taskId: String, attachmentId: String): String? =
        (repository.attachmentLink(taskId, attachmentId) as? ZillitResult.Success)?.data

    /** A link that saves the file under its own name; the panel opens it. */
    fun download(attachment: TaskAttachment) {
        val open = vm.currentState.panel ?: return
        val taskId = open.task?.id ?: return
        vm.work {
            when (val answer = repository.attachmentLink(taskId, attachment.id, download = true)) {
                is ZillitResult.Success -> vm.panelEdit {
                    if (key == open.key) copy(openUrl = answer.data) else this
                }

                is ZillitResult.Failure -> vm.failWith(answer.error)
            }
        }
    }

    /**
     * Why one did not go up.
     *
     * Storage refusing the bytes has no envelope and no message, so that one
     * is the client's own sentence (carried on the refusal); anything the
     * service said is the service's.
     */
    private fun refused(answer: AttachmentWrite.Refused) {
        if (answer.step == AttachmentStep.Upload) vm.warn(answer.error.localised()) else vm.failWith(answer.error)
    }

    /** Posting rights and a service that can keep them; the panel hides the controls too. */
    private fun canAdd(): Boolean {
        val state = vm.currentState
        return state.canPost && state.features.attachments && state.panel != null
    }

    /** "12 Oct, 14:05" — what the web puts in a pasted image's name. */
    private fun pastedStamp(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val month = MONTHS.getOrElse(now.month.ordinal) { "" }
        return "${now.dayOfMonth} $month, ${now.hour.pad()}:${now.minute.pad()}"
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private companion object {
        const val NOT_FOUND = 404
        val MONTHS = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
    }
}
