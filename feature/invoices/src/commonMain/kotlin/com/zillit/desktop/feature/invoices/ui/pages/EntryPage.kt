package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.EntryFilter
import com.zillit.desktop.feature.invoices.domain.EntrySort
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * Invoice Entry — the web's `EntryPage`.
 *
 * The third stage: an invoice that approval has cleared is coded here and
 * posted to the ledger. Who may touch a row is not a look but a rule — a
 * non-senior only sees their own assignments as actionable, and an
 * unassigned invoice is nobody's until a senior hands it over.
 */
@Composable
internal fun ColumnScope.EntryPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_entry_queue),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ZillitSelect(
            value = state.entrySort,
            options = EntrySort.entries,
            onSelect = { onEvent(InvoicesEvent.SelectEntrySort(it)) },
            label = { it.label },
            modifier = Modifier.width(ENTRY_SELECT_WIDTH),
        )
        ZillitSelect(
            value = state.payFilter,
            options = listOf<PayMethod?>(null) + PayMethod.entries,
            onSelect = { onEvent(InvoicesEvent.SelectPayFilter(it)) },
            label = { it?.label ?: str(S.desktop_all_pay_methods) },
            modifier = Modifier.width(ENTRY_SELECT_WIDTH),
        )
    }
    EntryFilterRow(state, onEvent)
    val rows = state.entryRows
    EntryBulkBar(state, onEvent)
    TableCard(
        title = str(S.desktop_entry_queue),
        icon = ZillitIcons.File,
        meta = countMeta(rows.size, "invoice"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = entryColumns(state, onEvent),
            key = { it.id },
            onRowClick = { if (state.canAccessEntry(it)) onEvent(InvoicesEvent.Open(it)) },
            isSelected = { it.id in state.selected },
            emptyTitle = str(S.desktop_nothing_awaiting_entry),
            emptyMessage = str(S.desktop_inv_entry_empty_message),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The web's quick filters, and the line that explains a locked row. */
@Composable
private fun EntryFilterRow(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.entryFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectEntryFilter(filter)) },
            )
        }
    }
    if (!state.viewer.isSenior) {
        MutedLine(str(S.desktop_inv_only_assigned_note))
    }
}

/** What can be done to everything ticked, and only while something is. */
@Composable
private fun EntryBulkBar(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    if (state.selected.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_inv_n_invoices_selected, state.selected.size),
            style = ZillitTheme.typography.bodySmall,
        )
        ZillitButton(
            text = str(S.assign),
            onClick = { onEvent(InvoicesEvent.StartAssign) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = str(S.desktop_submit_for_review),
            onClick = { onEvent(InvoicesEvent.ReviewSelected) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
        ZillitButton(
            text = str(S.ah_post_to_ledger),
            onClick = { onEvent(InvoicesEvent.PostSelected) },
            size = ButtonSize.Small,
            enabled = !state.busy && state.viewer.mayPost,
        )
        ZillitButton(
            text = str(S.ah_clear),
            onClick = { onEvent(InvoicesEvent.ClearSelection) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

private fun entryColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn("", ColumnWidth.Fixed(TICK_WIDTH)) { invoice ->
        // A row this viewer cannot open cannot be bulk-actioned either, or
        // select-all would hand them invoices they are not allowed to see.
        if (state.canAccessEntry(invoice)) {
            ZillitCheckbox(
                checked = invoice.id in state.selected,
                onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
            )
        } else {
            CellText("—", muted = true)
        }
    },
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_pay_method), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = it.payMethod.tone())
    },
    TableColumn(str(S.assigned), ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        val name = state.assigneeName(invoice)
        CellText(name ?: str(S.unassigned), muted = name == null)
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        val review = invoice.status == InvoiceStatus.UnderReview
        ZillitStatusPill(
            label = if (review) str(S.ah_under_review) else str(S.dd_csv_status_ready),
            tone = if (review) StatusTone.Progress else StatusTone.Ready,
        )
    },
)

internal val ENTRY_SELECT_WIDTH = 180.dp
