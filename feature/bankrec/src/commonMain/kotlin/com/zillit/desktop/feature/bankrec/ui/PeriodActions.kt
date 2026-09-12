package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import kotlinx.coroutines.async
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The two period tables — Overview's history and the History tab — and what is
 * done to a period from them: view it, export it, delete it.
 *
 * The delete is the most destructive act in the module: a period takes its
 * transactions, exceptions, fraud alerts and FX variances with it, and
 * un-matches every ledger entry that referenced them. A signed-off period is
 * kept out of the selection entirely — the server refuses it, because sign-off
 * marks invoices paid without recording what they were before.
 */
internal class PeriodActions(private val vm: BankRecViewModel) {

    @Suppress("CyclomaticComplexMethod") // One branch per action; each delegates.
    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.TogglePeriodSelection -> toggle(event.scope, event.periodId)
            is BankRecEvent.ToggleAllPeriods -> toggleAll(event.scope)
            is BankRecEvent.ClearPeriodSelection -> select(event.scope, emptySet())
            is BankRecEvent.AskDeletePeriods -> askDelete(event.periodIds)
            BankRecEvent.DismissDeletePeriods -> vm.update { copy(deleting = null) }
            BankRecEvent.ConfirmDeletePeriods -> confirmDelete()
            is BankRecEvent.SetHistoryAccount -> vm.update {
                // The selection is scoped to the account on screen: carrying it
                // across would delete periods the table no longer shows.
                copy(historyAccountId = event.bankAccountId, historySelection = emptySet())
            }

