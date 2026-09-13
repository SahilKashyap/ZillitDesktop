package com.zillit.desktop.feature.dealmemo.ui.preview

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.preview.EditAction
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalCoding
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.NominalsEvent

/**
 * "Update Nominals" (`UpdateNominalsModal.jsx`): re-code a saved deal's pay
 * lines. No lifecycle change and nothing for the crew to acknowledge.
 */
internal class NominalsActions(private val vm: DealMemoViewModel, private val page: DealPreviewActions) {

    fun open() {
        val deal = vm.ui.preview?.deal ?: return
        val form = NominalCoding.hydrate(deal)
        page.updatePreview { copy(nominals = NominalsEditorState(form = form, baseline = form.signature)) }
        vm.ensureCoa()
    }

    fun onEvent(event: NominalsEvent) {
        val editor = vm.ui.preview?.nominals ?: return
        when (event) {
            is NominalsEvent.Code -> update { copy(form = form.withValue(event.row, event.code)) }
            NominalsEvent.Save -> save()
            // Escape and Cancel both come here: nothing while saving, a question while dirty.
            NominalsEvent.Cancel -> when {
                editor.saving -> Unit
                editor.dirty -> update { copy(confirmLeave = true) }
                else -> close()
            }
            NominalsEvent.SaveAndLeave -> {
                update { copy(confirmLeave = false) }
                save()
            }
            NominalsEvent.Discard -> close()
            NominalsEvent.StayEditing -> update { copy(confirmLeave = false) }
        }
    }

    private fun save() {
        val preview = vm.ui.preview ?: return
        val deal = preview.deal ?: return
        val editor = preview.nominals ?: return
        if (editor.saving || !page.allows { EditAction.Nominals in editControl.actions }) return
        update { copy(saving = true) }
        vm.work {
            when (val result = vm.repository.updateNominalCodes(deal.id, NominalCoding.payload(editor.form, deal))) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "nominal_codes_updated")
                    page.refreshNow()
                    close()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "failed_to_update_nominal_codes")
                    update { copy(saving = false) }
                }
            }
        }
    }

    private fun close() = page.updatePreview { copy(nominals = null) }

    private fun update(reducer: NominalsEditorState.() -> NominalsEditorState) =
        page.updatePreview { copy(nominals = nominals?.reducer()) }
}
