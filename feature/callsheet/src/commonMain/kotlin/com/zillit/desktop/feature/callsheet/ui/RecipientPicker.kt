package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.feature.callsheet.domain.SheetMember

/**
 * "Send for Comments" — `SendForCommentsModal.jsx`.
 *
 * The recipient list is project-wide (`internal_distribution_receivers`). The
 * sender is never listed but always sent, so writing the receivers can never
 * drop them; unticking someone who received comments last time asks once
 * whether their viewing access goes too.
 */
internal class RecipientPicker(private val ctx: SheetContext) {

    fun open(sheetId: String?, fromEditor: Boolean) {
        val state = ctx.state
        if (!state.isPoster) return
        val editorReceivers = state.editor?.document?.shared?.internalReceiverIds.orEmpty()
        val source = editorReceivers.takeIf { fromEditor && it.isNotEmpty() } ?: state.metadata.internalReceiverIds
        val selectable = selectableIds()
        val initial = source.filter { it != state.me && it in selectable }.toSet()
        ctx.update {
            copy(
                dialog = SheetDialog.SendPicker(
                    sheetId = sheetId,
                    fromEditor = fromEditor,
                    selected = initial,
                    initial = initial,
                ),
            )
        }
    }

    fun onEvent(event: WorkflowEvent, onFinish: (revokeAccess: Boolean) -> Unit) {
        val picker = ctx.state.dialog as? SheetDialog.SendPicker ?: return
        when (event) {
            is WorkflowEvent.SearchRecipients -> update(picker.copy(search = event.query))
            is WorkflowEvent.ToggleRecipient -> update(
                picker.copy(
                    selected = picker.selected.let { if (event.userId in it) it - event.userId else it + event.userId },
                ),
            )
            WorkflowEvent.ToggleAllRecipients -> {
                val all = selectableIds()
                update(picker.copy(selected = if (picker.selected.size == all.size) emptySet() else all))
            }
            WorkflowEvent.SendRecipients -> {
                val removed = removed(picker)
                if (picker.selected.isEmpty() && removed.isEmpty()) return
                if (removed.isNotEmpty()) update(picker.copy(pendingRemoval = removed)) else onFinish(false)
            }
            WorkflowEvent.CancelRemoval -> update(picker.copy(pendingRemoval = null))
            else -> Unit
        }
    }

    /** Previously selected people no longer ticked — whatever the search shows. */
    fun removed(picker: SheetDialog.SendPicker): List<SheetMember> =
        selectable().filter { it.userId in picker.initial && it.userId !in picker.selected }

    /** The ids sent: every ticked recipient, plus the sender. */
    fun payloadIds(picker: SheetDialog.SendPicker): List<String> {
        val me = ctx.state.me
        return (picker.selected.filter { it != me } + listOfNotNull(me.takeIf { it.isNotBlank() })).distinct()
    }

    /** Accepted members except the sender, in crew order. */
    fun selectable(): List<SheetMember> =
        ctx.state.members.filter { it.isAccepted && it.userId != ctx.state.me && it.userId.isNotBlank() }

    private fun selectableIds(): Set<String> = selectable().map { it.userId }.toSet()

    private fun update(picker: SheetDialog.SendPicker) = ctx.update { copy(dialog = picker) }
}
