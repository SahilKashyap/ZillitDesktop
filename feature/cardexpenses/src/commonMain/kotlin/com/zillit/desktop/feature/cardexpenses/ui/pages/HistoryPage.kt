package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ProcessMode

/**
 * History — the web's `HistoryPage`: every posted receipt in one card, and
 * a row opens the process editor in its save-only History mode (no second
 * post, no top-up).
 */
@Composable
fun HistoryPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.receipts.filter { it.matchesHistory(state, state.search) }

    FixedPage {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(CardEvent.Search(it)) },
            placeholder = str(S.desktop_ce_process_search_history),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!state.loading && rows.isEmpty()) {
            ZillitText(
                text = str(S.desktop_pc_no_receipts_found),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
            )
            return@FixedPage
        }
        ZillitSectionCard(
            title = str(S.history),
            icon = ZillitIcons.Clock,
            meta = if (rows.size == 1) {
                str(S.desktop_card_receipt_count_one, rows.size)
            } else {
                str(S.desktop_card_receipt_count_other, rows.size)
            },
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = historyColumns(state),
                key = { it.id },
                loading = state.loading,
                onRowClick = { onEvent(CardEvent.OpenProcess(it.id, ProcessMode.History)) },
            )
        }
    }
}

/** Description, amount, holder or code — the web's search (`HistoryPage.jsx:80-88`). */
private fun CardReceipt.matchesHistory(state: CardUiState, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    val figure = if (amount == kotlin.math.floor(amount)) amount.toLong().toString() else amount.toString()
    val holder = state.people.firstOrNull { it.id == holderId }?.name.orEmpty()
    return description.lowercase().contains(needle) ||
        figure.contains(needle) ||
        holder.lowercase().contains(needle) ||
        nominalCode?.lowercase()?.contains(needle) == true
}

@Suppress("MagicNumber") // Column proportions.
private fun historyColumns(state: CardUiState): List<TableColumn<CardReceipt>> = listOf(
    TableColumn(str(S.date), ColumnWidth.Fixed(110.dp)) { DateCell(it) },
    TableColumn(str(S.ah_receipt_details), ColumnWidth.Weight(2.2f)) {
        ReceiptDetailsCell(state, it, lastFour = it.transactionCardLastFour, urgent = false)
    },
    TableColumn(str(S.desktop_card_card_holder), ColumnWidth.Weight(1.2f)) {
        HolderCell(state, it.holderId, it.holderName)
    },
    TableColumn(str(S.code), ColumnWidth.Fixed(110.dp)) { CodeCell(it.nominalCode, null, television = false) },
    TableColumn(str(S.amount), ColumnWidth.Fixed(120.dp), numeric = true) { AmountCell(it) },
    TableColumn(str(S.status), ColumnWidth.Fixed(120.dp)) { row ->
        // "Posted", teal, for a posted row — or one with no status at all.
        val posted = row.status == CardWorkflowStatus.Posted || row.status == CardWorkflowStatus.Unknown
        ZillitStatusPill(
            label = if (posted) str(S.ah_status_posted) else row.status.label,
            tone = if (posted) StatusTone.Ready else StatusTone.Neutral,
        )
    },
)
