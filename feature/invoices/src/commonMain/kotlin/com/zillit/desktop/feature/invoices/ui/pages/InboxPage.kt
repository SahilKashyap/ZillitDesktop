package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
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
    ZillitTabStrip(
        tabs = InboxTab.entries.map { tab ->
            ZillitTab(
                id = tab.name,
                label = tab.label,
                count = if (tab == InboxTab.Uploads) state.uploadRows.size else state.invoices.size,
            )
        },
        activeId = state.inboxTab.name,
        onSelect = { id -> InboxTab.entries.firstOrNull { it.name == id }?.let { onEvent(InboxEvent.SelectTab(it)) } },
    )
    if (state.inboxTab == InboxTab.Uploads) {
        UploadsCard(state, onEvent)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_search_inbox),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        InboxSelectionBar(state, onEvent)
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
                if (state.selected.isNotEmpty()) {
                    onEvent(InvoicesEvent.ToggleSelect(row.id))
                } else {
                    onEvent(InboxEvent.Open(row))
                }
            },
            isSelected = { it.id in state.selected },
            emptyTitle = str(S.desktop_inv_inbox_empty),
            emptyMessage = str(S.desktop_inv_inbox_empty_message),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** What the ticks can do: the count, Process, Clear — the web's floating bar. */
@Composable
private fun InboxSelectionBar(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    if (state.selected.isEmpty()) return
    ZillitText(
        text = str(S.dd_n_selected, state.selected.size),
        style = ZillitTheme.typography.bodySmall,
    )
    ZillitButton(
        text = str(S.ah_process),
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
    TableColumn("", ColumnWidth.Fixed(TICK_WIDTH)) { invoice ->
        ZillitCheckbox(
            checked = invoice.id in state.selected,
            onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
            enabled = !state.isLocked(invoice),
        )
    },
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    // The OCR'd supplier until a vendor is on the record — "Unknown" helped nobody.
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { str(S.desktop_manual_entry) }, muted = it.description.isBlank())
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_pay_method_title), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = it.payMethod.tone())
    },
    poColumn(),
    TableColumn(str(S.status), ColumnWidth.Fixed(PO_WIDTH)) { invoice ->
        if (invoice.ocrConfidence != null) {
            ZillitStatusPill(label = str(S.desktop_ocr), tone = StatusTone.Escalated)
        } else {
            ZillitStatusPill(label = str(S.continue_new), tone = StatusTone.Pending)
        }
    },
    TableColumn("", ColumnWidth.Fixed(ACTIONS_WIDTH)) { invoice ->
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (InvoiceRules.canDeleteInbox(invoice, state.viewer)) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.ah_delete_invoice),
                    tint = ZillitTheme.colors.danger,
                    onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                )
            }
        }
    },
)
