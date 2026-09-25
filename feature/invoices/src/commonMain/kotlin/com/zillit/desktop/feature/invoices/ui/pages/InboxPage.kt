package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InboxTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The Invoice Inbox — the web's `InboxPage`: the queue of what arrived, and
 * beside it the uploads still being extracted into it.
 *
 * A row opens its review, or — with ticks already made — joins them. Ticked
 * rows are processed together: one opens its review, more are checked and
 * sent to pre-approval as one.
 */
@Composable
internal fun ColumnScope.InboxPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    // Search sits above the tabs, as the web's does (`InboxPage.jsx`).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_inbox_long),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
        if (state.inboxTab == InboxTab.Queue) InboxSelectionBar(state, onEvent)
    }
    ZillitTabStrip(
        tabs = InboxTab.entries.map { tab -> ZillitTab(id = tab.name, label = tab.label) },
        activeId = state.inboxTab.name,
        onSelect = { id -> InboxTab.entries.firstOrNull { it.name == id }?.let { onEvent(InboxEvent.SelectTab(it)) } },
    )
    if (state.inboxTab == InboxTab.Uploads) {
        UploadsCard(state, onEvent)
        return
    }
    val rows = state.shownInvoices
    TableCard(
        title = str(S.desktop_inv_inbox_queue),
        icon = ZillitIcons.Inbox,
        meta = str(S.desktop_inv_awaiting_processing, state.invoices.size).takeIf { state.invoices.isNotEmpty() },
    ) {
        ZillitDataTable(
            rows = rows,
            columns = inboxColumns(state, onEvent),
            key = { it.id },
            onRowClick = { row ->
                when {
                    deleting(state, row) -> Unit
                    state.selected.isNotEmpty() -> onEvent(InvoicesEvent.ToggleSelect(row.id))
                    else -> onEvent(InboxEvent.Open(row))
                }
            },
            isSelected = { it.id in state.selected },
            emptyTitle = if (state.search.isNotBlank()) {
                str(S.desktop_inv_no_match_search)
            } else {
                str(S.desktop_inv_inbox_empty_long)
            },
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The row being deleted — it shows a spinner instead of its action, and ignores clicks. */
private fun deleting(state: InvoicesUiState, invoice: Invoice): Boolean =
    state.busy && state.confirmDelete?.id == invoice.id

/**
 * What the ticks can do: the count, Process, Clear — the web's floating bar,
 * with a failed Process said beside its button.
 */
@Composable
private fun InboxSelectionBar(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    if (state.selected.isEmpty()) return
    ZillitText(
        text = str(S.dd_n_selected, state.selected.size),
        style = ZillitTheme.typography.bodySmall,
    )
    state.inboxProcessError?.let {
        ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger)
    }
    ZillitButton(
        text = if (state.busy) str(S.txt_processing) else str(S.ah_process),
        onClick = { onEvent(InboxEvent.ProcessSelected) },
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Check,
        enabled = !state.busy,
        loading = state.busy,
    )
    ZillitButton(
        text = str(S.ah_clear),
        onClick = { onEvent(InvoicesEvent.ClearSelection) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = !state.busy,
    )
}

private fun inboxColumns(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit): List<TableColumn<Invoice>> = listOf(
    // Always tickable, a locked-period row too: bulk Process decides, as on the web.
    TableColumn("", ColumnWidth.Fixed(TICK_WIDTH)) { invoice ->
        ZillitCheckbox(
            checked = invoice.id in state.selected,
            onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
        )
    },
    // The row's own unread beside its number — `getInvoiceTotalUnread(invoice_inbox, id)`.
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { invoice ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CellText(invoice.displayNumber)
            val unread = AccountantPage.Inbox.badgeKey?.let { state.rowUnread(it, invoice.id) } ?: 0
            ZillitBadge(count = unread)
        }
    },
    // The OCR'd supplier until a vendor is on the record — "Unknown" helped nobody.
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { str(S.desktop_manual_entry) }, muted = it.description.isBlank())
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    // One colour for every method, as the web's teal pill.
    TableColumn(str(S.desktop_pay_method_title), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = StatusTone.Progress)
    },
    // `hasPO` is a linked order or a `po_id` — a bare typed number is still "No PO" (`entryToRow`).
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(PO_WIDTH)) { invoice ->
        val label = invoice.poLabel.takeIf { invoice.hasMatchedPo }
        if (label == null) {
            ZillitStatusPill(label = str(S.desktop_no_po), tone = StatusTone.Rejected)
        } else {
            ZillitStatusPill(label = label, tone = StatusTone.InTransit)
        }
    },
    TableColumn(str(S.status), ColumnWidth.Fixed(PO_WIDTH)) { invoice ->
        if (invoice.ocrConfidence != null) {
            ZillitStatusPill(label = str(S.desktop_ocr), tone = StatusTone.Escalated)
        } else {
            ZillitStatusPill(label = str(S.continue_new), tone = StatusTone.Pending)
        }
    },
    TableColumn(str(S.txt_action), ColumnWidth.Fixed(ACTIONS_WIDTH)) { invoice ->
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            when {
                deleting(state, invoice) -> ZillitSpinner(size = DELETE_SPINNER)
                InvoiceRules.canDeleteInbox(invoice, state.viewer) -> ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    },
)

private val DELETE_SPINNER = 12.dp

/**
 * The Inbox's delete confirmation — "Delete Invoice Entry" (`InboxPage.jsx:600-623`):
 * "Deleting..." while it runs, and it stays open when the delete is refused.
 */
@Composable
internal fun InboxDeleteDialog(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val deleting = state.busy
    ZillitDialogShell(
        title = str(S.desktop_inv_delete_invoice_entry),
        onDismiss = { if (!deleting) onEvent(InvoicesEvent.CancelDelete) },
        visible = true,
        icon = ZillitIcons.Trash,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
                enabled = !deleting,
            )
            ZillitButton(
                text = if (deleting) str(S.desktop_inv_deleting) else str(S.delete),
                onClick = { onEvent(InvoicesEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = deleting,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_inv_delete_entry_message, invoice.displayNumber),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}
