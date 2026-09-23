package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryExports
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfLayout
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The toolbar's panels — `ScheduleTypeManager`, `ActivityLogDrawer`,
 * `PresetListModal`, the PDF options prompt, Print Selected and
 * `ShareScheduleModal`.
 */
@Suppress("TooManyFunctions") // One handler per act across six small panels.
internal class PanelActions(private val vm: BoxScheduleViewModel) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per control.
    fun onEvent(event: PanelEvent) {
        when (event) {
            PanelEvent.OpenTypes -> if (vm.mayEdit()) vm.updateOverlays { copy(types = TypesManager()) }
            PanelEvent.CloseTypes -> vm.updateOverlays { copy(types = null) }
            is PanelEvent.RecolorType -> recolorType(event.typeId, event.color)
            is PanelEvent.StartTypeEdit -> startTypeEdit(event.typeId)
            is PanelEvent.SetEditTitle -> types { copy(editTitle = event.text) }
            is PanelEvent.SetEditColor -> types { copy(editColor = event.color) }
            PanelEvent.SaveTypeEdit -> saveTypeEdit()
            PanelEvent.CancelTypeEdit -> types {
                copy(editingId = null, editTitle = "", editColor = TypesManager.DEFAULT_TYPE_COLOR)
            }
            is PanelEvent.DeleteType -> deleteType(event.typeId)
            is PanelEvent.SetNewTitle -> types { copy(newTitle = event.text) }
            is PanelEvent.SetNewColor -> types { copy(newColor = event.color) }
            PanelEvent.AddType -> addType()

            PanelEvent.OpenHistory -> openHistory()
            PanelEvent.CloseHistory -> vm.updateOverlays { copy(history = null) }
            is PanelEvent.FilterHistory -> history { copy(action = event.action) }
            is PanelEvent.HistoryDay -> history { copy(day = event.date) }
            is PanelEvent.OpenHistoryDetail -> history { copy(detailId = event.entryId) }
            PanelEvent.CloseHistoryDetail -> history { copy(detailId = null) }

            PanelEvent.OpenPresets -> openPresets()
            PanelEvent.ClosePresets -> vm.updateOverlays { copy(presets = null) }
            is PanelEvent.SearchPresets -> presets { copy(search = event.text) }
            PanelEvent.NewPreset -> if (vm.mayEdit()) presets { copy(form = PresetForm()) }
            is PanelEvent.EditPreset -> editPreset(event.presetId)
            is PanelEvent.SetPresetName -> presetForm { copy(name = event.text.take(PresetForm.NAME_LIMIT)) }
            is PanelEvent.SetPresetQuery -> presetForm { copy(query = event.text) }
            is PanelEvent.TogglePresetUser -> presetForm {
                copy(userIds = if (event.userId in userIds) userIds - event.userId else userIds + event.userId)
            }
            is PanelEvent.SetPresetUsers -> presetForm { copy(userIds = event.userIds.distinct()) }
            PanelEvent.SavePreset -> savePreset()
            PanelEvent.BackToPresets -> presets { copy(form = null) }
            is PanelEvent.AskDeletePreset -> if (vm.mayEdit()) presets { copy(confirmDelete = event.presetId) }
            PanelEvent.ConfirmDeletePreset -> deletePreset()
            PanelEvent.CancelDeletePreset -> presets { copy(confirmDelete = null) }
            is PanelEvent.ShowPresetMembers -> presets { copy(membersOf = event.presetId) }

            is PanelEvent.OpenPdf -> openPdf(event.destination)
            is PanelEvent.SetPdfLayout -> pdf { copy(options = options.copy(layout = event.layout)) }
            is PanelEvent.SetPdfPersonalNotes -> pdf {
                copy(options = options.copy(includePersonalNotes = event.include))
            }
            PanelEvent.SubmitPdf -> submitPdf()
            PanelEvent.ClosePdf -> vm.updateOverlays { copy(pdf = pdf?.takeIf { it.busy }) }

            PanelEvent.OpenPrintSelected -> {
                val state = vm.currentState
                if (state.page.selected.isNotEmpty() && state.canPrint) vm.updateOverlays {
                    copy(printSelected = PrintPrompt())
                }
            }
            is PanelEvent.SetPrintPersonalNotes -> vm.updateOverlays {
                copy(printSelected = printSelected?.copy(includePersonalNotes = event.include))
            }
            PanelEvent.ConfirmPrintSelected -> printSelected()
            PanelEvent.ClosePrintSelected -> vm.updateOverlays { copy(printSelected = null) }

            PanelEvent.OpenShare -> vm.updateOverlays { copy(share = SharePanel()) }
            PanelEvent.CloseShare -> vm.updateOverlays { copy(share = null) }
            PanelEvent.GenerateShareLink -> generateShareLink()
            PanelEvent.CopyShareLink -> vm.currentState.overlays.share?.link?.let { link ->
                vm.copyText(link)
                vm.notice(str(S.drive_link_copied), success = true)
                vm.updateOverlays { copy(share = share?.copy(linkCopied = true)) }
            }
            PanelEvent.CopyScheduleText -> copyScheduleText()
        }
    }

    private fun types(change: TypesManager.() -> TypesManager) = vm.updateOverlays { copy(types = types?.change()) }
    private fun history(change: HistoryPanel.() -> HistoryPanel) = vm.updateOverlays {
        copy(history = history?.change())
    }
    private fun presets(change: PresetsPanel.() -> PresetsPanel) = vm.updateOverlays {
        copy(presets = presets?.change())
    }
    private fun presetForm(change: PresetForm.() -> PresetForm) = presets { copy(form = form?.change()) }
    private fun pdf(change: PdfSheet.() -> PdfSheet) = vm.updateOverlays { copy(pdf = pdf?.change()) }

    private fun failure(error: ZillitError, fallback: String): String = error.localised().ifBlank { fallback }

    // Types --------------------------------------------------------------------

    /** A colour change saves at once — system types may only ever be recoloured. */
    private fun recolorType(typeId: String, color: String) {
        if (!vm.mayEdit()) return
        val type = vm.currentState.types.firstOrNull { it.id == typeId } ?: return
        if (type.color.equals(color, ignoreCase = true)) return
        vm.work {
            when (val result = vm.repo.updateType(typeId, title = null, color = color)) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.desktop_bs_type_updated), success = true)
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.notice(failure(result.error, str(S.desktop_bs_type_update_failed)))
            }
        }
    }

    private fun startTypeEdit(typeId: String) {
        val type = vm.currentState.types.firstOrNull { it.id == typeId } ?: return
        if (type.systemDefined) return
        types { copy(editingId = type.id, editTitle = type.title, editColor = type.color) }
    }

    /** Sends only what changed; nothing changed is simply the end of the edit. */
    private fun saveTypeEdit() {
        val manager = vm.currentState.overlays.types ?: return
        val type = vm.currentState.types.firstOrNull { it.id == manager.editingId } ?: return
        val title = manager.editTitle.trim()
        if (!vm.mayEdit() || title.isEmpty() || manager.savingEdit) return
        val newTitle = title.takeIf { it != type.title && !type.systemDefined }
        val newColor = manager.editColor.takeIf { !it.equals(type.color, ignoreCase = true) }
        if (newTitle == null && newColor == null) {
            types { copy(editingId = null) }
        } else {
            sendTypeEdit(type.id, newTitle, newColor)
        }
    }

    private fun sendTypeEdit(typeId: String, newTitle: String?, newColor: String?) {
        types { copy(savingEdit = true) }
        vm.work {
            when (val result = vm.repo.updateType(typeId, newTitle, newColor)) {
                is ZillitResult.Success -> {
                    types { copy(editingId = null, savingEdit = false) }
                    vm.notice(str(S.desktop_bs_type_updated), success = true)
                    vm.refresh()
                }
                is ZillitResult.Failure -> {
                    types { copy(savingEdit = false) }
                    vm.notice(failure(result.error, str(S.desktop_bs_type_update_failed)))
                }
            }
        }
    }

    private fun deleteType(typeId: String) {
        if (!vm.mayEdit()) return
        val type = vm.currentState.types.firstOrNull { it.id == typeId } ?: return
        if (type.systemDefined) return
        vm.work {
            when (val result = vm.repo.deleteType(typeId)) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.desktop_bs_type_deleted), success = true)
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.notice(failure(result.error, str(S.desktop_bs_type_delete_failed)))
            }
        }
    }

    private fun addType() {
        val manager = vm.currentState.overlays.types ?: return
        val title = manager.newTitle.trim()
        if (!vm.mayEdit() || title.isEmpty() || manager.creating) return
        types { copy(creating = true) }
        vm.work {
            when (val result = vm.repo.createType(title, manager.newColor)) {
                is ZillitResult.Success -> {
                    types { copy(creating = false, newTitle = "", newColor = TypesManager.DEFAULT_TYPE_COLOR) }
                    vm.notice(str(S.desktop_bs_type_created), success = true)
                    vm.refresh()
                }
                is ZillitResult.Failure -> {
                    types { copy(creating = false) }
                    vm.notice(failure(result.error, str(S.desktop_bs_failed_to_create_type)))
                }
            }
        }
    }

    // History ------------------------------------------------------------------

    /**
     * Reads the log, its revisions and the saved presets together. Opening it
     * reads the diary's notifications, when there are any to read.
     */
    private fun openHistory() {
        vm.updateOverlays { copy(history = HistoryPanel()) }
        if (vm.currentState.historyBadge > 0) vm.work { vm.host.onHistoryViewed() }
        vm.work {
            coroutineScope {
                val entries = async { vm.repo.history() }
                val revisions = async { vm.repo.revisions() }
                val presets = async { vm.repo.presets() }
                val log = entries.await()
                val revisionList = (revisions.await() as? ZillitResult.Success)?.data.orEmpty()
                val presetList = (presets.await() as? ZillitResult.Success)?.data.orEmpty()
                history {
                    copy(
                        loading = false,
                        entries = (log as? ZillitResult.Success)?.data.orEmpty(),
                        revisions = revisionList,
                        presets = presetList,
                    )
                }
                if (log is ZillitResult.Failure) vm.notice(failure(log.error, str(S.desktop_bs_history_load_failed)))
            }
        }
    }

    // Presets ------------------------------------------------------------------

    private fun openPresets() {
        vm.updateOverlays { copy(presets = PresetsPanel()) }
        reloadPresets()
    }

    private fun reloadPresets() {
        vm.work {
            when (val result = vm.repo.presets()) {
                is ZillitResult.Success -> presets { copy(loading = false, presets = result.data) }
                is ZillitResult.Failure -> {
                    presets { copy(loading = false) }
                    vm.notice(failure(result.error, str(S.bs_preset_failed)))
                }
            }
        }
    }

    private fun editPreset(presetId: String) {
        if (!vm.mayEdit()) return
        val preset = vm.currentState.overlays.presets?.presets?.firstOrNull { it.id == presetId } ?: return
        presets {
            copy(form = PresetForm(presetId = preset.id, name = preset.name, userIds = preset.members.map { it.id }))
        }
    }

    private fun savePreset() {
        val form = vm.currentState.overlays.presets?.form ?: return
        if (!vm.mayEdit() || form.saving) return
        when {
            form.name.isBlank() -> return vm.notice(str(S.desktop_bs_preset_name_required))
            form.userIds.isEmpty() -> return vm.notice(str(S.desktop_bs_select_at_least_one_user))
        }
        presetForm { copy(saving = true) }
        vm.work {
            when (val result = vm.repo.savePreset(form.presetId, form.name.trim(), form.userIds)) {
                is ZillitResult.Success -> {
                    vm.notice(str(if (form.isEdit) S.bs_preset_updated else S.bs_preset_created), success = true)
                    presets { copy(form = null, loading = true) }
                    reloadPresets()
                }
                is ZillitResult.Failure -> {
                    presetForm { copy(saving = false) }
                    val fallback = if (form.isEdit) S.bs_preset_update_failed else S.desktop_bs_preset_create_failed
                    vm.notice(failure(result.error, str(fallback)))
                }
            }
        }
    }

    private fun deletePreset() {
        val panel = vm.currentState.overlays.presets ?: return
        val presetId = panel.confirmDelete ?: return
        if (!vm.mayEdit() || panel.deleting) return
        presets { copy(deleting = true) }
        vm.work {
            when (val result = vm.repo.deletePreset(presetId)) {
                is ZillitResult.Success -> {
                    presets { copy(confirmDelete = null, deleting = false, loading = true) }
                    vm.notice(str(S.bs_preset_deleted), success = true)
                    reloadPresets()
                }
                is ZillitResult.Failure -> {
                    presets { copy(deleting = false) }
                    vm.notice(failure(result.error, str(S.bs_preset_delete_failed)))
                }
            }
        }
    }

    // PDF ----------------------------------------------------------------------

    /** The prompt opens on the layout of the view on screen, and With Personal Notes. */
    private fun openPdf(destination: PdfDestination) {
        if (!vm.mayEdit()) return
        if (destination == PdfDestination.Publish && !vm.currentState.canPublish) return
        vm.updateOverlays { copy(pdf = PdfSheet(destination, DiaryPdfOptions(layout = currentLayout()))) }
    }

    private fun currentLayout(): DiaryPdfLayout =
        if (vm.currentState.page.view == DiaryView.List) DiaryPdfLayout.List else DiaryPdfLayout.Calendar

    /** The palette's Print — straight to the file, laid out as the page is. */
    fun printNow() {
        if (!vm.mayEdit() || vm.currentState.overlays.pdf?.busy == true) return
        vm.updateOverlays { copy(pdf = PdfSheet(PdfDestination.Print, DiaryPdfOptions(layout = currentLayout()))) }
        submitPdf()
    }

    /**
     * Asks the server for the diary as chosen, then hands the staged file to
     * its destination. `action=print` for the library as well — the web sends
     * the same, and the server only logs the verb.
     */
    private fun submitPdf() {
        val sheet = vm.currentState.overlays.pdf ?: return
        if (sheet.busy || !vm.mayEdit()) return
        val host = vm.host
        pdf { copy(busy = true) }
        vm.work {
            val staged = vm.repo.pdf(sheet.options, DiaryPdfAction.Print, host.watermark())
            val outcome = when (staged) {
                is ZillitResult.Failure -> staged
                is ZillitResult.Success -> when (sheet.destination) {
                    PdfDestination.Print -> host.transfer?.open(staged.data)
                    PdfDestination.Publish -> host.publisher?.publish(staged.data)
                } ?: ZillitResult.Failure(ZillitError.Validation(str(S.desktop_not_available_here)))
            }
            when (outcome) {
                is ZillitResult.Failure -> {
                    vm.updateOverlays { copy(pdf = null) }
                    vm.notice(failure(outcome.error, str(S.desktop_cl_failed_to_generate_pdf)))
                }
                is ZillitResult.Success -> {
                    vm.updateOverlays { copy(pdf = null) }
                    if (sheet.destination == PdfDestination.Publish) {
                        vm.notice(str(S.desktop_board_published_to_docdist), success = true)
                    }
                }
            }
        }
    }

    /** The selected days as a page the system prints — `PrintableSchedule`. */
    private fun printSelected() {
        val prompt = vm.currentState.overlays.printSelected ?: return
        val printer = vm.host.printer ?: return
        val state = vm.currentState
        val rows = state.selectedRows
        if (rows.isEmpty() || prompt.printing) return
        vm.updateOverlays { copy(printSelected = prompt.copy(printing = true)) }
        val page = DiaryExports.printableHtml(
            rows = rows,
            types = state.types,
            events = state.events,
            includePersonalNotes = prompt.includePersonalNotes,
            now = vm.now(),
            zone = state.zone,
        )
        vm.work {
            val result = printer.print(page)
            vm.updateOverlays { copy(printSelected = null) }
            if (result is ZillitResult.Failure) vm.notice(failure(result.error, str(S.desktop_bs_print_failed)))
        }
    }

    // Share --------------------------------------------------------------------

    private fun generateShareLink() {
        val panel = vm.currentState.overlays.share ?: return
        if (panel.generating) return
        vm.updateOverlays { copy(share = panel.copy(generating = true)) }
        vm.work {
            when (val result = vm.repo.shareLink()) {
                is ZillitResult.Success -> vm.updateOverlays {
                    copy(share = share?.copy(link = result.data, generating = false))
                }
                is ZillitResult.Failure -> {
                    vm.updateOverlays { copy(share = share?.copy(generating = false)) }
                    vm.notice(str(S.drive_err_failed_to_generate_link))
                }
            }
        }
    }

    private fun copyScheduleText() {
        val state = vm.currentState
        vm.copyText(DiaryExports.scheduleText(state.rows, state.today, state.zone))
        vm.notice(str(S.desktop_bs_schedule_copied_as_text), success = true)
        vm.updateOverlays { copy(share = share?.copy(textCopied = true)) }
    }
}
