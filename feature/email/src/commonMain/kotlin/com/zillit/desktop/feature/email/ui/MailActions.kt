package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.SELECTION_LIMIT

/**
 * Ticking rows and acting on them: trash, destroy, move, empty Trash — and
 * the same verbs on the open conversation from the reading pane's toolbar.
 *
 * Split out of [EmailViewModel] for its size; the rules are the web's
 * (`useEmailSelection`, `DeleteConfirmModal`, `handleMoveEmails`): at most
 * [SELECTION_LIMIT] rows, every destructive step confirmed first, and — with
 * conversation view on — a ticked row standing for every message of its
 * conversation that this machine holds.
 */
internal class MailActions(private val host: EmailViewModel) {

    private val state get() = host.currentState

    @Suppress("CyclomaticComplexMethod") // One branch per action; every one delegates.
    fun onSelection(event: EmailEvent.Selection) {
        when (event) {
            is EmailEvent.ToggleSelection -> toggle(event.rowId)
            is EmailEvent.SelectRows -> selectRows(event.which)
            EmailEvent.ClearSelection -> host.update { copy(selectedIds = emptySet()) }
            EmailEvent.DeleteSelected -> askDelete(state.selectedIds)
            EmailEvent.DeleteOpen -> state.openRowId?.let { askDelete(setOf(it)) }
            is EmailEvent.DeleteOne -> host.update { copy(pendingConfirm = PendingConfirm.DeleteOne(event.message)) }
            is EmailEvent.MoveSelected -> move(state.selectedIds, event.folderName)
            is EmailEvent.MoveOpen -> state.openRowId?.let { move(setOf(it), event.folderName) }
            is EmailEvent.DropOnFolder -> move(setOf(event.rowId), event.folderName)
            EmailEvent.EmptyTrash -> host.update { copy(pendingConfirm = PendingConfirm.EmptyTrash) }
            EmailEvent.ConfirmPending -> runPending()
            EmailEvent.DismissConfirm -> host.update { copy(pendingConfirm = null) }
            EmailEvent.DismissInfo -> host.update { copy(info = null) }
        }
    }

    private fun toggle(rowId: String) {
        val current = state.selectedIds
        if (rowId in current) {
            host.update { copy(selectedIds = selectedIds - rowId) }
        } else if (current.size >= SELECTION_LIMIT) {
            host.update { copy(info = MailInfo.SelectionLimit) }
        } else {
            host.update { copy(selectedIds = selectedIds + rowId) }
        }
    }

    /** The select-all menu; more than the cap ticks the first 30 and says so, as the web does. */
    private fun selectRows(which: SelectionChoice) {
        val rows = state.rows
        val chosen = when (which) {
            SelectionChoice.All -> rows
            SelectionChoice.None -> emptyList()
            SelectionChoice.Read -> rows.filter { !it.hasUnread }
            SelectionChoice.Unread -> rows.filter { it.hasUnread }
        }.map { it.id }
        val capped = chosen.size > SELECTION_LIMIT
        host.update {
            copy(
                selectedIds = chosen.take(SELECTION_LIMIT).toSet(),
                info = if (capped) MailInfo.SelectionLimit else info,
            )
        }
    }

    /**
     * Trashing asks, as the web does; destroying — only ever from Trash — and
     * deleting drafts ask with their own words.
     */
    private fun askDelete(ids: Set<String>) {
        if (ids.isEmpty()) return
        val pending = when {
            state.isViewingDrafts -> PendingConfirm.DeleteDrafts(ids.toList())
            state.isViewingTrash -> PendingConfirm.Destroy(host.selectionTargets(ids).map { it.id })
            else -> PendingConfirm.TrashSelected(ids.size)
        }
        host.update { copy(pendingConfirm = pending, pendingIds = ids) }
    }

