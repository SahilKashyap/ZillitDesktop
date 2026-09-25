package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.AlertFilter
import com.zillit.desktop.feature.cardexpenses.domain.AlertSeverity
import com.zillit.desktop.feature.cardexpenses.domain.AlertText
import com.zillit.desktop.feature.cardexpenses.domain.AlertTxn
import com.zillit.desktop.feature.cardexpenses.domain.AnalyticsMath
import com.zillit.desktop.feature.cardexpenses.domain.AnalyticsSlice
import com.zillit.desktop.feature.cardexpenses.domain.BudgetPhaseName
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CashFlowWeek
import com.zillit.desktop.feature.cardexpenses.ui.AlertAction
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InsightsEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightBar
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightChips
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightEyebrow
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightTag
import com.zillit.desktop.feature.cardexpenses.ui.components.insightCard
import com.zillit.desktop.feature.cardexpenses.ui.money
import kotlin.math.max
import kotlin.math.roundToInt

// == Analytics ==================================================================

/**
 * Card spend, broken down — the web's `AnalyticsPage.jsx:189-512`.
 *
 * Six tiles; spend by department and by holder on the left; the cash-flow
 * bars and the cost-report impact on the right; processing times across the
 * foot. `/analytics/overview` is read with no window, as the web reads it —
 * the page has no period picker. The cash-flow and budget figures are the
 * web's own illustrative formulas ([AnalyticsMath]), kept identical.
 */
@Composable
fun AnalyticsPage(state: CardUiState) {
    val analytics = state.analytics
    if (analytics == null) {
        ScrollingPage {
            if (state.loading) Row(Modifier.fillMaxWidth(), Arrangement.Center) { ZillitSpinner() }
        }
        return
    }
    val currency = state.currency
    val departments = analytics.byDepartment
    val deptTotal = departments.sumOf { it.amount }
    val totalSpend = analytics.totalSpend.takeIf { it != 0.0 } ?: deptTotal

    ScrollingPage {
        AnalyticsTiles(state, analytics, totalSpend, currency)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                DepartmentCard(state, departments, deptTotal, currency)
                HolderCard(state, analytics.byHolder, currency)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                CashFlowCard(totalSpend, analytics.postedTotal, currency)
                CostImpactCard(totalSpend, analytics.postedTotal, currency)
            }
        }
        PerformanceCard(analytics)
    }
}

/** The six tiles (`AnalyticsPage.jsx:190-197`). */
@Composable
private fun AnalyticsTiles(state: CardUiState, analytics: CardAnalytics, totalSpend: Double, currency: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        val tile = Modifier.weight(1f)
        ZillitStatTile(
            label = str(S.ah_total_spend),
            value = money(totalSpend, currency),
            sub = receiptCount(analytics.transactionCount),
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_ce_insights_avg_per_receipt),
            value = money(analytics.averageTransaction, currency),
            sub = str(S.desktop_ce_insights_median_x, money(analytics.averageTransaction * MEDIAN_FACTOR, currency)),
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.ah_daily_avg),
            value = money(AnalyticsMath.jsRound(totalSpend / DAYS), currency),
            sub = str(S.dd_range_30),
            tone = StatusTone.Progress,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.ah_vat_recovered),
            value = money(AnalyticsMath.jsRound(totalSpend / VAT_DIVISOR * CENTS) / CENTS, currency),
            sub = str(S.desktop_ce_insights_standard_rate),
            tone = StatusTone.Ready,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.ah_active_cards),
            value = analytics.activeCards.toString(),
            sub = str(S.desktop_ce_insights_n_total_cards, analytics.totalCards),
            tone = StatusTone.Pending,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_ce_insights_top_dept),
            value = departmentName(state, analytics.topDepartmentId),
            sub = money(analytics.byDepartment.firstOrNull()?.amount ?: 0.0, currency),
            tone = StatusTone.Pending,
            modifier = tile,
        )
    }
}

private fun receiptCount(count: Int): String =
    if (count == 1) str(S.desktop_card_receipt_count_one, count) else str(S.desktop_card_receipt_count_other, count)

