package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.ReceiptBadgeRule
import com.zillit.desktop.feature.cardexpenses.ui.components.ReceiptDetailView

/**
 * The reconciliation pages' dialogs, drawn at the screen root with the rest —
 * `ZillitDialogShell` is not a popup, and inside a page it would cover only
 * the page. Drawn before the query dialog, so a Query raised from the receipt
 * detail opens on top of it.
 */
@Composable
fun InboxDialogs(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    when (state.destination) {
        CardDestination.ReceiptInbox -> {
            ReceiptDetailDialog(state, onEvent)
            ManualMatchDialog(state, onEvent)
        }

        CardDestination.ImportStatement -> ImportStatementDialog(state, onEvent)
        CardDestination.AllTransactions -> {
            DeleteDialog(state, onEvent)
            BulkDeleteDialog(state, onEvent)
        }

        else -> Unit
    }
}

/** The inbox's receipt detail: the reconciliation badge, and Query for the accountant. */
@Composable
private fun ReceiptDetailDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val open = state.inbox.detail ?: return
    ReceiptDetailView(
        detail = open.detail,
        people = state.people,
        loading = open.loading,
        badgeRule = ReceiptBadgeRule.Inbox,
        isTelevision = state.viewer.isTelevision,
        media = open.media,
        onOpenMedia = { onEvent(CardEvent.ViewReceipt(it.key)) },
        history = open.history,
        onClose = { onEvent(InboxEvent.CloseDetail) },
        onShowHistory = { onEvent(InboxEvent.ShowHistory) },
        onHideHistory = { onEvent(InboxEvent.HideHistory) },
        onQuery = { onEvent(CardEvent.OpenQuery(open.receiptId)) },
    )
}

/** "Delete Transaction?" (`AllTransactionsPage.jsx:540-550`). */
@Composable
private fun DeleteDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val ledger = state.inbox.ledger
    val target = ledger.deleteTarget ?: return
    val merchant = target.merchant.takeIf { it.isNotBlank() }
    ConfirmDelete(
        title = str(S.desktop_ce_inbox_delete_title),
        message = if (merchant != null) {
            str(S.desktop_ce_inbox_delete_body_from, merchant)
        } else {
            str(S.desktop_ce_inbox_delete_body)
        },
        busy = ledger.deleting,
        onCancel = { onEvent(InboxEvent.AskDelete(null)) },
        onConfirm = { onEvent(InboxEvent.ConfirmDelete) },
    )
}

/**
 * "Delete N Transactions?" — open only while there is something to delete:
 * a refetch that empties the visible selection closes it rather than leaving
 * a Delete that does nothing (`AllTransactionsPage.jsx:552-567`).
 */
@Composable
private fun BulkDeleteDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val ledger = state.inbox.ledger
    val selected = state.visibleLedgerSelection()
    if (!ledger.bulkConfirm || state.loading || selected.isEmpty()) return
    val one = selected.size == 1
    ConfirmDelete(
        title = str(if (one) S.desktop_ce_inbox_bulk_title_one else S.desktop_ce_inbox_bulk_title_many, selected.size),
        message = str(if (one) S.desktop_ce_inbox_bulk_body_one else S.desktop_ce_inbox_bulk_body_many, selected.size),
        busy = ledger.bulkDeleting,
        onCancel = { onEvent(InboxEvent.AskBulkDelete(open = false)) },
        onConfirm = { onEvent(InboxEvent.ConfirmBulkDelete(selected)) },
    )
}

@Composable
private fun ConfirmDelete(
    title: String,
    message: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    ZillitDialogShell(
        title = title,
        icon = ZillitIcons.Trash,
        visible = true,
        width = CONFIRM_WIDTH,
        onDismiss = { if (!busy) onCancel() },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = onCancel,
                variant = ButtonVariant.Tertiary,
                enabled = !busy,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = onConfirm,
                variant = ButtonVariant.Danger,
                loading = busy,
            )
        },
    ) {
        ZillitText(message, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
    }
}

private val CONFIRM_WIDTH = 440.dp
