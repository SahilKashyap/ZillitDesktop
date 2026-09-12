package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.VendorRow
import com.zillit.desktop.feature.invoices.domain.VendorSpendReport
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/** What the register holds, at a glance — the web's three tiles. */
@Composable
private fun VendorTiles(rows: List<VendorRow>, projectCurrency: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        val currency = rows.firstOrNull { it.currency.isNotBlank() }?.currency ?: projectCurrency
        ZillitStatTile(
            label = "Vendors",
            value = rows.size.toString(),
            sub = "on this production",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ZillitStatTile(
            label = "Total Spend",
            value = Money.format(rows.sumOf { it.totalSpend }, currency),
            sub = countMeta(rows.sumOf { it.invoiceCount }),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ZillitStatTile(
            label = "Missing details",
            value = rows.count { !it.isCompliant }.toString(),
            sub = "no tax ID or bank",
            tone = StatusTone.Pending,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/**
 * The production's vendors, with what has been spent with each — the web's
 * `SuppliersPage`.
 *
 * The spend is counted here from the invoice list, because no route answers
 * it; the compliance column says what a vendor is missing before they can be
 * paid.
 */
@Composable
internal fun ColumnScope.VendorsPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    val all = VendorSpendReport.rows(state.vendors.values.toList(), state.invoices)
    val needle = state.search.trim().lowercase()
    val rows = all.filter { row ->
        needle.isEmpty() ||
            row.vendor.name.lowercase().contains(needle) ||
            row.vendor.taxNumber.lowercase().contains(needle)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = "Search vendor or tax ID",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
    }
    VendorTiles(all, state.projectCurrency)
    TableCard(
        title = "Vendors",
        icon = ZillitIcons.Users,
        meta = countMeta(rows.size, "vendor"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = vendorColumns(state),
            key = { it.vendor.id },
            emptyTitle = "No vendors yet",
            emptyMessage = "Vendors added in the Account Hub appear here with what has been spent with them.",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun vendorColumns(state: InvoicesUiState): List<TableColumn<VendorRow>> = listOf(
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(it.vendor.name) },
    TableColumn("Country", ColumnWidth.Weight(1f)) { CellText(it.vendor.country.ifBlank { "—" }, muted = true) },
    TableColumn("Tax ID", ColumnWidth.Weight(1f)) { CellText(it.vendor.taxNumber.ifBlank { "—" }, muted = true) },
    TableColumn("Type", ColumnWidth.Weight(1f)) { CellText(it.vendor.type.ifBlank { "—" }, muted = true) },
    TableColumn("Bank", ColumnWidth.Weight(1f)) { CellText(it.vendor.bankName.ifBlank { "—" }, muted = true) },
    TableColumn("Default Code", ColumnWidth.Weight(1f)) {
        CellText(it.vendor.defaultNominalCode.ifBlank { "—" }, muted = true)
    },
    TableColumn("Terms", ColumnWidth.Fixed(PAY_WIDTH)) {
        CellText(it.vendor.slaLabel ?: it.vendor.terms.ifBlank { "—" }, muted = true)
    },
    TableColumn("Total Spend", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.totalSpend, it.currency, state.projectCurrency)
    },
    TableColumn("Compliance", ColumnWidth.Weight(WEIGHT_MEDIUM)) { row ->
        ZillitStatusPill(
            label = row.complianceLabel,
            tone = if (row.isCompliant) StatusTone.Done else StatusTone.Pending,
        )
    },
)

/**
 * Money owed *to* the production — the web's `SalesPage`.
 *
 * The only outward-facing record in the module: raised against a client, sent,
 * then marked paid. Each row's buttons are what its status allows.
 */
@Composable
internal fun ColumnScope.SalesInvoicesPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    val needle = state.search.trim().lowercase()
    val rows = state.salesInvoices.filter { invoice ->
        needle.isEmpty() ||
            invoice.reference.lowercase().contains(needle) ||
            invoice.clientName.lowercase().contains(needle)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = "Search ref or client",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ZillitButton(
            text = "Raise an invoice",
            onClick = { onEvent(InvoicesEvent.StartSalesInvoice) },
            size = ButtonSize.Small,
            enabled = state.viewer.mayPost,
        )
    }
    TableCard(
        title = "Invoice List",
        icon = ZillitIcons.CreditCard,
        meta = countMeta(rows.size, "invoice"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = salesColumns(state, onEvent),
            key = { it.id },
            emptyTitle = "No sales invoices",
            emptyMessage = "Invoices the production raises against a client appear here.",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun salesColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<SalesInvoice>> = listOf(
    TableColumn("Invoice", ColumnWidth.Weight(1f)) { CellText(it.reference.ifBlank { "—" }) },
    TableColumn("Client", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(it.clientName.ifBlank { "Unknown" }) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn("Gross", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Due", ColumnWidth.Fixed(DUE_WIDTH)) { CellText(InvoiceFormat.date(it.dueDateMs), muted = true) },
    TableColumn("Status", ColumnWidth.Weight(WEIGHT_NARROW)) {
        ZillitStatusPill(label = it.status.label, tone = it.status.tone())
    },
    TableColumn("", ColumnWidth.Fixed(RUN_ACTIONS_WIDTH)) { invoice ->
        SalesActions(state, invoice, onEvent)
    },
)

@Composable
private fun SalesActions(state: InvoicesUiState, invoice: SalesInvoice, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (invoice.status.canSend) {
            ZillitButton(
                text = "Send",
                onClick = { onEvent(InvoicesEvent.SendSalesInvoice(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy && state.viewer.mayPost,
            )
        }
        if (invoice.status.canMarkPaid) {
            ZillitButton(
                text = "Mark paid",
                onClick = { onEvent(InvoicesEvent.MarkSalesInvoicePaid(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy && state.viewer.mayPost,
            )
        }
        if (invoice.status == SalesInvoiceStatus.Draft) {
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(InvoicesEvent.DeleteSalesInvoice(invoice)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !state.busy && state.viewer.mayPost,
            )
        }
    }
}

private fun SalesInvoiceStatus.tone(): StatusTone = when (this) {
    SalesInvoiceStatus.Draft -> StatusTone.Neutral
    SalesInvoiceStatus.Sent -> StatusTone.Progress
    SalesInvoiceStatus.Paid -> StatusTone.Done
    SalesInvoiceStatus.Overdue -> StatusTone.Rejected
    SalesInvoiceStatus.Cancelled -> StatusTone.Neutral
}
