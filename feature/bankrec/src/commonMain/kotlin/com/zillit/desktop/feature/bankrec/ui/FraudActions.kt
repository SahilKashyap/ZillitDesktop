package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.AuditAction
import com.zillit.desktop.feature.bankrec.domain.AuditExportFormat
import com.zillit.desktop.feature.bankrec.domain.AuditFilters
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FraudStatus

/**
 * The payments the fraud engine wants a person to look at, and the trail of
 * who decided what.
 *
 * "Investigated — No Issue" dismisses; "Escalate to Finance" escalates. Both
 * are one click, as on the web, and both land in the audit trail against the
 * person who pressed them.
 */
internal class FraudActions(private val vm: BankRecViewModel) {

    private val page: FraudPageState get() = vm.ui.fraudPage

    private fun edit(reducer: FraudPageState.() -> FraudPageState) =
        vm.update { copy(fraudPage = fraudPage.reducer()) }

    private fun editAudit(reducer: AuditLogState.() -> AuditLogState) =
        edit { copy(audit = audit?.reducer()) }

    @Suppress("CyclomaticComplexMethod") // One branch per action; each delegates.
    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.SetFraudPeriod -> edit { copy(periodChoice = event.choice) }
            is BankRecEvent.DismissAlert -> act(event.alertId, AlertAction.Dismiss)
            is BankRecEvent.EscalateAlert -> act(event.alertId, AlertAction.Escalate)
            BankRecEvent.OpenAuditLog -> openAudit()
            BankRecEvent.CloseAuditLog -> edit { copy(audit = null) }
            is BankRecEvent.FilterAuditLog -> filter(event.filters)
            is BankRecEvent.SortAuditLog -> editAudit {
                if (sort == event.sort) copy(ascending = !ascending) else copy(sort = event.sort, ascending = false)
            }

            is BankRecEvent.ShowAuditExportMenu -> editAudit { copy(exportMenu = event.show) }
            is BankRecEvent.ExportAuditLog -> export(event.format)
            else -> return false
        }
        return true
    }

    private fun act(id: String, action: AlertAction) {
        if (page.acting != null) return
        edit { copy(acting = id to action) }
        vm.runResult(
            {
                when (action) {
                    AlertAction.Dismiss -> vm.repo.dismissFraudAlert(id)
                    AlertAction.Escalate -> vm.repo.escalateFraudAlert(id)
                }
            },
            {
                val status = if (action == AlertAction.Dismiss) FraudStatus.Dismissed else FraudStatus.Escalated
                vm.update {
                    copy(
                        fraudPage = fraudPage.copy(acting = null),
                        fraudAlerts = fraudAlerts.map { if (it.id == id) it.copy(status = status) else it },
                    )
                }
                vm.notify(if (action == AlertAction.Dismiss) "Marked as investigated." else "Escalated to finance.")
                // The period's fraud count and the workspace's flags move with it.
                vm.loadFraudAlerts()
                vm.loadPeriods()
                vm.workspaceActions.refresh()
            },
            { error ->
                edit { copy(acting = null) }
                vm.report(error)
            },
        )
    }

    private fun openAudit() {
        edit { copy(audit = AuditLogState(loading = true)) }
        vm.runResult({ vm.repo.fraudAuditLog() }, { rows ->
            editAudit { copy(loading = false, entries = rows) }
        }, { error ->
            editAudit { copy(loading = false) }
            vm.report(error)
        })
    }

    /** Choosing a bank resets the period, whose options belong to the bank. */
    private fun filter(filters: AuditFilters) = editAudit {
        val next = if (filters.bankAccountId != this.filters.bankAccountId) filters.copy(periodId = "") else filters
        copy(filters = next)
    }

    /** The trail as filtered on screen — the export carries the same filters, so the file matches. */
    private fun export(format: AuditExportFormat) {
        val audit = page.audit ?: return
        if (audit.exporting != null) return
        editAudit { copy(exporting = format, exportMenu = false) }
        vm.launchWork {
            val bytes = vm.repo.exportAuditLog(format, audit.filters, vm.company)
            vm.deliver("audit-log.${format.extension}", bytes)
            editAudit { copy(exporting = null) }
        }
    }
}

/** The audit trail as the dialog shows it: filtered, then sorted. */
internal fun AuditLogState.visibleEntries(): List<FraudAuditEntry> {
    val sorted = entries.filter(filters::accepts).sortedWith(sort.comparator)
    return if (ascending) sorted else sorted.reversed()
}

/** Every filter set must match; a blank one matches anything. */
private fun AuditFilters.accepts(entry: FraudAuditEntry): Boolean =
    listOf(
        bankAccountId to entry.bankAccountId,
        periodId to entry.periodId,
        performedBy to entry.performedBy,
        action to entry.action,
    ).all { (filter, value) -> filter.isBlank() || filter == value }

private val AuditSort.comparator: Comparator<FraudAuditEntry>
    get() = when (this) {
        AuditSort.CreatedAt -> compareBy { it.createdAtMillis ?: 0L }
        AuditSort.Action -> compareBy { AuditAction.labelFor(it.action) }
        AuditSort.Risk -> compareBy { it.riskScore ?: 0 }
        AuditSort.Amount -> compareBy { it.transaction?.debit ?: 0.0 }
        AuditSort.Vendor -> compareBy { it.transaction?.vendorName.orEmpty().lowercase() }
    }
