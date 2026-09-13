package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.feature.productionreport.domain.SheetMember

/**
 * The "Select Recipients for Comments" picker — `SendForApprovalModal.jsx`.
 *
 * Offers every accepted member except the sender; pre-selects the project's
 * comment receivers (the editor's own list first); asks before removing
 * anyone who received comments last time. The sender is always added to the
 * list sent, so writing the receivers can never drop them.
 */
internal class RecipientPicker(private val ctx: ReportContext) {

    fun open(reportId: String?, fromEditor: Boolean) {
        val state = ctx.state
        if (!state.isPoster) return
        val editorReceivers = state.editor?.document?.shared?.internalReceiverIds.orEmpty()
        val source = editorReceivers.takeIf { fromEditor && it.isNotEmpty() } ?: state.metadata.internalReceiverIds
        val selectable = selectableIds()
        val initial = source.filter { it != state.me && it in selectable }.toSet()
        ctx.update {
            copy(
                dialog = ReportDialog.SendPicker(
                    reportId = reportId,
                    fromEditor = fromEditor,
                    choosing = fromEditor,
                    selected = initial,
                    initial = initial,
                ),
            )
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per picker event.
    fun onEvent(event: WorkflowEvent, onFinalFromEditor: () -> Unit, onFinish: (revokeAccess: Boolean) -> Unit) {
        val picker = ctx.state.dialog as? ReportDialog.SendPicker ?: return
        when (event) {
            WorkflowEvent.ChooseComments -> update(picker.copy(choosing = false))
            WorkflowEvent.BackToChooser -> update(picker.copy(choosing = true, search = ""))
            WorkflowEvent.ChooseSignature -> {
                ctx.update { copy(dialog = null) }
                onFinalFromEditor()
            }
            is WorkflowEvent.SearchRecipients -> update(picker.copy(search = event.query))
            is WorkflowEvent.ToggleRecipient -> update(
                picker.copy(
                    selected = picker.selected.let { if (event.userId in it) it - event.userId else it + event.userId },
                ),
            )
            WorkflowEvent.ToggleAllRecipients -> {
                val all = selectableIds()
                update(picker.copy(selected = if (picker.selected.containsAll(all)) emptySet() else all))
            }
            WorkflowEvent.SendRecipients -> {
                if (picker.selected.isEmpty()) return
                val removed = (picker.initial - picker.selected).mapNotNull { ctx.state.member(it) }
                if (removed.isNotEmpty()) {
                    update(picker.copy(pendingRemoval = removed))
                } else {
                    onFinish(false)
                }
            }
            WorkflowEvent.CancelRemoval -> update(picker.copy(pendingRemoval = null))
            else -> Unit
        }
    }

    /** The ids sent: every ticked recipient, plus the sender. */
    fun payloadIds(picker: ReportDialog.SendPicker): List<String> {
        val me = ctx.state.me
        return (picker.selected.filter { it != me } + listOfNotNull(me.takeIf { it.isNotBlank() })).distinct()
    }

    /** Accepted members except the sender, in crew order. */
    fun selectable(): List<SheetMember> =
        ctx.state.members.filter { it.isAccepted && it.userId != ctx.state.me && it.userId.isNotBlank() }

    private fun selectableIds(): Set<String> = selectable().map { it.userId }.toSet()

    private fun update(picker: ReportDialog.SendPicker) = ctx.update { copy(dialog = picker) }
}
