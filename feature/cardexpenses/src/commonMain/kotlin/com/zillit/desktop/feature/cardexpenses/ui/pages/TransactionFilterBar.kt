package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.cardLabel
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * All Transactions' five tiles (`AllTransactionsPage.jsx:321-326`): total, new,
 * in approval, posted, and what the lines come to — over the rows the server
 * returned for the filters.
 */
@Composable
internal fun TransactionTiles(state: CardUiState) {
    val rows = state.transactions
    val currency = rows.firstNotNullOfOrNull { it.currency } ?: state.currency
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitStatTile(str(S.asset_total), rows.size.toString(), Modifier.weight(1f))
        ZillitStatTile(
            label = str(S.ah_txn_filter_new),
            value = rows.count { it.status == CardWorkflowStatus.New }.toString(),
            tone = StatusTone.Pending,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.desktop_in_approval),
            value = rows.count { it.status == CardWorkflowStatus.InApproval }.toString(),
            tone = StatusTone.Progress,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_status_posted),
            value = rows.count { it.status == CardWorkflowStatus.Posted }.toString(),
            tone = StatusTone.Done,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = str(S.ah_addl_value_hint),
            value = money(rows.sumOf { it.amount }, currency),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The server-side filters (`transactionQuery.js`): statement, card, department
 * and a date window, applied on a press — a half-typed date would otherwise
 * fetch a window nobody asked for. Reset clears every one, including the month
 * the page opens on.
 */
@Suppress("LongMethod") // One bar: three pickers, two dates and the two buttons.
@Composable
internal fun TransactionFilterBar(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val applied = state.transactionFilters
    var draft by remember(applied) { mutableStateOf(applied) }
    val departments = state.people
        .filter { it.departmentId.isNotBlank() }
        .distinctBy { it.departmentId }
        .map { it.departmentId to it.department.ifBlank { it.departmentId } }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitSelect(
            value = state.imports.firstOrNull { it.id == draft.statementId },
            options = state.imports,
            onSelect = { draft = draft.copy(statementId = it?.id.orEmpty()) },
            label = { it?.filename ?: it?.id ?: str(S.desktop_card_all_statements) },
            modifier = Modifier.width(PICKER_WIDTH),
        )
        ZillitSelect(
            value = state.cards.firstOrNull { it.id == draft.cardId },
            options = state.cards,
            onSelect = { draft = draft.copy(cardId = it?.id.orEmpty()) },
            label = { card ->
                card?.let { "${cardLabel(it)} · ${state.holderShortName(it)}" } ?: str(S.desktop_card_all_cards)
            },
            modifier = Modifier.width(PICKER_WIDTH),
        )
        ZillitSelect(
            value = departments.firstOrNull { it.first == draft.departmentId },
            options = departments,
            onSelect = { draft = draft.copy(departmentId = it?.first.orEmpty()) },
            label = { it?.second ?: str(S.invitees_tab_all_depts) },
            modifier = Modifier.width(PICKER_WIDTH),
        )
        ZillitDateField(
            value = draft.from,
            onValueChange = { draft = draft.copy(from = it) },
            label = str(S.fromText),
            modifier = Modifier.weight(1f),
        )
        ZillitDateField(
            value = draft.to,
            onValueChange = { draft = draft.copy(to = it) },
            label = str(S.toText),
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.dm_filter_apply),
            onClick = { onEvent(CardEvent.SetTransactionFilters(draft)) },
            size = ButtonSize.Small,
            enabled = draft != applied && !state.loading,
        )
        ZillitButton(
            text = str(S.desktop_reset_all),
            onClick = { onEvent(CardEvent.SetTransactionFilters(TransactionFilters())) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = applied.count > 0,
        )
    }
}

private val PICKER_WIDTH = 190.dp
