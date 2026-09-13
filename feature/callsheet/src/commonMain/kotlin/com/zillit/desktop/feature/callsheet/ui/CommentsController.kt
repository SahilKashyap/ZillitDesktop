package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetSyncEvent
import com.zillit.desktop.feature.callsheet.domain.normalised

/**
 * One sheet's comment thread beside its read-only preview — `CommentsModal.jsx`.
 *
 * Opening reads the thread's unread badge (only when it has one); another
 * user's add, edit or delete lands live; a stale read never overwrites a newer
 * one. Divergences, each fixing a web bug: the draft and an open edit never
 * leak into another sheet's thread (the dialog state is per opening); a failed
 * preview says so instead of "Loading preview…" forever; failures toast
 * instead of vanishing.
 */
internal class CommentsController(private val ctx: SheetContext) {

    private var readSerial = 0L

    fun open(sheet: CallSheetSummary, readOnly: Boolean) {
        if (ctx.state.unreadComments(sheet.id) > 0) ctx.services.badges.readCommentThread(sheet.id)
        ctx.update {
            copy(dialog = SheetDialog.Comments(sheetId = sheet.id, sheetName = sheet.name, readOnly = readOnly))
        }
        loadPreview(sheet.id)
        read(sheet.id, quiet = false)
    }

    /** A closed thread drops any read still on its way. */
    fun close() {
        readSerial += 1
    }

    /** `callsheet:comment:created|updated|deleted` for the open thread. */
    fun onSyncEvent(event: SheetSyncEvent) {
        val thread = ctx.state.dialog as? SheetDialog.Comments ?: return
        if (event.sheetId != null && event.sheetId != thread.sheetId) return
        val comment = event.comment
        when {
            event.name.endsWith(":deleted") -> {
                val id = event.commentId ?: comment?.id ?: return read(thread.sheetId, quiet = true)
                update(thread.copy(comments = thread.comments.filterNot { it.id == id }))
            }
            comment == null -> read(thread.sheetId, quiet = true)
            event.name.endsWith(":updated") -> update(
                thread.copy(comments = thread.comments.map { if (it.id == comment.id) comment else it }),
            )
            thread.comments.none { it.id == comment.id } -> update(thread.copy(comments = thread.comments + comment))
        }
    }

    fun onEvent(event: DialogEvent) {
        val thread = ctx.state.dialog as? SheetDialog.Comments ?: return
        when (event) {
            is DialogEvent.EditCommentDraft -> update(thread.copy(draft = event.text))
            DialogEvent.SendComment -> send(thread)
            is DialogEvent.StartCommentEdit -> thread.comments.firstOrNull { it.id == event.commentId }
                ?.takeIf { !thread.readOnly && it.authorId == ctx.state.me }
                ?.let { comment -> update(thread.copy(editingId = comment.id, editText = comment.text)) }
            is DialogEvent.EditCommentText -> update(thread.copy(editText = event.text))
            DialogEvent.SaveCommentEdit -> saveEdit(thread)
            DialogEvent.CancelCommentEdit -> update(thread.copy(editingId = null, editText = ""))
            is DialogEvent.AskDeleteComment -> update(thread.copy(confirmDeleteId = event.commentId))
            DialogEvent.CancelDeleteComment -> update(thread.copy(confirmDeleteId = null))
            DialogEvent.ConfirmDeleteComment -> delete(thread)
            else -> Unit
        }
    }

    /** The preview needs the cells — list rows carry the header only. */
    private fun loadPreview(sheetId: String) {
        ctx.launchWork {
            val result = ctx.repository.sheet(sheetId)
            val thread = ctx.state.dialog as? SheetDialog.Comments
            if (thread?.sheetId != sheetId) return@launchWork
            when (result) {
                is ZillitResult.Success -> update(thread.copy(preview = result.data.payload.normalised()))
                is ZillitResult.Failure -> update(thread.copy(previewFailed = true))
            }
        }
    }

