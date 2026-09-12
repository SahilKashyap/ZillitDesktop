package com.zillit.desktop.feature.costreport.ui.worksheet

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.buildSections
import com.zillit.desktop.feature.costreport.ui.LedgerView
import com.zillit.desktop.feature.costreport.ui.SnapshotView
import com.zillit.desktop.feature.costreport.ui.exportFileName
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The posted history, a posted snapshot opened from it, and the ledger drill-down. */
internal class WorksheetSnapshotActions(private val vm: WorksheetViewModel) {

    fun handles(event: WorksheetEvent): Boolean {
        when (event) {
            is WorksheetEvent.SetHistoryFilter -> vm.update { copy(snapshots = snapshots.copy(filter = event.filter)) }
            WorksheetEvent.RefreshHistory -> refreshList()
            is WorksheetEvent.OpenSnapshot -> open(event.header)
            WorksheetEvent.CloseSnapshot -> vm.update { copy(snapshot = null) }
            is WorksheetEvent.SearchSnapshot -> vm.update { copy(snapshot = snapshot?.copy(query = event.query)) }
            is WorksheetEvent.ToggleSnapshotSection -> vm.update {
                copy(snapshot = snapshot?.let { it.copy(toggles = it.toggles.toggleSection(event.id)) })
            }
            is WorksheetEvent.ToggleSnapshotHeader -> vm.update {
                copy(snapshot = snapshot?.let { it.copy(toggles = it.toggles.toggleHeader(event.code)) })
            }
            is WorksheetEvent.ExportSnapshot -> exportSnapshot(event.format)
            is WorksheetEvent.OpenLedger -> openLedger(event.pane, event.nominal, event.column)
            WorksheetEvent.CloseLedger -> vm.update { copy(ledger = null) }
            else -> return false
        }
        return true
    }

    /** The posted list, newest first. A failed refresh keeps what was there. */
    fun refreshList() {
        if (vm.ui.snapshots.loading) return
        vm.update { copy(snapshots = snapshots.copy(loading = true)) }
        vm.launchWork {
            val rows = vm.repository.snapshots(null).getOrNull()
            vm.update {
                copy(snapshots = snapshots.copy(loading = false, loadedOnce = true, rows = rows ?: snapshots.rows))
            }
        }
    }

    private fun open(header: SnapshotHeader) {
        val state = vm.ui
        vm.update {
            copy(
                modal = null,
                snapshot = SnapshotView(header = header, symbol = reference.symbolFor(header.currency)),
            )
        }
        vm.launchWork {
            val result = vm.repository.snapshot(header.id)
            vm.update {
                val shown = snapshot?.takeIf { it.header.id == header.id } ?: return@update this
                when (result) {
                    is ZillitResult.Failure -> copy(
                        snapshot = shown.copy(loading = false, error = result.error.localised()),
                    )
                    is ZillitResult.Success -> copy(
                        snapshot = shown.copy(
                            loading = false,
                            detail = result.data,
                            sections = buildSections(state.reference.coa, result.data.lines),
                            symbol = reference.symbolFor(result.data.header.currency),
                        ),
                    )
                }
            }
        }
    }

    private fun exportSnapshot(format: ExportFormat) {
        val shown = vm.ui.snapshot ?: return
        if (shown.exporting != null) return
        val header = shown.shownHeader
        vm.update { copy(snapshot = snapshot?.copy(exporting = format)) }
        vm.launchWork {
            val body = buildJsonObject {
                put("project_name", JsonPrimitive(vm.ui.projectName))
                vm.companyName()?.takeIf { it.isNotBlank() }?.let { put("company_name", JsonPrimitive(it)) }
                header.postedBy?.let { put("generated_by", JsonPrimitive(vm.resolveUser(it) ?: it)) }
            }
            val outcome = when (val bytes = vm.exporter.export(header.id, format, body)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> vm.files.saveAndOpen(exportFileName(header, format), bytes.data)
            }
            vm.update {
                copy(snapshot = snapshot?.copy(exporting = null, error = outcome.errorOrNull()?.localised()))
            }
            if (outcome is ZillitResult.Success) vm.notice("${format.label} downloaded")
        }
    }

    /**
     * The account's line items. Contractual rows have no ledger behind them and
     * do not open; everything else does, Non-Allocated rows included — they are
     * opened precisely to find what needs re-coding.
     */
    private fun openLedger(pane: WorksheetPane, nominal: CrNominal, column: CrColumn?) {
        if (nominal.isContractual) return
        val shown = vm.pane(pane)
        val symbol = vm.ui.symbolFor(shown)
        vm.update {
            copy(
                ledger = LedgerView(
                    nominal = nominal,
                    type = column?.ledgerType,
                    source = column?.ledgerSource,
                    budget = nominal.line.budget,
                    symbol = symbol,
                ),
            )
        }
        vm.launchWork {
            val result = vm.repository.accountLineItems(
                code = nominal.apiCode,
                type = column?.ledgerType,
                source = column?.ledgerSource,
                currency = shown.applied.currency,
            )
            vm.update {
                val open = ledger?.takeIf { it.nominal.identity == nominal.identity } ?: return@update this
                when (result) {
                    is ZillitResult.Failure -> copy(
                        ledger = open.copy(loading = false, error = result.error.localised()),
                    )
                    is ZillitResult.Success -> copy(ledger = open.copy(loading = false, result = result.data))
                }
            }
        }
    }
}