            is BankRecEvent.OpenPeriodDetail -> openDetail(event.periodId)
            BankRecEvent.ClosePeriodDetail -> vm.update { copy(periodDetail = null) }
            BankRecEvent.OpenExportPdf -> vm.update { copy(exportPdf = ExportPdfState()) }
            is BankRecEvent.ToggleExportPeriod -> toggleExport(event.periodId)
            BankRecEvent.ToggleAllExportPeriods -> toggleAllExport()
            BankRecEvent.CloseExportPdf -> vm.update { copy(exportPdf = null) }
            BankRecEvent.ConfirmExportPdf -> exportPdf()
            else -> return false
        }
        return true
    }

    /** The periods a scope's table shows — History is filtered to one account. */
    fun visible(scope: PeriodScope): List<BankPeriod> {
        val state = vm.ui
        return when (scope) {
            PeriodScope.Overview -> state.periods
            PeriodScope.History -> state.historyPeriods
        }
    }

    private fun selection(scope: PeriodScope): Set<String> = when (scope) {
        PeriodScope.Overview -> vm.ui.overviewSelection
        PeriodScope.History -> vm.ui.historySelection
    }

    private fun select(scope: PeriodScope, ids: Set<String>) = vm.update {
        when (scope) {
            PeriodScope.Overview -> copy(overviewSelection = ids)
            PeriodScope.History -> copy(historySelection = ids)
        }
    }

    private fun toggle(scope: PeriodScope, id: String) {
        if (visible(scope).none { it.id == id && it.isDeletable }) return
        val current = selection(scope)
        select(scope, if (id in current) current - id else current + id)
    }

    private fun toggleAll(scope: PeriodScope) {
        val deletable = visible(scope).filter { it.isDeletable }.map { it.id }.toSet()
        val everySelected = deletable.isNotEmpty() && selection(scope).containsAll(deletable)
        select(scope, if (everySelected) emptySet() else deletable)
    }

    /**
     * Drops ids that are gone or no longer deletable, so a period signed off or
     * deleted in another window cannot linger in a selection — and closes a
     * detail whose period has been deleted, rather than leaving it open over a
     * period that no longer exists.
     */
    fun onPeriodsChanged() {
        val state = vm.ui
        val deletable = state.periods.filter { it.isDeletable }.map { it.id }.toSet()
        vm.update {
            copy(
                overviewSelection = overviewSelection.intersect(deletable),
                historySelection = historySelection.intersect(deletable),
                exportPdf = exportPdf?.copy(
                    selected = exportPdf.selected.intersect(state.periods.map { it.id }.toSet()),
                ),
            )
        }
        val detail = state.periodDetail ?: return
        if (state.periods.none { it.id == detail.periodId }) {
            vm.update { copy(periodDetail = null) }
            vm.refuse("This period was deleted by another user.")
        }
    }

    private fun askDelete(ids: List<String>) {
        val periods = ids.mapNotNull { id -> vm.ui.period(id) }.filter { it.isDeletable }
        if (periods.isEmpty()) return vm.refuse("A signed-off period cannot be deleted.")
        vm.update { copy(deleting = DeleteRequest(ids = periods.map { it.id }, label = monthsLabel(periods))) }
    }

    private fun confirmDelete() {
        val request = vm.ui.deleting ?: return
        if (request.deleting) return
        vm.update { copy(deleting = request.copy(deleting = true)) }
        vm.runResult({ vm.repo.deletePeriods(request.ids) }, {
            vm.update {
                copy(
                    deleting = null,
                    overviewSelection = overviewSelection - request.ids.toSet(),
                    historySelection = historySelection - request.ids.toSet(),
                )
            }
            vm.notify(
                if (request.ids.size == 1) "${request.label} deleted." else "${request.ids.size} periods deleted.",
            )
            // Everything the periods produced went with them.
            vm.loadPeriods()
            vm.loadExceptions()
            vm.loadFraudAlerts()
            vm.loadFxVariances()
        }, { error ->
            vm.update { copy(deleting = request.copy(deleting = false)) }
            vm.report(error)
        })
    }

    /**
     * The read-only detail: the portal's summary of the period, with its bank
     * lines and its paid ledger entries under it. Two reads in parallel; either
     * failing leaves its half empty rather than the whole dialog in an error.
     */
    private fun openDetail(periodId: String) {
        val period = vm.ui.period(periodId) ?: return
        vm.update { copy(periodDetail = PeriodDetailState(periodId = periodId)) }
        vm.launchWork {
            val preview = async { vm.repo.portalPreview(periodId, period.bankAccountId) }
            val workspace = async { vm.repo.workspace(periodId) }
            val previewData = (preview.await() as? ZillitResult.Success)?.data
            val workspaceData = (workspace.await() as? ZillitResult.Success)?.data
            vm.update {
                if (periodDetail?.periodId != periodId) {
                    this
                } else {
                    copy(
                        periodDetail = periodDetail.copy(
                            loading = false,
                            preview = previewData,
                            transactions = workspaceData?.transactions.orEmpty(),
                            ledger = workspaceData?.ledger.orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private fun toggleExport(id: String) {
        val export = vm.ui.exportPdf ?: return
        val next = if (id in export.selected) export.selected - id else export.selected + id
        vm.update { copy(exportPdf = export.copy(selected = next)) }
    }

    private fun toggleAllExport() {
        val export = vm.ui.exportPdf ?: return
        val completed = vm.ui.completedPeriods.map { it.id }.toSet()
        val next = if (export.selected.size == completed.size && completed.isNotEmpty()) emptySet() else completed
        vm.update { copy(exportPdf = export.copy(selected = next)) }
    }

    private fun exportPdf() {
        val export = vm.ui.exportPdf ?: return
        if (export.selected.isEmpty() || export.exporting) return
        vm.update { copy(exportPdf = export.copy(exporting = true)) }
        vm.launchWork {
            val bytes = vm.repo.exportPeriodsPdf(export.selected.toList(), vm.company)
            val delivered = vm.deliver("reconciliation-export-${today()}.pdf", bytes)
            vm.update {
                if (delivered) copy(exportPdf = null) else copy(exportPdf = exportPdf?.copy(exporting = false))
            }
        }
    }

    private companion object {
        /** "Apr 2026", "Apr 2026 and May 2026", "Mar 2026, Apr 2026 and May 2026". */
        fun monthsLabel(periods: List<BankPeriod>): String {
            val months = periods.map { BankRecFormat.periodLabel(it) }
            return when (months.size) {
                1 -> months.first()
                2 -> "${months[0]} and ${months[1]}"
                else -> months.dropLast(1).joinToString(", ") + " and " + months.last()
            }
        }
    }
}

/** `2026-09-12`, for an export's file name — the web stamps it the same way. */
internal fun today(): String {
    val date = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
    val month = (date.month.ordinal + 1).toString().padStart(2, '0')
    return "${date.year}-$month-${date.day.toString().padStart(2, '0')}"
}
