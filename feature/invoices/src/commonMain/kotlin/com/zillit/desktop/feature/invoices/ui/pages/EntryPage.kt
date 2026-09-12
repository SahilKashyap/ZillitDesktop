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
            placeholder = "Search by number, vendor, nominal code, amount",
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
            label = { it?.label ?: "All pay methods" },
            modifier = Modifier.width(ENTRY_SELECT_WIDTH),
        )
    }
    EntryFilterRow(state, onEvent)
    val rows = state.entryRows
    EntryBulkBar(state, onEvent)
    TableCard(
        title = "Entry Queue",
        icon = ZillitIcons.File,
        meta = countMeta(rows.size, "invoice"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = entryColumns(state, onEvent),
            key = { it.id },
            onRowClick = { if (state.canAccessEntry(it)) onEvent(InvoicesEvent.Open(it)) },
            isSelected = { it.id in state.selected },
            emptyTitle = "Nothing awaiting entry",
            emptyMessage = "Approve invoices from the Approval Queue to see them here.",
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
        MutedLine("You can only open invoices assigned to you; a senior accountant assigns the rest.")
    }
}

/** What can be done to everything ticked, and only while something is. */
@Composable
private fun EntryBulkBar(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    if (state.selected.isEmpty()) return
    val plural = if (state.selected.size == 1) "invoice" else "invoices"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "${state.selected.size} $plural selected",
            style = ZillitTheme.typography.bodySmall,
        )
        ZillitButton(
            text = "Assign",
            onClick = { onEvent(InvoicesEvent.StartAssign) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = "Submit for Review",
            onClick = { onEvent(InvoicesEvent.ReviewSelected) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
        ZillitButton(
            text = "Post to ledger",
            onClick = { onEvent(InvoicesEvent.PostSelected) },
            size = ButtonSize.Small,
            enabled = !state.busy && state.viewer.mayPost,
        )
        ZillitButton(
            text = "Clear",
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
    TableColumn("Invoice", ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn("Gross", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Pay Method", ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = it.payMethod.tone())
    },
    TableColumn("Assigned", ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        val name = state.assigneeName(invoice)
        CellText(name ?: "Unassigned", muted = name == null)
    },
    TableColumn("Status", ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        val review = invoice.status == InvoiceStatus.UnderReview
        ZillitStatusPill(
            label = if (review) "Under Review" else "Ready",
            tone = if (review) StatusTone.Progress else StatusTone.Ready,
        )
    },
)

internal val ENTRY_SELECT_WIDTH = 180.dp
