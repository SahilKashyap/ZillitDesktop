package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoCashFlow
import com.zillit.desktop.feature.purchaseorder.domain.PoCashWeek
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoQueueScope
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState

/**
 * The accountant's Queue — the web's `POQueue`, with its two halves.
 *
 * "My Queue" is what the assignment rules routed to this accountant; "All
 * Queue" is every order in flight on the production. Each half carries its
 * count on its tab, the five cards count the half on screen, and a senior sees
 * the six-week cash-flow forecast under them — all three the web's.
 */
@Composable
internal fun PoQueuePage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.rows
    // Both halves, whichever is open: the tabs count both, and the forecast
    // reads everything in flight, as the web's `pos` does.
    val mine = remember(state) { state.copy(queueScope = PoQueueScope.Mine).rows }
    val everything = remember(state) { state.copy(queueScope = PoQueueScope.All).rows }
    Box(modifier = Modifier.fillMaxSize()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitTabStrip(
                tabs = PoQueueScope.entries.map { scope ->
                    val count = if (scope == PoQueueScope.Mine) mine.size else everything.size
                    ZillitTab(scope.slug, scope.label, count = count)
                },
                activeId = state.queueScope.slug,
                onSelect = { slug ->
                    PoQueueScope.entries.firstOrNull { it.slug == slug }?.let { onEvent(PoEvent.OpenQueue(it)) }
                },
            )
            QueueStats(state, rows)
            if (state.viewer.isSeniorAccountant) CashFlowForecast(state, everything)
            if (state.showsFilters) PoFilterRow(state, onEvent)
            ZillitSectionCard(
                title = if (state.queueScope == PoQueueScope.Mine) {
                    str(S.desktop_my_queue)
                } else {
                    str(S.desktop_all_queue)
                },
                icon = ZillitIcons.Clock,
                meta = str(
                    if (rows.size == 1) S.desktop_po_order_count_one else S.desktop_po_order_count_other,
                    rows.size,
                ),
                padded = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PoOrderTable(state, rows, onEvent)
            }
        }
        PoBulkBar(state, onEvent, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The five cards, counted off the half on screen — the web's `statsSource`:
 * All POs (a senior's word for it) or My POs, Pending, Ready to process
 * (approved or Acct Entered), Assigned Depts, and My Committed.
 */
@Composable
private fun QueueStats(state: PoUiState, rows: List<PurchaseOrder>) {
    val pending = rows.count { it.status == PoStatus.AwaitingApproval }
    val ready = rows.count {
        it.status == PoStatus.Approved || it.status == PoStatus.Queued || it.status == PoStatus.AccountsEntered
    }
    // A senior covers every department by role — the web's "All (override)".
    val departments = if (state.viewer.isSeniorAccountant) {
        str(S.desktop_po_all_override)
    } else {
        rows.mapNotNull { state.departmentName(it.departmentId).ifBlank { null } }.distinct().joinToString(", ")
            .ifBlank { "—" }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitStatTile(
            label = if (state.viewer.isSeniorAccountant) str(S.ah_tab_all) else str(S.ah_tab_my),
            value = rows.size.toString(),
            tone = StatusTone.Pending,
            icon = ZillitIcons.File,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.pending),
            value = pending.toString(),
            sub = str(S.desktop_po_waiting_on_an_approval),
            icon = ZillitIcons.Clock,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_ready_to_process),
            value = ready.toString(),
            sub = str(S.desktop_po_approved_and_yours_to_code),
            tone = StatusTone.Done,
            icon = ZillitIcons.Ledger,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_po_assigned_depts),
            value = departments,
            icon = ZillitIcons.Users,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_po_my_committed),
            value = rows.totalValue(state),
            sub = rows.currencyNote(),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Wallet,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The next six weeks of expected payments, confirmed (posted) stacked under
 * committed (approved or Acct Entered) — the web's accountant-only forecast.
 */
@Composable
private fun CashFlowForecast(state: PoUiState, orders: List<PurchaseOrder>) {
    val today = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    val weeks = remember(orders) { PoCashFlow.weeks(orders, today) }
    val peak = weeks.maxByOrNull { it.totalK }?.takeIf { it.totalK > 0 }
    val symbol = Money.symbol(state.rates.defaultCode ?: state.currencies.firstOrNull())
    ZillitSectionCard(
        title = str(S.desktop_cash_flow_forecast),
        icon = ZillitIcons.BarChart,
        meta = str(S.desktop_po_cash_flow_basis),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val highest = (weeks.maxOfOrNull { it.totalK } ?: 0L).coerceAtLeast(1L)
        Row(
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            weeks.forEach { week -> WeekBar(week, highest, symbol, Modifier.weight(1f)) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            LegendSwatch(ZillitTheme.colors.accent, str(S.desktop_po_cash_confirmed))
            LegendSwatch(ZillitTheme.colors.warning, str(S.desktop_po_cash_committed))
            Spacer(Modifier.weight(1f))
            ZillitText(
                text = str(S.desktop_po_cash_six_week_total, "$symbol${weeks.sumOf { it.totalK }}k") +
                    (peak?.let { " · " + str(S.desktop_po_cash_peak, it.index) } ?: ""),
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun WeekBar(week: PoCashWeek, highest: Long, symbol: String, modifier: Modifier) {
    val fraction = week.totalK.toFloat() / highest.toFloat()
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs, Alignment.Bottom),
    ) {
        ZillitText(
            text = if (week.totalK > 0) "$symbol${week.totalK}k" else "—",
            style = ZillitTheme.typography.numeric,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        if (week.totalK > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_MAX * fraction)
                    .clip(RoundedCornerShape(topStart = BAR_RADIUS, topEnd = BAR_RADIUS)),
            ) {
                if (week.committedK > 0) {
                    Box(
                        Modifier.fillMaxWidth().weight(week.committedK.toFloat())
                            .background(ZillitTheme.colors.warning),
                    )
                }
                if (week.confirmedK > 0) {
                    Box(
                        Modifier.fillMaxWidth().weight(week.confirmedK.toFloat())
                            .background(ZillitTheme.colors.accent),
                    )
                }
            }
        }
        ZillitText(
            text = str(S.desktop_cr_week_short, week.index),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(Modifier.size(SWATCH).clip(RoundedCornerShape(SWATCH_RADIUS)).background(color))
        ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
    }
}

private val CHART_HEIGHT = 170.dp
private val BAR_MAX = 110.dp
private val BAR_RADIUS = 6.dp
private val SWATCH = 12.dp
private val SWATCH_RADIUS = 3.dp
