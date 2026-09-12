package com.zillit.desktop.feature.costreport.ui.worksheet

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrOverrides
import com.zillit.desktop.feature.costreport.domain.commit
import com.zillit.desktop.feature.costreport.domain.overridesFromVersion
import com.zillit.desktop.feature.costreport.domain.toVersionLines

/**
 * Compute, typing into the grid, and the weekly versions a forecast is saved as.
 */
internal class WorksheetEditActions(private val vm: WorksheetViewModel) {

    /** True when [event] was one of these; the view model hands everything else on. */
    fun handles(event: WorksheetEvent): Boolean {
        when (event) {
            is WorksheetEvent.Compute -> compute(event.pane)
            is WorksheetEvent.CommitCell -> commitCell(event.nominal, event.column, event.value)
            WorksheetEvent.Save -> save()
            WorksheetEvent.OpenSaveVersion -> vm.update { copy(modal = WorksheetModal.SaveVersion()) }
            is WorksheetEvent.SetVersionLabel -> vm.update {
                copy(modal = (modal as? WorksheetModal.SaveVersion)?.copy(label = event.label) ?: modal)
            }
            WorksheetEvent.ConfirmSaveVersion -> saveVersion()
            else -> return false
        }
        return true
    }

    /**
     * Compute: what is picked becomes what is shown.
     *
     * Only a company, budget or currency change re-fetches — a version is
     * applied on top of figures already loaded. A newly picked version replaces
     * the overrides with its own rows, converted into the currency about to be
     * shown; a currency change alone re-expresses the typed overrides, because
     * the server converts the figures and nothing converts what was typed.
     */
    private fun compute(pane: WorksheetPane) {
        val current = vm.pane(pane)
        val picked = current.pending
        val shown = current.applied
        val refetch = picked.companyId != shown.companyId || picked.budgetKey != shown.budgetKey ||
            picked.currency != shown.currency
        val versionChanged = picked.versionId != shown.versionId
        val rates = vm.ui.reference.rates
        vm.updatePane(pane) {
            copy(
                applied = picked,
                computing = refetch,
                overrides = when {
                    versionChanged -> if (picked.versionId == null) CrOverrides.NONE else overrides
                    picked.currency != shown.currency -> overrides.rescaled(
                        rates.factor(from = shown.currency, to = picked.currency),
                    )
                    else -> overrides
                },
            )
        }
        if (versionChanged) picked.versionId?.let { loadVersion(pane, it, picked.currency) }
        if (refetch) vm.loadPane(pane)
    }

    /**
     * A saved version's rows as the pane's overrides. The worksheet says what
     * it loaded; the Live CR loads quietly, as the web's does.
     */
    private fun loadVersion(pane: WorksheetPane, versionId: String, currency: String?) = vm.launchWork {
        when (val rows = vm.repository.etcVersion(versionId)) {
            is ZillitResult.Failure -> if (pane == WorksheetPane.Worksheet) {
                vm.notice("Load failed: ${rows.error.localised()}", error = true)
            }
            is ZillitResult.Success -> {
                if (vm.pane(pane).applied.versionId != versionId) return@launchWork
                val overrides = overridesFromVersion(rows.data, currency, vm.ui.reference.rates)
                vm.updatePane(pane) { copy(overrides = overrides) }
                if (pane == WorksheetPane.Worksheet) {
                    val label = vm.pane(pane).versions.firstOrNull { it.id == versionId }?.label?.ifBlank { null }
                        ?: "version"
                    val count = rows.data.size
                    vm.notice("Loaded $label · $count override row${if (count == 1) "" else "s"}")
                }
            }
        }
    }

    /** A value typed into the worksheet. Nothing moves inside a locked week. */
    private fun commitCell(nominal: CrNominal, column: CrColumn, value: Double) {
        val state = vm.ui
        if (state.isLocked || column !in CrOverrides.EDITABLE) return
        val result = state.ws.overrides.commit(nominal, column, value, state.symbolFor(state.ws))
        vm.updatePane(WorksheetPane.Worksheet) { copy(overrides = result.overrides) }
        result.warning?.let { vm.notice(it, error = true) }
    }

    /**
     * Save: overwrite the version that is picked with what is typed now. The
     * version keeps its place in the list; only its lines change. Unavailable
     * until a saved version is picked — Save Version makes a new one.
     */
    private fun save() {
        val state = vm.ui
        val versionId = state.ws.pending.versionId ?: return
        if (state.saving) return
        vm.update { copy(saving = true) }
        vm.launchWork {
            val result = vm.repository.updateEtcVersion(
                versionId = versionId,
                lines = state.ws.overrides.toVersionLines(),
                currency = state.ws.applied.currency,
            )
            when (result) {
                is ZillitResult.Failure -> vm.notice(result.error.localised(), error = true)
                is ZillitResult.Success -> {
                    vm.notice(result.data.message?.localisedMessage() ?: "Version saved")
                    vm.updatePane(WorksheetPane.Worksheet) { copy(applied = applied.copy(versionId = versionId)) }
                    vm.refreshVersions(WorksheetPane.Worksheet)
                }
            }
            vm.update { copy(saving = false) }
        }
    }

    /**
     * Save Version: a new version for this week, labelled, holding whatever is
     * typed — even nothing, which still stamps the week as saved. The new
     * version becomes both the picked and the shown one.
     */
    private fun saveVersion() {
        val state = vm.ui
        val modal = state.modal as? WorksheetModal.SaveVersion ?: return
        val week = state.ws.week ?: return
        if (state.savingVersion) return
        val label = modal.label.trim().ifBlank { "Untitled" }
        vm.update { copy(savingVersion = true) }
        vm.launchWork {
            val result = vm.repository.createEtcVersion(
                weekEnding = week.weekEnding,
                label = label,
                lines = state.ws.overrides.toVersionLines(),
                currency = state.ws.applied.currency,
            )
            when (result) {
                is ZillitResult.Failure -> vm.notice(result.error.localised(), error = true)
                is ZillitResult.Success -> {
                    vm.notice(result.data.message?.localisedMessage() ?: "Saved “$label”")
                    val id = result.data.value
                    vm.refreshVersions(WorksheetPane.Worksheet) {
                        if (id != null) {
                            vm.updatePane(WorksheetPane.Worksheet) {
                                copy(pending = pending.copy(versionId = id), applied = applied.copy(versionId = id))
                            }
                        }
                    }
                }
            }
            vm.update { copy(savingVersion = false, modal = null) }
        }
    }
}