/**
 * A department reference, named: the id first, then the slice's own label,
 * then "—" — never the raw id (`AnalyticsPage.jsx:75-80`). No id at all is
 * "Unassigned".
 */
private fun departmentName(state: CardUiState, id: String?, label: String? = null): String {
    if (id.isNullOrBlank()) return label?.takeIf { it.isNotBlank() } ?: str(S.unassigned)
    return state.insights.departments.firstOrNull { it.id == id }?.name
        ?: state.people.firstOrNull { it.departmentId == id }?.department?.takeIf { it.isNotBlank() }
        ?: label?.takeIf { it.isNotBlank() }
        ?: "—"
}

/** "Spend by Department" (`AnalyticsPage.jsx:203-243`). */
@Composable
private fun DepartmentCard(state: CardUiState, slices: List<AnalyticsSlice>, total: Double, currency: String?) {
    ZillitSectionCard(title = str(S.ah_spend_by_department), icon = ZillitIcons.BarChart) {
        if (slices.isEmpty()) {
            EmptyLine(str(S.desktop_ce_insights_no_department_data))
            return@ZillitSectionCard
        }
        val palette = departmentPalette()
        slices.forEachIndexed { index, slice ->
            val pct = if (total > 0) (slice.amount / total * PERCENT).roundToInt() else 0
            val tint = palette[index % palette.size]
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
                    ZillitText(
                        text = departmentName(state, slice.departmentId, slice.label),
                        style = ZillitTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                    ZillitText(
                        text = "$pct%",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    ZillitText(
                        text = str(S.desktop_ce_insights_paren_receipts, slice.count),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(text = money(slice.amount, currency), style = ZillitTheme.typography.numeric)
                }
                InsightBar(fraction = pct / PERCENT.toFloat(), color = tint.copy(alpha = 0.8f), height = 8.dp)
            }
        }
        ZillitDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            InsightEyebrow(str(S.asset_total), modifier = Modifier.weight(1f))
            ZillitText(
                text = money(total, currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.warning,
            )
        }
    }
}

@Composable
private fun departmentPalette(): List<Color> {
    val colors = ZillitTheme.colors
    return listOf(colors.gold, colors.teal, colors.info, colors.danger, colors.violet, colors.warning)
}

/** "Spend by Card Holder" (`AnalyticsPage.jsx:245-311`). */
@Suppress("LongMethod") // One row: avatar, name, card, count, and the three card figures with their bar.
@Composable
private fun HolderCard(state: CardUiState, slices: List<AnalyticsSlice>, currency: String?) {
    ZillitSectionCard(title = str(S.ah_spend_by_card_holder), icon = ZillitIcons.User) {
        if (slices.isEmpty()) {
            EmptyLine(str(S.desktop_ce_insights_no_holder_data))
            return@ZillitSectionCard
        }
        slices.forEachIndexed { index, slice ->
            if (index > 0) ZillitDivider()
            val limit = slice.cardLimit ?: 0.0
            val balance = slice.balance ?: 0.0
            val spent = max(limit - balance, 0.0)
            val code = slice.currency ?: currency
            val holder = holderLabel(state, slice.userId)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ZillitAvatar(name = holder, userId = slice.userId, size = 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ZillitText(text = holder, style = ZillitTheme.typography.bodySmall, maxLines = 1)
                                slice.cardLastFour?.let {
                                    ZillitText(
                                        text = "••••$it",
                                        style = ZillitTheme.typography.labelSmall,
                                        color = ZillitTheme.colors.textMuted,
                                    )
                                }
                            }
                            ZillitText(
                                text = listOf(
                                    receiptCount(slice.count),
                                    departmentName(state, slice.departmentId),
                                ).joinToString(" · "),
                                style = ZillitTheme.typography.bodySmall,
                                color = ZillitTheme.colors.textSecondary,
                            )
                        }
                        if (limit > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                                MiniFigure(money(limit, code), str(S.desktop_issued), ZillitTheme.colors.textPrimary)
                                MiniFigure(
                                    money(spent, code),
                                    str(S.ah_spent_label),
                                    if (spent > 0) ZillitTheme.colors.warning else ZillitTheme.colors.textMuted,
                                )
                                MiniFigure(
                                    money(balance, code),
                                    str(S.ah_balance_label),
                                    if (balance >= 0) ZillitTheme.colors.success else ZillitTheme.colors.danger,
                                )
                            }
                        }
                    }
                    if (limit > 0) InsightBar((spent / limit).toFloat(), ZillitTheme.colors.warning, height = 6.dp)
                }
            }
        }
    }
}

