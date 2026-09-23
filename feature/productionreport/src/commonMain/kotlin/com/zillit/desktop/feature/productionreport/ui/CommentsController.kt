package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportSyncEvent
import com.zillit.desktop.feature.productionreport.domain.canPostComments

/**
 * One report's comment thread — `CommentsModal.jsx`. Opening reads the
 * thread's COMMENT badge on the list it was opened from; another user's add,
 * edit or delete re-reads it quietly (ZL-21388/ZL-21389); a stale read never
 * overwrites a newer one.
 */
internal class CommentsController(private val ctx: ReportContext) {

    private var readSerial = 0L

    /**
     * Everyone who can see the row may read the thread; only its creator and
     * the project's comment recipients may post (`canPostComments`) — decided
     * here, whatever [readOnly] the screen asked for.
     */
    fun open(report: ReportSummary, readOnly: Boolean) {
        ctx.lists.readRowBadge(report.id, BadgeKind.Comment)
        val mayPost = canPostComments(report, ctx.state.me, ctx.state.isInternalReceiver)
        val closedNote = if (!readOnly && !report.status.locked && !mayPost) READ_ONLY_NOTE else LOCKED_NOTE
        ctx.update {
            copy(
                dialog = ReportDialog.Comments(
                    reportId = report.id,
                    reportName = report.name,
                    readOnly = readOnly || !mayPost,
                    closedNote = closedNote,
                ),
            )
        }
        read(report.id, quiet = false)
    }

    /** A closed thread drops any read still on its way. */
    fun close() {
        readSerial += 1
    }

    fun onSyncEvent(event: ReportSyncEvent) {
        val thread = ctx.state.dialog as? ReportDialog.Comments ?: return
        if (event.reportId != null && event.reportId != thread.reportId) return
        read(thread.reportId, quiet = true)
    }

    fun onEvent(event: DialogEvent) {
        val thread = ctx.state.dialog as? ReportDialog.Comments ?: return
        when (event) {
            is DialogEvent.EditCommentDraft -> update(thread.copy(draft = event.text))
            DialogEvent.SendComment -> send(thread)
            is DialogEvent.StartCommentEdit -> thread.comments.firstOrNull { it.id == event.commentId }
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

    private fun read(reportId: String, quiet: Boolean) {
        val serial = ++readSerial
        ctx.launchWork {
            val result = ctx.repository.comments(reportId)
            val thread = ctx.state.dialog as? ReportDialog.Comments
            if (serial != readSerial || thread?.reportId != reportId) return@launchWork
            when (result) {
                is ZillitResult.Success -> {
                    val next = if (sameThread(thread.comments, result.data)) thread.comments else result.data
                    update(thread.copy(comments = next, loading = false))
                }
                is ZillitResult.Failure -> {
                    update(thread.copy(loading = false))
                    if (!quiet) {
                        val reason = result.error.localised()
                        ctx.toast(str(S.desktop_could_not_load_comments_reason, reason), isError = true)
                    }
                }
            }
        }
    }

    private fun send(thread: ReportDialog.Comments) {
        val text = thread.draft.trim()
        if (text.isEmpty() || thread.sending || thread.readOnly) return
        update(thread.copy(sending = true))
        ctx.launchWork {
            val result = ctx.repository.addComment(thread.reportId, ctx.state.currentMember ?: selfAsMember(), text)
            val current = ctx.state.dialog as? ReportDialog.Comments ?: return@launchWork
            when (result) {
                is ZillitResult.Success -> {
                    val added = result.data
                    val comments = if (added == null || current.comments.any { it.id == added.id }) {
                        current.comments
                    } else {
                        current.comments + added
                    }
                    update(current.copy(comments = comments, draft = "", sending = false))
                    if (added == null) read(thread.reportId, quiet = true)
                }
                is ZillitResult.Failure -> {
                    update(current.copy(sending = false))
                    ctx.toast(str(S.desktop_could_not_send_comment_reason, result.error.localised()), isError = true)
                }
            }
        }
    }

    private fun saveEdit(thread: ReportDialog.Comments) {
        val id = thread.editingId ?: return
        val text = thread.editText.trim()
        if (text.isEmpty() || thread.savingEdit) return
        val original = thread.comments.firstOrNull { it.id == id }
        if (original?.text == text) {
            update(thread.copy(editingId = null, editText = ""))
            return
        }
        update(thread.copy(savingEdit = true))
        ctx.launchWork {
            val result = ctx.repository.editComment(thread.reportId, id, text)
            val current = ctx.state.dialog as? ReportDialog.Comments ?: return@launchWork
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
                    ctx.toast(str(S.desktop_could_not_save_comment_reason, result.error.localised()), isError = true)
                }
            }
        }
    }

    private fun delete(thread: ReportDialog.Comments) {
        val id = thread.confirmDeleteId ?: return
        if (thread.deletingId != null) return
        update(thread.copy(confirmDeleteId = null, deletingId = id))
        ctx.launchWork {
            val result = ctx.repository.deleteComment(thread.reportId, id)
            val current = ctx.state.dialog as? ReportDialog.Comments ?: return@launchWork
            when (result) {
                is ZillitResult.Success -> update(
                    current.copy(comments = current.comments.filterNot { it.id == id }, deletingId = null),
                )
                is ZillitResult.Failure -> {
                    update(current.copy(deletingId = null))
                    ctx.toast(str(S.desktop_could_not_delete_comment_reason, result.error.localised()), isError = true)
                }
            }
        }
    }

    /** The web's author fallback when the member list does not hold the viewer. */
    private fun selfAsMember() = com.zillit.desktop.feature.productionreport.domain.SheetMember(
        userId = ctx.state.me,
        fullName = ctx.viewer().displayName,
        designation = ctx.viewer().designation,
    )

    /** Same length and each id, text and edit time equal — keep the old list so the view does not jump. */
    private fun sameThread(before: List<ReportComment>, after: List<ReportComment>): Boolean =
        before.size == after.size && before.zip(after)
            .all { (a, b) -> a.id == b.id && a.text == b.text && a.updatedOn == b.updatedOn }

    private fun update(thread: ReportDialog.Comments) = ctx.update {
        if ((dialog as? ReportDialog.Comments)?.reportId == thread.reportId) copy(dialog = thread) else this
    }

    private companion object {
        val LOCKED_NOTE: String get() = str(S.desktop_pr_comments_closed_note)
        val READ_ONLY_NOTE: String get() = str(S.desktop_pr_comments_read_only_note)
    }
}