    private fun read(sheetId: String, quiet: Boolean) {
        val serial = ++readSerial
        ctx.launchWork {
            val result = ctx.repository.comments(sheetId)
            val thread = ctx.state.dialog as? SheetDialog.Comments
            if (serial != readSerial || thread?.sheetId != sheetId) return@launchWork
            when (result) {
                is ZillitResult.Success -> {
                    val sorted = result.data.sortedBy { it.createdOn ?: Long.MAX_VALUE }
                    val next = if (sameThread(thread.comments, sorted)) thread.comments else sorted
                    update(thread.copy(comments = next, loading = false))
                }
                is ZillitResult.Failure -> {
                    update(thread.copy(loading = false))
                    if (!quiet) ctx.toast("Couldn't load comments: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun send(thread: SheetDialog.Comments) {
        val text = thread.draft.trim()
        if (text.isEmpty() || thread.sending || thread.readOnly) return
        update(thread.copy(sending = true))
        ctx.launchWork {
            val result = ctx.repository.addComment(
                thread.sheetId,
                ctx.state.currentMember ?: selfAsMember(),
                ctx.viewer().displayName,
                text,
            )
            val current = ctx.state.dialog as? SheetDialog.Comments ?: return@launchWork
            if (current.sheetId != thread.sheetId) return@launchWork
            when (result) {
                is ZillitResult.Success -> {
                    val added = result.data
                    val comments = if (added == null || current.comments.any { it.id == added.id }) {
                        current.comments
                    } else {
                        current.comments + added
                    }
                    update(current.copy(comments = comments, draft = "", sending = false))
                    if (added == null) read(thread.sheetId, quiet = true)
                }
                is ZillitResult.Failure -> {
                    update(current.copy(sending = false))
                    ctx.toast("Couldn't send the comment: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun saveEdit(thread: SheetDialog.Comments) {
        val id = thread.editingId ?: return
        val text = thread.editText.trim()
        if (text.isEmpty() || thread.savingEdit) return
        val original = thread.comments.firstOrNull { it.id == id }
        if (original?.text?.trim() == text) {
            update(thread.copy(editingId = null, editText = ""))
            return
        }
        update(thread.copy(savingEdit = true))
        ctx.launchWork {
            val result = ctx.repository.editComment(thread.sheetId, id, text)
            val current = ctx.state.dialog as? SheetDialog.Comments ?: return@launchWork
            if (current.sheetId != thread.sheetId) return@launchWork
            when (result) {
                is ZillitResult.Success -> update(
                    current.copy(
                        comments = current.comments.map { comment ->
                            if (comment.id == id) result.data ?: comment.copy(text = text) else comment
                        },
                        editingId = null,
                        editText = "",
                        savingEdit = false,
                        editedIds = current.editedIds + id,
                    ),
                )
                is ZillitResult.Failure -> {
                    update(current.copy(savingEdit = false))
                    ctx.toast("Couldn't save the comment: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun delete(thread: SheetDialog.Comments) {
        val id = thread.confirmDeleteId ?: return
        if (thread.deletingId != null) return
        update(thread.copy(confirmDeleteId = null, deletingId = id))
        ctx.launchWork {
            val result = ctx.repository.deleteComment(thread.sheetId, id)
            val current = ctx.state.dialog as? SheetDialog.Comments ?: return@launchWork
            if (current.sheetId != thread.sheetId) return@launchWork
            when (result) {
                is ZillitResult.Success -> update(
                    current.copy(comments = current.comments.filterNot { it.id == id }, deletingId = null),
                )
                is ZillitResult.Failure -> {
                    update(current.copy(deletingId = null))
                    ctx.toast("Couldn't delete the comment: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    /** The web's author fallback when the crew list does not hold the viewer. */
    private fun selfAsMember(): SheetMember? = ctx.state.me.takeIf { it.isNotBlank() }?.let { me ->
        SheetMember(userId = me, fullName = ctx.viewer().displayName, designation = ctx.viewer().designation)
    }

    /** Same length and each id, text and edit time equal — keep the old list so the view does not jump. */
    private fun sameThread(before: List<SheetComment>, after: List<SheetComment>): Boolean =
        before.size == after.size && before.zip(after)
            .all { (a, b) -> a.id == b.id && a.text == b.text && a.updatedOn == b.updatedOn }

    private fun update(thread: SheetDialog.Comments) = ctx.update {
        if ((dialog as? SheetDialog.Comments)?.sheetId == thread.sheetId) copy(dialog = thread) else this
    }
}
