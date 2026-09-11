package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudStatus

/**
 * The payments the fraud engine wants a person to look at.
 *
 * Dismissing is reversible in effect — the line goes back to being an ordinary
 * one — but escalating is not: it tells somebody outside this screen that a
 * payment on this production may be fraudulent, so it is confirmed.
 */
internal class FraudActions(private val vm: BankRecViewModel) {

    private val state: FraudState get() = vm.ui.fraud

    private fun edit(reducer: FraudState.() -> FraudState) =
        vm.update { copy(fraud = fraud.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.FilterFraud -> {
                edit { copy(periodId = event.periodId) }
                load(force = true)
            }

            is BankRecEvent.DismissAlert -> dismiss(event.alert)
            is BankRecEvent.AskEscalate -> edit { copy(escalating = event.alert) }
            BankRecEvent.DismissEscalate -> edit { copy(escalating = null) }
            BankRecEvent.ConfirmEscalate -> escalate()
            is BankRecEvent.ShowAuditLog -> showAuditLog(event.show)
            else -> return false
        }
        return true
    }

    fun load(force: Boolean) {
        if (!force && state.alerts.isNotEmpty()) return
        val periodId = state.periodId
        edit { copy(loading = true) }
        vm.runResult({ vm.repo.fraudAlerts(periodId.takeIf { it.isNotBlank() }) }, { rows ->
            edit { copy(alerts = rows, loading = false) }
        }, { error ->
            edit { copy(loading = false) }
            vm.report(error)
        })
        if (state.showAuditLog) loadAuditLog()
    }

    private fun showAuditLog(show: Boolean) {
        edit { copy(showAuditLog = show) }
        if (show && state.auditLog.isEmpty()) loadAuditLog()
    }

    /**
     * The trail of who decided what.
     *
     * Failures are swallowed: an empty trail is a smaller loss than an error
     * over a page of alerts that is otherwise working, and the alerts
     * themselves already carry their status.
     */
    private fun loadAuditLog() {
        val periodId = state.periodId
        vm.runResult({ vm.repo.fraudAuditLog(periodId.takeIf { it.isNotBlank() }) }, { rows ->
            edit { copy(auditLog = rows) }
        }, { })
    }

    private fun dismiss(alert: FraudAlert) {
        edit { copy(acting = alert.id) }
        vm.runResult({ vm.repo.dismissFraudAlert(alert.id) }, {
            settle(alert.id, FraudStatus.Dismissed)
            vm.notify("Alert dismissed.")
        }, { error ->
            edit { copy(acting = "") }
            vm.report(error)
        })
    }

    private fun escalate() {
        val alert = state.escalating ?: return
        edit { copy(acting = alert.id, escalating = null) }
        vm.runResult({ vm.repo.escalateFraudAlert(alert.id) }, {
            settle(alert.id, FraudStatus.Escalated)
            vm.notify("Alert escalated.")
        }, { error ->
            edit { copy(acting = "") }
            vm.report(error)
        })
    }

    private fun settle(id: String, status: FraudStatus) {
        edit {
            copy(
                acting = "",
                alerts = alerts.map { if (it.id == id) it.copy(status = status) else it },
            )
        }
        // The count on the period header moves with it.
        vm.loadPeriods()
        loadAuditLog()
    }
}