/** "Full Name (Designation)"; a holder nobody can name reads "Unknown", never an id. */
private fun holderLabel(state: CardUiState, userId: String?): String {
    val person = state.people.firstOrNull { it.id == userId } ?: return str(S.desktop_unknown)
    return AlertText.personLabel(person)
}

@Composable
private fun MiniFigure(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitText(
            text = value,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
        )
        InsightEyebrow(label)
    }
}

/** "Cash Flow & Forecast": W1–W4, actual beside projected (`AnalyticsPage.jsx:318-373`). */
@Composable
private fun CashFlowCard(totalSpend: Double, postedTotal: Double, currency: String?) {
    val weeks = AnalyticsMath.weeks(totalSpend, postedTotal)
    val top = weeks.maxOf { max(it.actual ?: 0.0, it.projected) }.takeIf { it > 0 } ?: 1.0
    val colors = ZillitTheme.colors
    ZillitSectionCard(title = str(S.desktop_ce_insights_cash_flow), icon = ZillitIcons.BarChart) {
        Row(
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.Bottom,
        ) {
            weeks.forEach { week -> WeekBars(week, top, Modifier.weight(1f)) }
        }
        ZillitDivider()
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.lg, Alignment.CenterHorizontally)) {
            Legend(str(S.desktop_cr_actual), colors.teal.copy(alpha = 0.8f), dashed = false)
            Legend(str(S.desktop_projected), colors.gold.copy(alpha = 0.15f), dashed = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitStatTile(
                label = str(S.desktop_ce_insights_month_to_date),
                value = money(postedTotal, currency),
                tone = StatusTone.Done,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_ce_insights_month_forecast),
                value = money(totalSpend, currency),
                tone = StatusTone.Pending,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WeekBars(week: CashFlowWeek, top: Double, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val actual = week.actual
            if (actual != null) {
                Box(
                    Modifier.weight(1f).fillMaxHeight((actual / top).toFloat().coerceIn(0f, 1f))
                        .clip(BAR_TOP).background(colors.teal.copy(alpha = 0.8f)),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                Modifier.weight(1f).fillMaxHeight((week.projected / top).toFloat().coerceIn(0f, 1f))
                    .clip(BAR_TOP).background(colors.gold.copy(alpha = 0.15f)).dashedBorder(colors.gold),
            )
        }
        ZillitText(
            text = week.label,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, DASH))),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
}

@Composable
private fun Legend(label: String, fill: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.width(12.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(fill)
                .then(if (dashed) Modifier.dashedBorder(ZillitTheme.colors.gold) else Modifier),
        )
        ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
    }
}

/** "Cost Report Impact" — budget vs actual per phase (`AnalyticsPage.jsx:375-448`). */
@Suppress("LongMethod") // Three phases and the totals row under them.
@Composable
private fun CostImpactCard(totalSpend: Double, postedTotal: Double, currency: String?) {
    val phases = AnalyticsMath.phases(totalSpend, postedTotal)
    val colors = ZillitTheme.colors
    ZillitSectionCard(
        title = str(S.desktop_ce_insights_cost_report_impact),
        icon = ZillitIcons.Wallet,
        meta = str(S.desktop_ce_insights_budget_vs_actual),
    ) {
        phases.forEach { phase ->
            val bar = when {
                phase.over -> colors.danger
                phase.percent > NEAR_BUDGET -> colors.warning
                else -> colors.teal
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    ZillitText(
                        text = phaseName(phase.phase),
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = "${phase.percent}%",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                InsightBar(
                    fraction = if (phase.budget > 0) (phase.actual / phase.budget).toFloat() else 0f,
                    color = bar,
                    height = 8.dp,
                )
                Row {
                    ZillitText(
                        text = str(S.desktop_cr_budget_amount, money(phase.budget, currency)),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = str(S.desktop_ce_insights_actual_x, money(phase.actual, currency)),
                        style = ZillitTheme.typography.bodySmall,
                        color = if (phase.over) colors.danger else colors.teal,
                    )
                }
            }
        }
        ZillitDivider()
        val budget = phases.sumOf { it.budget }
        val actual = phases.sumOf { it.actual }
        Row(Modifier.fillMaxWidth()) {
            TotalFigure(str(S.cr_kpi_total_budget), money(budget, currency), colors.textMuted, Modifier.weight(1f))
            TotalFigure(
                str(S.desktop_ce_insights_total_actual),
                money(actual, currency),
                colors.teal,
                Modifier.weight(1f),
            )
            TotalFigure(str(S.ah_lbl_remaining), money(budget - actual, currency), colors.warning, Modifier.weight(1f))
        }
    }
}

private fun phaseName(phase: BudgetPhaseName): String = when (phase) {
    BudgetPhaseName.PreProduction -> str(S.pre_production)
    BudgetPhaseName.Production -> str(S.production)
    BudgetPhaseName.PostProduction -> str(S.desktop_ce_insights_post_production)
}

@Composable
private fun TotalFigure(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InsightEyebrow(label)
        ZillitText(text = value, style = ZillitTheme.typography.numeric, color = color)
    }
}

