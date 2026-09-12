package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoQueueScope
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState

/**
 * The accountant's Queue — the web's `POQueue`, with its two halves.
 *
 * "My Queue" is what the assignment rules routed to this accountant; "All
 * Queue" is every order in flight on the production. The four cards above are
 * the web's, and the fourth is the one that makes the tab useful: how many
 * orders are ready to be processed right now.
 */
@Composable
internal fun PoQueuePage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.rows
    Box(modifier = Modifier.fillMaxSize()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitTabStrip(
                tabs = PoQueueScope.entries.map { ZillitTab(it.slug, it.label) },
                activeId = state.queueScope.slug,
                onSelect = { slug ->
                    PoQueueScope.entries.firstOrNull { it.slug == slug }?.let { onEvent(PoEvent.OpenQueue(it)) }
                },
            )
            QueueStats(state, onEvent)
            if (state.showsFilters) PoFilterRow(state, onEvent)
            ZillitSectionCard(
                title = if (state.queueScope == PoQueueScope.Mine) "My Queue" else "All Queue",
                icon = ZillitIcons.Clock,
                meta = "${rows.size} order${if (rows.size == 1) "" else "s"}",
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
 * The four cards: Pending, Ready to process, My Committed, Assigned Depts.
 *
 * "Ready to process" is counted through [PoAccess.canProcess] rather than by
 * status alone, so it means what the button under it will actually let this
 * person do — an approver who is not the assignee sees zero, which is correct.
 */
@Composable
private fun QueueStats(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.rows
    val pending = rows.count { it.status == PoStatus.AwaitingApproval }
    val ready = rows.count { PoAccess.canProcess(it, state.viewer) }
    val mine = rows.filter { it.assignedTo == state.viewer.userId && it.status.isCommitted }
    val departments = rows.mapNotNull { it.departmentId }.distinct().size
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitStatTile(
            label = "Pending",
            value = pending.toString(),
            sub = "Waiting on an approval",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Clock,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Ready to process",
            value = ready.toString(),
            sub = "Approved and yours to code",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Ledger,
            onClick = { onEvent(PoEvent.Open(com.zillit.desktop.feature.purchaseorder.ui.PoDestination.Entry)) },
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "My Committed",
            value = mine.totalValue(),
            sub = mine.currencyNote() ?: "Assigned to you",
            tone = StatusTone.Done,
            icon = ZillitIcons.Wallet,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Assigned Depts",
            value = departments.toString(),
            sub = "Departments in this queue",
            icon = ZillitIcons.Users,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Spend on a set of orders, as the cards show it. */
internal fun committedTotal(state: PoUiState): String =
    Money.format(state.rows.filter { it.status.isCommitted }.sumOf { it.gross }, state.currencies.firstOrNull())