    private fun runPending() {
        val pending = state.pendingConfirm ?: return
        val ids = state.pendingIds
        host.update { copy(pendingConfirm = null, pendingIds = emptySet()) }
        when (pending) {
            is PendingConfirm.TrashSelected -> trash(host.selectionTargets(ids))
            is PendingConfirm.Destroy -> destroy(pending.messageIds)
            is PendingConfirm.DeleteDrafts -> deleteDrafts(pending.draftIds)
            is PendingConfirm.DeleteOne -> deleteOne(pending.message)
            PendingConfirm.EmptyTrash -> emptyTrash()
            is PendingConfirm.DeleteFolder -> host.deleteFolder(pending.folder)
        }
    }

    /** Moves to Trash, one call per source folder: a selection can span folders. */
    private fun trash(targets: List<EmailSummary>) {
        if (targets.isEmpty()) return
        val byFolder = targets.groupBy { it.folderName.ifBlank { state.selectedFolder?.name.orEmpty() } }
        host.forgetOpen(targets.map { it.id })
        host.runOnMailbox(
            block = { moveAll(byFolder, EmailFolder.TRASH) },
            done = if (targets.size == 1) "Email moved to Trash" else "${targets.size} emails moved to Trash",
            folders = (byFolder.keys + EmailFolder.TRASH).toList(),
        )
    }

    private fun destroy(ids: List<String>) {
        if (ids.isEmpty()) return
        host.forgetOpen(ids)
        host.runOnMailbox(
            block = { host.mail.deletePermanently(ids) },
            done = if (ids.size == 1) "Email deleted" else "${ids.size} emails deleted",
            folders = listOf(EmailFolder.TRASH),
        )
    }

    private fun deleteDrafts(ids: List<String>) {
        if (ids.isEmpty()) return
        host.forgetOpen(ids)
        host.update { copy(drafts = drafts.filterNot { it.id in ids }) }
        host.runOnMailbox(
            block = { host.drafts.deleteDrafts(ids) },
            done = if (ids.size == 1) "Draft deleted" else "${ids.size} drafts deleted",
        )
        host.reloadDrafts()
    }

    /** One message of the open conversation: trashed, or destroyed when it is already in Trash. */
    private fun deleteOne(message: EmailMessage) {
        val folder = message.folderName.ifBlank { state.selectedFolder?.name.orEmpty() }
        val inTrash = folder.equals(EmailFolder.TRASH, ignoreCase = true)
        host.forgetOpen(listOf(message.id))
        host.runOnMailbox(
            block = {
                if (inTrash) host.mail.deletePermanently(listOf(message.id))
                else host.mail.move(listOf(message.id), folder, EmailFolder.TRASH)
            },
            done = if (inTrash) "Email deleted" else "Email moved to Trash",
            folders = listOf(folder, EmailFolder.TRASH),
        )
    }

    private fun emptyTrash() {
        host.forgetOpen(state.messages.map { it.id })
        host.runOnMailbox(
            block = { host.mail.emptyTrash() },
            done = "Trash emptied",
            folders = listOf(EmailFolder.TRASH),
        )
    }

    /** Moves ticked rows — every message of their conversations — into [target]. */
    private fun move(ids: Set<String>, target: String) {
        val targets = host.selectionTargets(ids)
        if (targets.isEmpty() || state.isViewingDrafts) return
        val byFolder = targets
            .filterNot { it.folderName.equals(target, ignoreCase = true) }
            .groupBy { it.folderName.ifBlank { state.selectedFolder?.name.orEmpty() } }
        if (byFolder.isEmpty()) return
        host.forgetOpen(byFolder.values.flatten().map { it.id })
        host.update { copy(selectedIds = emptySet()) }
        val label = state.folders.firstOrNull { it.name == target }?.displayName ?: target
        host.runOnMailbox(
            block = { moveAll(byFolder, target) },
            done = "Moved to $label",
            folders = (byFolder.keys + target).toList(),
        )
    }

    private suspend fun moveAll(byFolder: Map<String, List<EmailSummary>>, target: String): ZillitResult<Unit> {
        var failure: ZillitResult.Failure? = null
        byFolder.forEach { (source, rows) ->
            val result = host.mail.move(rows.map { it.id }, source, target)
            if (result is ZillitResult.Failure) failure = result
        }
        return failure ?: ZillitResult.Success(Unit)
    }
}