/** "Processing Performance" — five figures across the foot (`AnalyticsPage.jsx:451-508`). */
@Composable
private fun PerformanceCard(analytics: CardAnalytics) {
    val colors = ZillitTheme.colors
    ZillitSectionCard(title = str(S.ah_processing_performance), icon = ZillitIcons.Clock) {
        Row(Modifier.fillMaxWidth().height(PERFORMANCE_HEIGHT)) {
            val cells = listOf(
                Triple(S.ah_import_to_coded, days(analytics.avgImportToCoded), colors.warning)
                    to S.desktop_ce_insights_avg_crew_response,
                Triple(S.ah_coded_to_approved, days(analytics.avgCodedToApproved), colors.info)
                    to S.desktop_ce_insights_avg_approval_time,
                Triple(S.ah_approved_to_posted, days(analytics.avgApprovedToPosted), colors.success)
                    to S.desktop_ce_insights_avg_post_time,
                Triple(S.ah_receipts_missing, percent(analytics.receiptsMissingPct), colors.danger)
                    to S.desktop_ce_insights_at_import_time,
                Triple(S.ah_auto_reconciled, percent(analytics.autoReconciledPct), colors.success)
                    to S.desktop_ce_insights_no_crew_action,
            )
            cells.forEachIndexed { index, (head, caption) ->
                if (index > 0) ZillitVerticalDivider()
                Column(
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InsightEyebrow(str(head.first))
                    ZillitText(
                        text = head.second,
                        style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = head.third,
                    )
                    ZillitText(
                        text = str(caption),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun days(value: Double): String = str(S.desktop_ce_insights_days_x, figure(value))

private fun percent(value: Double): String = str(S.desktop_ce_insights_percent_x, figure(value))

/** A figure as the web prints `{x || 0}`: whole numbers without a decimal point. */
private fun figure(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun EmptyLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
    )
}

// == Smart Alerts ===============================================================

/**
 * Smart alerts — the web's `SmartAlertsPage.jsx:198-354`, a card list.
 *
 * Four tiles, the type chips, then one card per alert: its severity down the
 * left edge, its tags, the transactions it points at, the resolution once
 * there is one, and the three actions while it is open. Crew ids the engine
 * bakes into the prose read as "Name (Designation)"; anything left that
 * looks like an id reads as "an unknown user".
 */
@Composable
fun AlertsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val alerts = state.alerts
    val filter = state.insights.alertFilter
    val visible = filter.apply(alerts)
    val open = alerts.count { it.isOpen }
    val resolved = alerts.filter { it.status == CardAlert.RESOLVED }
    val loading = state.loading && alerts.isEmpty()

    Box(Modifier.fillMaxSize()) {
        ScrollingPage {
            AlertTiles(state, alerts, open, resolved.size, loading)
            InsightChips(
                options = AlertFilter.entries,
                selected = filter,
                label = ::filterLabel,
                onSelect = { onEvent(InsightsEvent.FilterAlerts(it)) },
            )
            if (loading) {
                Row(Modifier.fillMaxWidth(), Arrangement.Center) { ZillitSpinner() }
            } else {
                visible.forEach { alert -> AlertCard(state, alert, onEvent) }
                if (visible.isEmpty()) {
                    EmptyLine(
                        if (filter == AlertFilter.All) {
                            str(S.desktop_ce_insights_all_clear)
                        } else {
                            str(S.desktop_ce_insights_no_alerts_match)
                        },
                    )
                }
                if (filter == AlertFilter.All && resolved.isNotEmpty()) RecentlyResolved(state, resolved)
                ZillitText(
                    text = str(S.desktop_ce_insights_alerts_footer),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        ResolveDialog(state, onEvent)
    }
}

@Composable
private fun AlertTiles(state: CardUiState, alerts: List<CardAlert>, open: Int, resolved: Int, loading: Boolean) {
    val rate = if (alerts.isNotEmpty()) (open.toDouble() / alerts.size * PERCENT) else 0.0
    val savings = alerts.sumOf { it.savings ?: 0.0 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        val tile = Modifier.weight(1f)
        ZillitStatTile(
            label = str(S.desktop_ce_insights_active_alerts),
            value = if (loading) "—" else open.toString(),
            sub = str(S.desktop_ce_insights_needs_review),
            tone = StatusTone.Rejected,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_ce_insights_resolved_7d),
            value = if (loading) "—" else resolved.toString(),
            sub = str(S.desktop_ce_insights_this_session),
            tone = StatusTone.Done,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_ce_insights_alert_rate),
            value = if (loading) "—" else "${oneDecimal(rate)}%",
            sub = str(S.desktop_ce_insights_of_transactions),
            tone = StatusTone.Pending,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_ce_insights_savings_found),
            value = if (loading) "—" else money(savings, state.currency),
            sub = str(S.desktop_ce_insights_caught_this_period),
            tone = StatusTone.Ready,
            modifier = tile,
        )
    }
}

/** `toFixed(1)`. */
private fun oneDecimal(value: Double): String {
    val tenths = (value * TENTHS).roundToInt()
    return "${tenths / TENTHS.toInt()}.${tenths % TENTHS.toInt()}"
}

private fun filterLabel(filter: AlertFilter): String = when (filter) {
    AlertFilter.All -> str(S.all)
    AlertFilter.Anomaly -> str(S.ah_alert_filter_anomaly)
    AlertFilter.Duplicate -> str(S.ah_alert_filter_duplicate)
    AlertFilter.Velocity -> str(S.ah_alert_filter_velocity)
    AlertFilter.Merchant -> str(S.ah_merchant)
    AlertFilter.Resolved -> str(S.ah_alert_filter_resolved)
}

@Composable
private fun severityColor(severity: AlertSeverity): Color = when (severity) {
    AlertSeverity.High -> ZillitTheme.colors.danger
    AlertSeverity.Medium -> ZillitTheme.colors.warning
    AlertSeverity.Low -> ZillitTheme.colors.violet
}

private fun priorityLabel(severity: AlertSeverity): String = when (severity) {
    AlertSeverity.High -> str(S.desktop_ce_insights_high_priority)
    AlertSeverity.Medium -> str(S.medium)
    AlertSeverity.Low -> str(S.info)
}

@Suppress("LongMethod") // One alert card: header, prose, transactions, resolution, actions.
@Composable
private fun AlertCard(state: CardUiState, alert: CardAlert, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val severity = severityColor(alert.severity)
    val unknown = str(S.desktop_ce_insights_an_unknown_user)
    // The severity runs down the left edge (`borderLeft: 3px solid`), drawn
    // rather than laid out so the card needs no intrinsic height.
    Row(
        Modifier.fillMaxWidth().insightCard(colors.surface, colors.border)
            .drawBehind { drawRect(color = severity, size = Size(EDGE.dp.toPx(), size.height)) },
    ) {
        Column(
            Modifier.weight(1f).padding(horizontal = 18.dp, vertical = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitIcon(
                    icon = if (alert.severity == AlertSeverity.Low) ZillitIcons.Info else ZillitIcons.Warning,
                    tint = severity,
                    size = 18.dp,
                )
                ZillitText(
                    text = AlertText.scrub(alert.title.ifBlank { alert.type.orEmpty() }, state.people, unknown),
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = severity,
                    maxLines = 2,
                )
                InsightTag(priorityLabel(alert.severity), severity.copy(alpha = 0.12f), severity)
                val (statusLabel, statusColor) = statusStyle(alert.status)
                InsightTag(statusLabel, statusColor.copy(alpha = 0.12f), statusColor)
                alert.savings?.takeIf { it > 0 }?.let {
                    InsightTag(
                        str(S.desktop_ce_insights_x_savings, money(it, state.currency)),
                        colors.tealSoft,
                        colors.teal,
                    )
                }
                Spacer(Modifier.weight(1f))
                ZillitText(
                    text = str(S.desktop_ce_insights_detected_x, detectedAt(alert.at)),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            ZillitText(
                text = AlertText.scrub(alert.description, state.people, unknown),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            if (alert.relatedTxns.isNotEmpty()) RelatedTxns(state, alert.relatedTxns, severity)
            val resolution = alert.resolution
            if (resolution != null && alert.status == CardAlert.RESOLVED) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.tealSoft)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    InsightEyebrow(str(S.desktop_ce_insights_resolution), color = colors.teal)
                    ZillitText(
                        text = AlertText.scrub(resolution, state.people, unknown),
                        style = ZillitTheme.typography.bodySmall,
                    )
                }
            }
            if (alert.isOpen && state.viewer.isAccountant) AlertActions(state, alert, onEvent)
        }
    }
}

@Composable
private fun statusStyle(status: String): Pair<String, Color> {
    val colors = ZillitTheme.colors
    return when (status) {
        CardAlert.ACTIVE -> str(S.active) to colors.danger
        CardAlert.INVESTIGATING -> str(S.desktop_investigating) to colors.warning
        CardAlert.RESOLVED -> str(S.ah_alert_filter_resolved) to colors.teal
        CardAlert.DISMISSED -> str(S.ah_dismissed_toast) to colors.textMuted
        AUTO_CLOSED -> str(S.desktop_card_auto_closed) to colors.textMuted
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() } to colors.textMuted
    }
}

/** `dd Mon yyyy · HH:mm` (`formatTs`, `SmartAlertsPage.jsx:177-187`). */
private fun detectedAt(millis: Long?): String {
    val day = EpochDate.date(millis).replace(",", "")
    if (day.isEmpty()) return "—"
    val iso = EpochDate.dateTime(millis).substringAfter("| ")
    return "$day · ${twentyFourHour(iso)}"
}

/** `4:35 PM` → `16:35`. */
private fun twentyFourHour(twelve: String): String {
    val (clock, meridiem) = twelve.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
    val hour = clock.substringBefore(':').toIntOrNull() ?: return twelve
    val minute = clock.substringAfter(':')
    val h24 = when {
        meridiem == "PM" && hour != NOON -> hour + NOON
        meridiem == "AM" && hour == NOON -> 0
        else -> hour
    }
    return "${h24.toString().padStart(2, '0')}:$minute"
}

@Composable
private fun RelatedTxns(state: CardUiState, txns: List<AlertTxn>, severity: Color) {
    val colors = ZillitTheme.colors
    txns.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { txn ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(colors.surfaceSunken)
                        .border(1.dp, severity.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ZillitText(
                        text = txn.merchant ?: "—",
                        style = ZillitTheme.typography.titleSmall,
                    )
                    val meta = listOfNotNull(
                        txn.ref?.takeIf { !AlertText.isIdLike(it) },
                        txn.holder?.let { AlertText.user(it, state.people, str(S.desktop_ce_insights_unknown_user)) },
                        txn.amount?.let { money(it, txn.currency ?: state.currency) },
                    )
                    if (meta.isNotEmpty()) {
                        ZillitText(
                            text = meta.joinToString(" · "),
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
            if (pair.size == 1 && txns.size > 1) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Investigate (or the "Under Investigation" pill once it is), Resolve and
 * Dismiss — on the press, no confirmation (`SmartAlertsPage.jsx:283-322`).
 */
@Composable
private fun AlertActions(state: CardUiState, alert: CardAlert, onEvent: (CardEvent) -> Unit) {
    val busy = state.insights.alertBusy[alert.id]
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (alert.status == CardAlert.ACTIVE) {
            ZillitButton(
                text = if (busy == AlertAction.Investigate) {
                    str(S.desktop_ce_insights_investigating_ellipsis)
                } else {
                    str(S.desktop_card_investigate)
                },
                onClick = { onEvent(InsightsEvent.Investigate(alert.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Search,
                enabled = busy == null,
                loading = busy == AlertAction.Investigate,
            )
        } else {
            InsightTag(str(S.desktop_ce_insights_under_investigation), colors.warningSoft, colors.warning, dot = true)
        }
        ZillitButton(
            text = if (busy == AlertAction.Resolve) str(S.desktop_inv_resolving) else str(S.desktop_resolve),
            onClick = { onEvent(InsightsEvent.OpenResolve(alert.id)) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Check,
            enabled = busy == null,
        )
        ZillitButton(
            text = if (busy == AlertAction.Dismiss) str(S.desktop_br_dismissing) else str(S.sync_action_dismiss),
            onClick = { onEvent(InsightsEvent.Dismiss(alert.id)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = busy == null,
        )
    }
}

/** "Recently Resolved (N)" — the last five, when the filter is All (`SmartAlertsPage.jsx:329-342`). */
@Composable
private fun RecentlyResolved(state: CardUiState, resolved: List<CardAlert>) {
    val unknown = str(S.desktop_ce_insights_an_unknown_user)
    ZillitSectionCard(
        title = str(S.desktop_ce_insights_recently_resolved, resolved.size),
        icon = ZillitIcons.Check,
        padded = false,
    ) {
        resolved.take(RECENT).forEachIndexed { index, alert ->
            if (index > 0) ZillitDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InsightTag(str(S.ah_alert_filter_resolved), ZillitTheme.colors.tealSoft, ZillitTheme.colors.teal)
                ZillitText(
                    text = AlertText.scrub(alert.title, state.people, unknown),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = detectedAt(alert.at),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

/** "Resolve Alert": the alert, an optional note, "Confirm Resolution" (`SmartAlertsPage.jsx:359-388`). */
@Composable
private fun ResolveDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.insights.resolve ?: return
    val alert = state.alerts.firstOrNull { it.id == draft.alertId } ?: return
    val unknown = str(S.desktop_ce_insights_an_unknown_user)
    val resolving = state.insights.alertBusy[alert.id] == AlertAction.Resolve
    ZillitDialogShell(
        title = str(S.desktop_ce_insights_resolve_alert),
        icon = ZillitIcons.Check,
        visible = true,
        width = RESOLVE_WIDTH,
        onDismiss = { onEvent(InsightsEvent.OpenResolve(null)) },
        actions = {
            ZillitButton(
                text = if (resolving) str(S.desktop_inv_resolving) else str(S.desktop_ce_insights_confirm_resolution),
                onClick = { onEvent(InsightsEvent.ConfirmResolve) },
                enabled = !resolving,
                loading = resolving,
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ZillitTheme.colors.surfaceSunken)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitText(
                text = AlertText.scrub(alert.title, state.people, unknown),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitText(
                text = AlertText.scrub(alert.description, state.people, unknown),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitTextField(
            value = draft.note,
            onValueChange = { onEvent(InsightsEvent.EditResolveNote(it)) },
            label = str(S.desktop_ce_insights_resolution_note),
            placeholder = str(S.desktop_ce_insights_resolution_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val PERCENT = 100.0
private const val CENTS = 100.0
private const val DAYS = 30.0
private const val VAT_DIVISOR = 6.0
private const val MEDIAN_FACTOR = 0.6
private const val NEAR_BUDGET = 80
private const val TENTHS = 10.0
private const val NOON = 12
private const val RECENT = 5
private const val DASH = 8f
private const val AUTO_CLOSED = "auto_closed"
private val CHART_HEIGHT = 160.dp
private val PERFORMANCE_HEIGHT = 110.dp
private val RESOLVE_WIDTH = 520.dp
private val BAR_TOP = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
private const val EDGE = 3
