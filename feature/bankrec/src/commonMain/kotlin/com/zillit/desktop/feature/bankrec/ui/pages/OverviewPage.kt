package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.ui.BankKpi
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.PeriodScope
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrBanner
import com.zillit.desktop.feature.bankrec.ui.components.BrCard
import com.zillit.desktop.feature.bankrec.ui.components.BrLegendDot
import com.zillit.desktop.feature.bankrec.ui.components.BrSegmentBar
import com.zillit.desktop.feature.bankrec.ui.components.BrStatCard
import com.zillit.desktop.feature.bankrec.ui.components.BrTileGrid
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.components.titleStyle

/**
 * Where the reconciliation stands.
 *
 * The five figures and the progress card describe the period **in progress**,
 * not the newest: a signed-off month is done, and showing its balances as the
 * current state is how a finished period comes to look like an outstanding
 * one. The history under them is every period.
 */
@Composable
fun ColumnScope.OverviewPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    if (state.periodsLoading) {
        OverviewSkeleton()
        return
    }
    val current = state.currentPeriod

    ActionRow(
        leading = {
            if (current != null && current.fraudCount > 0) {
                BrBanner(
                    tone = BrTone.Red,
                    icon = ZillitIcons.Shield,
                    title = "${current.fraudCount} Fraud Alert${if (current.fraudCount == 1) "" else "s"}",
                    message = "Requires your review",
                    fill = false,
                    action = {
                        Spacer(Modifier.width(6.dp))
                        ZillitButton(
                            text = "Review",
                            onClick = { onEvent(BankRecEvent.OpenTab(BankTab.FraudAlerts)) },
                            variant = ButtonVariant.Danger,
                            size = ButtonSize.Small,
                            trailingIcon = ZillitIcons.ArrowRight,
                        )
                    },
                )
            }
        },
    ) {
        ZillitButton(
            text = "Import Statement",
            onClick = { onEvent(BankRecEvent.OpenImport) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Upload,
            enabled = state.canImport,
        )
        if (current != null) {
            ZillitButton(
                text = "Open Workspace",
                onClick = { onEvent(BankRecEvent.OpenPeriod(current.id)) },
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ArrowRight,
            )
        }
    }

    KpiGrid(state, current)
    if (current != null) ProgressCard(state, current, onEvent)
    PeriodHistoryCard(state, onEvent, scope = PeriodScope.Overview)
}

@Composable
private fun KpiGrid(state: BankRecUiState, current: BankPeriod?) {
    val kpi = state.kpiFor(current)
    val colors = ZillitTheme.colors
    BrTileGrid(
        columns = 5,
        tiles = listOf(
            { modifier -> BankBalanceTile(kpi, modifier) },
            { modifier ->
                BrStatCard(
                    label = "Zillit Balance",
                    value = BankRecFormat.plainMoney(kpi.zillitBalance, kpi.currency),
                    sub = "Ledger balance",
                    modifier = modifier,
                )
            },
            { modifier -> DifferenceTile(kpi, modifier) },
            { modifier ->
                BrStatCard(
                    label = "Auto-Matched",
                    value = kpi.matched.toString(),
                    sub = "of ${kpi.total} transactions",
                    valueColor = colors.success,
                    modifier = modifier,
                )
            },
            { modifier ->
                BrStatCard(
                    label = "Period",
                    value = current?.let(BankRecFormat::periodLabel) ?: BankRecFormat.DASH,
                    sub = "Current reconciliation",
                    valueColor = colors.gold,
                    modifier = modifier,
                )
            },
        ),
    )
}

/**
 * The statement's closing balance, in the project's own currency.
 *
 * The statement's own figure sits under it whenever a conversion happened, so
 * a converted number is never mistaken for what the bank actually said. With
 * no rate set nothing is converted: the statement's figure is shown in its own
 * currency, in amber, and the tile says why.
 */
