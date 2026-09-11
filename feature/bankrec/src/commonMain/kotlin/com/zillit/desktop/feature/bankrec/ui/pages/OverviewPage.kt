package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
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
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.ui.BankKpi
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab

/**
 * Where the reconciliation stands.
 *
 * The five figures at the top describe the period **in progress**, not the
 * newest: a signed-off month is done, and showing its balances as the current
 * state is how a finished period comes to look like an outstanding one.
 */
@Composable
fun ColumnScope.OverviewPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    if (state.periodsLoading && state.periods.isEmpty()) {
        ZillitSpinner()
        return
    }

    val period = state.currentPeriod
    if (period == null) {
        ZillitEmptyState(
            title = "No period open",
            message = "Import a statement to start a reconciliation. A statement spanning " +
                "several months opens a period for each.",
            icon = ZillitIcons.Bank,
            action = {
                ZillitButton(
                    text = "Import statement",
                    onClick = { onEvent(BankRecEvent.ComposeImport) },
                    leadingIcon = ZillitIcons.Upload,
                    enabled = state.canImport,
                )
            },
        )
        PeriodTable(state, onEvent, title = "Earlier periods")
        return
    }

    if (period.fraudCount > 0) {
        ZillitNotice(
            text = "${period.fraudCount} payment(s) flagged for review in ${state.periodLabel(period)}.",
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Siren,
            modifier = Modifier.fillMaxWidth(),
            action = {
                ZillitButton(
                    text = "Review",
                    onClick = { onEvent(BankRecEvent.OpenTab(BankTab.FraudAlerts)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
            },
        )
    }

    KpiRow(state)
    ProgressCard(state, onEvent)
    PeriodTable(state, onEvent, title = "Reconciliation history")
}

@Composable
private fun ColumnScope.KpiRow(state: BankRecUiState) {
    val kpi = state.kpiFor(state.currentPeriod)
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BankBalanceTile(kpi, Modifier.weight(1f))
        ZillitStatTile(
            label = "Zillit balance",
            value = money(kpi.zillitBalance, kpi.currency),
            sub = "Ledger balance",
            modifier = Modifier.weight(1f),
        )
        DifferenceTile(kpi, Modifier.weight(1f))
        ZillitStatTile(
            label = "Matched",
            value = kpi.matched.toString(),
            sub = "of ${kpi.total} transactions",
            tone = StatusTone.Done,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Period",
            value = state.currentPeriod?.let { state.periodLabel(it) } ?: "—",
            sub = "Current reconciliation",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The statement's closing balance, in the project's own currency.
 *
 * The statement's own figure sits under it whenever a conversion happened, so
 * a converted number is never mistaken for what the bank actually said. With
 * no rate set nothing is converted and the tile says so.
 */
@Composable
private fun BankBalanceTile(kpi: BankKpi, modifier: Modifier) {
    ZillitStatTile(
        label = "Bank balance",
        value = kpi.bankBalance?.let { money(it, kpi.currency) } ?: "—",
        sub = when {
            !kpi.hasRate -> "${kpi.nativeCurrency} — no exchange rate set"
            kpi.nativeBalance != null ->
                "${money(kpi.nativeBalance, kpi.nativeCurrency.orEmpty())} converted"

            else -> "Statement closing"
        },
        tone = if (kpi.hasRate) null else StatusTone.Pending,
        modifier = modifier,
    )
}

/** Null when the two sides are in currencies the project has no rate between. */
@Composable
private fun DifferenceTile(kpi: BankKpi, modifier: Modifier) {
    ZillitStatTile(
        label = "Difference",
        value = kpi.difference?.let { signedMoney(it, kpi.currency) } ?: "—",
        sub = if (kpi.difference == null) "Not comparable" else "To reconcile",
        tone = when {
            kpi.difference == null -> StatusTone.Pending
            kpi.isReconciled -> StatusTone.Done
            else -> StatusTone.Rejected
        },
        modifier = modifier,
    )
}

@Composable
private fun ColumnScope.ProgressCard(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val period = state.currentPeriod ?: return
    ZillitSectionCard(
        title = state.periodLabel(period),
        meta = "${period.matchedPercent}% matched",
        icon = ZillitIcons.BarChart,
        action = {
            ZillitButton(
                text = "Open workspace",
                onClick = { onEvent(BankRecEvent.OpenPeriod(period.id)) },
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ArrowRight,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitMeter(
            fraction = period.matchedFraction,
            tone = if (period.matchedFraction >= 1f) StatusTone.Done else StatusTone.Progress,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Count("Matched", period.matchedCount)
            Count("Suggested", period.suggestedCount)
            Count("Unmatched", period.unmatchedCount)
            Count("Fraud flags", period.fraudCount)
        }
    }
}

@Composable
private fun Count(label: String, value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = value.toString(), style = ZillitTheme.typography.titleSmall)
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}
