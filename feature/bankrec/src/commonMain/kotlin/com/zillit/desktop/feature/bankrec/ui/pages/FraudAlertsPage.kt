package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.dayLabel

/**
 * The payments the engine wants a person to look at.
 *
 * Dismissing puts a line back to ordinary. Escalating does not: it tells
 * somebody outside this screen that a payment on this production may be
 * fraudulent, which is why it is the one that asks first.
 */
@Composable
fun ColumnScope.FraudAlertsPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val fraud = state.fraud

    PeriodFilter(state, fraud.periodId) { onEvent(BankRecEvent.FilterFraud(it)) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitStatTile(
            label = "Awaiting review",
            value = fraud.activeCount.toString(),
            tone = if (fraud.activeCount > 0) StatusTone.Escalated else StatusTone.Done,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "High risk",
            value = fraud.alerts.count { it.isHighRisk }.toString(),
            sub = "Risk score 75 or over",
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Reviewed",
            value = fraud.alerts.count { !it.isOpen }.toString(),
            modifier = Modifier.weight(1f),
        )
    }

    if (fraud.loading && fraud.alerts.isEmpty()) {
        ZillitSpinner()
        return
    }

    if (fraud.alerts.isEmpty()) {
        ZillitEmptyState(
            title = "Nothing flagged",
            message = "No payment on this period tripped a fraud check.",
            icon = ZillitIcons.Shield,
        )
    }

    fraud.alerts.forEach { alert -> AlertCard(alert, state, onEvent) }

    AuditLog(state, onEvent)
}

@Composable
private fun ColumnScope.AlertCard(
    alert: FraudAlert,
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
) {
    val currency = alert.transaction?.currency
        ?: state.currencyOf(state.periods.firstOrNull { it.id == alert.periodId })

    ZillitSectionCard(
        title = alert.title.ifBlank { alert.alertType?.label.orEmpty().ifBlank { "Flagged payment" } },
        meta = "Risk ${alert.riskScore} · ${alert.riskLabel}",
        icon = ZillitIcons.Siren,
        action = {
            ZillitStatusPill(label = alert.status.label, tone = alert.status.tone(), dot = true)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = alert.description.ifBlank { alert.alertType?.explanation.orEmpty() },
            style = ZillitTheme.typography.bodyMedium,
        )

        PaymentLine(alert, currency)
        VendorDetail(alert)

        if (alert.signals.isNotEmpty()) {
            ZillitText(
                text = alert.signals.joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        AlertFooter(alert, state, onEvent)
    }
}

@Composable
private fun ColumnScope.PaymentLine(alert: FraudAlert, currency: String) {
    val txn = alert.transaction ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = txn.vendorName.ifBlank { txn.description },
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = signedMoney(txn.amount, txn.currency ?: currency),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.AlertFooter(
    alert: FraudAlert,
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = dayLabel(alert.createdAtMillis),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        if (alert.isOpen) {
            ZillitButton(
                text = "Dismiss",
                onClick = { onEvent(BankRecEvent.DismissAlert(alert)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                loading = state.fraud.acting == alert.id,
            )
            ZillitButton(
                text = "Escalate",
                onClick = { onEvent(BankRecEvent.AskEscalate(alert)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * The bank details the payment went to.
 *
 * Shown in full because this is the one screen where a changed sort code is
 * the whole point: an alert that hides the digits cannot be checked against
 * the supplier's record.
 */
@Composable
private fun ColumnScope.VendorDetail(alert: FraudAlert) {
    if (alert.vendorName.isBlank() && alert.vendorSortCode.isBlank()) return
    ZillitText(
        text = listOfNotNull(
            alert.vendorName.takeIf { it.isNotBlank() },
            alert.vendorSortCode.takeIf { it.isNotBlank() }?.let { "Sort $it" },
            alert.vendorAccountNumber.takeIf { it.isNotBlank() }?.let { "Account $it" },
        ).joinToString(" · "),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

@Composable
private fun ColumnScope.AuditLog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val fraud = state.fraud
    ZillitSectionCard(
        title = "Audit trail",
        meta = if (fraud.showAuditLog) "${fraud.auditLog.size} entries" else null,
        icon = ZillitIcons.Ledger,
        action = {
            ZillitButton(
                text = if (fraud.showAuditLog) "Hide" else "Show",
                onClick = { onEvent(BankRecEvent.ShowAuditLog(!fraud.showAuditLog)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!fraud.showAuditLog) {
            ZillitText(
                text = "Who decided what about each alert, and when.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            return@ZillitSectionCard
        }
        if (fraud.auditLog.isEmpty()) {
            ZillitText(
                text = "Nothing recorded for this period yet.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            return@ZillitSectionCard
        }
        fraud.auditLog.forEach { entry -> AuditRow(entry) }
    }
}

@Composable
private fun ColumnScope.AuditRow(entry: FraudAuditEntry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitText(
                text = entry.action.ifBlank { "Changed" },
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = listOfNotNull(
                    entry.userName.takeIf { it.isNotBlank() },
                    dayLabel(entry.atMillis).takeIf { it != "—" },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        if (entry.detail.isNotBlank()) {
            ZillitText(
                text = entry.detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitDivider()
    }
}