@Composable
private fun BankBalanceTile(kpi: BankKpi, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val native = kpi.nativeBalance
    BrStatCard(
        label = "Bank Balance",
        value = when {
            kpi.bankBalance != null -> BankRecFormat.plainMoney(kpi.bankBalance, kpi.currency)
            native != null -> BankRecFormat.plainMoney(native, kpi.nativeCurrency)
            else -> BankRecFormat.DASH
        },
        sub = when {
            !kpi.hasRate -> "${kpi.nativeCurrency} — no exchange rate set"
            native != null -> "${BankRecFormat.plainMoney(native, kpi.nativeCurrency)} ${kpi.nativeCurrency} converted"
            else -> "Statement closing"
        },
        valueColor = if (kpi.hasRate) null else colors.warning,
        modifier = modifier,
    )
}

/** Blank when the two sides are in currencies the project has no rate between. */
@Composable
private fun DifferenceTile(kpi: BankKpi, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val difference = kpi.difference
    BrStatCard(
        label = "Difference",
        value = difference?.let { BankRecFormat.money(it, kpi.currency) } ?: BankRecFormat.DASH,
        sub = if (difference == null) "Not comparable — no exchange rate" else "To reconcile",
        valueColor = when {
            difference == null -> colors.warning
            kpi.isReconciled -> colors.success
            else -> colors.danger
        },
        modifier = modifier,
    )
}

/** The period in progress: how far through it is, and a way back into it. */
@Composable
private fun ProgressCard(state: BankRecUiState, period: BankPeriod, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val total = period.totalTxns.coerceAtLeast(0)
    fun share(count: Int) = if (total == 0) 0f else count.toFloat() / total

    BrCard(modifier = Modifier.fillMaxWidth(), padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZillitText("${BankRecFormat.fullPeriodLabel(period)} Reconciliation", style = titleStyle(14.sp))
            BrBadge(period.status.label, period.status.tone)
            state.account(period.bankAccountId)?.displayName?.takeIf { it.isNotBlank() }?.let {
                ZillitText("· $it", style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            period.createdAtMillis?.let {
                ZillitText(
                    "Started ${BankRecFormat.day(it)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(
                text = "Continue",
                onClick = { onEvent(BankRecEvent.OpenPeriod(period.id)) },
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ArrowRight,
            )
        }
        Spacer(Modifier.height(14.dp))
        BrSegmentBar(
            segments = listOf(
                share(period.matchedCount) to colors.success,
                share(period.suggestedCount) to colors.warning,
                share(period.unmatchedCount) to colors.danger.copy(alpha = 0.7f),
                share(period.fraudCount) to colors.danger,
            ),
            height = 12.dp,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BrLegendDot(colors.success, "Matched", period.matchedCount)
            BrLegendDot(colors.warning, "Suggested", period.suggestedCount)
            // Unmatched counts the flagged lines too, as the web's legend does:
            // a line under fraud review is not matched either.
            BrLegendDot(colors.danger.copy(alpha = 0.7f), "Unmatched", period.unmatchedCount + period.fraudCount)
            BrLegendDot(colors.danger, "Fraud flags", period.fraudCount, pulse = period.fraudCount > 0)
            Spacer(Modifier.weight(1f))
            ZillitText(
                "${BankRecFormat.percent(period.matchedCount, total)} complete",
                style = mono(12.sp, FontWeight.Medium),
                color = colors.textSecondary,
            )
        }
    }
}

/** The page's shape while the first list is on its way — the web's skeleton, not a spinner. */
@Composable
private fun OverviewSkeleton() {
    val colors = ZillitTheme.colors
    BrTileGrid(
        columns = 5,
        tiles = List(5) {
            { modifier ->
                Column(
                    modifier.clip(ZillitTheme.shapes.large).border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZillitSkeletonBar(Modifier.width(90.dp))
                    ZillitSkeletonBar(Modifier.width(120.dp), height = 20.dp)
                    ZillitSkeletonBar(Modifier.width(100.dp))
                }
            }
        },
    )
    BrCard(Modifier.fillMaxWidth(), padded = true) {
        ZillitSkeletonBar(Modifier.width(220.dp))
        Spacer(Modifier.height(14.dp))
        ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 12.dp)
        Spacer(Modifier.height(12.dp))
        ZillitSkeletonBar(Modifier.width(360.dp))
    }
    BrCard(Modifier.fillMaxWidth(), title = "Reconciliation History", icon = ZillitIcons.Clock) {
        com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows(5)
    }
}
