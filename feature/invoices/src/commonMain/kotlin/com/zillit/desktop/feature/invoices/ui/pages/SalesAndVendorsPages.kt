// Vendors and Sales Invoices: the web's SuppliersPage and SalesPage lists.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.SalesFilter
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.SalesInvoices
import com.zillit.desktop.feature.invoices.domain.VendorFilter
import com.zillit.desktop.feature.invoices.domain.VendorRow
import com.zillit.desktop.feature.invoices.domain.VendorSpendReport
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.SalesEvent
import com.zillit.desktop.feature.invoices.ui.VendorEvent

// -- Vendors ---------------------------------------------------------------------------

/** The register at a glance — the web's five tiles (`SuppliersPage.jsx:427-431`). */
@Composable
private fun VendorTiles(rows: List<VendorRow>, loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        val figure = { count: Int -> if (loading) "—" else count.toString() }
        ZillitStatTile(
            label = str(S.desktop_inv_total_vendors),
            value = figure(rows.size),
            sub = str(S.desktop_inv_n_countries, rows.map { it.country }.distinct().size),
            icon = ZillitIcons.Users,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.active),
            value = figure(rows.count { it.isActive }),
            sub = str(S.desktop_inv_invoiced_this_series),
            tone = StatusTone.Done,
            icon = AhIcons.CheckCircle,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_inv_with_tax_id),
            value = figure(rows.count { it.hasTaxId }),
            sub = str(S.desktop_inv_tax_registered),
            tone = StatusTone.Pending,
            icon = ZillitIcons.File,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_inv_no_bank_details),
            value = figure(rows.count { !it.hasBank }),
            sub = str(S.desktop_inv_bank_not_verified),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Bank,
            modifier = tile,
        )
        ZillitStatTile(
            label = str(S.desktop_inv_compliance_issues),
            value = figure(rows.count { it.hasComplianceIssue }),
            sub = str(S.desktop_inv_needs_attention),
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Warning,
            modifier = tile,
        )
    }
}

/**
 * The production's vendors, with what has been spent with each — the web's
 * `SuppliersPage`: five tiles, the search and Add Vendor, the quick filters,
 * then the table. A row opens the vendor's detail.
 *
 * The spend is counted here from the invoice list (`?perPage=500`), because
 * no route answers it; suppliers only ever named on an invoice get a row too.
 */
@Composable
internal fun ColumnScope.VendorsPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    val rates = state.rates.copy(defaultCode = state.rates.defaultCode.ifBlank { state.projectCurrency })
    val all = VendorSpendReport.rows(state.vendors.values.toList(), state.invoices, rates)
    val loading = state.loading && state.invoices.isEmpty()
    VendorTiles(all, loading)
    val needle = state.search.trim().lowercase()
    val rows = all.filter { row ->
        state.vendorsPage.filter.keeps(row) && (
            needle.isEmpty() ||
                row.name.lowercase().contains(needle) ||
                row.country.lowercase().contains(needle) ||
                row.vendor.taxNumber.lowercase().contains(needle) ||
                row.vendor.email.lowercase().contains(needle) ||
                row.vendor.phone.lowercase().contains(needle)
            )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_vendors_long),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
        ZillitButton(
            text = str(S.ah_add_vendor),
            onClick = { onEvent(VendorEvent.AddVendor) },
            leadingIcon = ZillitIcons.Add,
            size = ButtonSize.Small,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VendorFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = str(S.desktop_email_title_with_count, filter.label, all.count { filter.keeps(it) }),
                selected = state.vendorsPage.filter == filter,
                onClick = { onEvent(VendorEvent.SelectFilter(filter)) },
            )
        }
    }
    TableCard(
        title = str(S.ah_vendors),
        icon = AhIcons.List,
        meta = if (loading) null else countMeta(rows.size, S.desktop_inv_one_vendor, S.ah_run_detail_summary_vendors),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = vendorColumns(state),
            key = { it.vendor.id.ifBlank { "name:${it.name}" } },
            onRowClick = { onEvent(VendorEvent.Open(it)) },
            isSelected = { it == state.vendorsPage.detail },
            emptyTitle = str(S.desktop_inv_no_vendors_found),
            loading = loading,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun vendorColumns(state: InvoicesUiState): List<TableColumn<VendorRow>> = listOf(
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(it.name) },
    TableColumn(str(S.country), ColumnWidth.Weight(1f)) { CellText(countryWithFlag(it.country), muted = true) },
    TableColumn(str(S.desktop_tax_id), ColumnWidth.Weight(1f)) {
        CellText(it.vendor.taxNumber.ifBlank { "—" }, muted = true)
    },
    TableColumn(str(S.type), ColumnWidth.Weight(1f)) {
        ZillitStatusPill(label = it.vendor.type.ifBlank { "—" }, tone = StatusTone.Neutral)
    },
    TableColumn(str(S.desktop_bank), ColumnWidth.Fixed(BANK_WIDTH)) { row ->
        if (row.hasBank) {
            ZillitIcon(icon = AhIcons.CheckCircle, tint = ZillitTheme.colors.success)
        } else {
            CellText("—", muted = true)
        }
    },
    TableColumn(str(S.desktop_default_code), ColumnWidth.Weight(1f)) {
        CellText(it.vendor.defaultNominalCode.ifBlank { "—" }, muted = true)
    },
    TableColumn(str(S.desktop_terms), ColumnWidth.Fixed(PAY_WIDTH)) { CellText(it.termsLabel, muted = true) },
    TableColumn(str(S.ah_total_spend), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.totalSpend, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.dm_section_compliance), ColumnWidth.Weight(WEIGHT_NARROW)) { row ->
        ZillitStatusPill(label = row.complianceLabel, tone = row.complianceTone())
    },
)

/** Pending (amber) for a master vendor, Unknown (grey) for an invoice-only supplier. */
internal fun VendorRow.complianceTone(): StatusTone = if (fromMaster) StatusTone.Pending else StatusTone.Neutral

/** "🇬🇧 UK" — the web's `Flag` beside the country, for the countries its `isoMap` knows. */
internal fun countryWithFlag(country: String): String {
    val iso = COUNTRY_ISO[country] ?: return country
    val flag = iso.map { char -> surrogatePair(REGIONAL_A + (char - 'A')) }.joinToString("")
    return "$flag $country"
}

/** The web's own `isoMap` (`SuppliersPage.jsx:210`). */
private val COUNTRY_ISO = mapOf(
    "United Kingdom" to "GB",
    "UK" to "GB",
    "India" to "IN",
    "United States" to "US",
    "US" to "US",
    "Germany" to "DE",
    "France" to "FR",
    "Canada" to "CA",
    "Australia" to "AU",
)

/** A code point above the BMP as its UTF-16 pair — common code has no `Character.toChars`. */
private fun surrogatePair(codePoint: Int): String {
    val offset = codePoint - SUPPLEMENTARY_BASE
    val high = (offset shr SURROGATE_BITS) + HIGH_SURROGATE
    val low = (offset and SURROGATE_MASK) + LOW_SURROGATE
    return charArrayOf(high.toChar(), low.toChar()).concatToString()
}

/** 🇦 — regional indicator A; a flag is two of them. */
private const val REGIONAL_A = 0x1F1E6
private const val SUPPLEMENTARY_BASE = 0x10000
private const val SURROGATE_BITS = 10
private const val SURROGATE_MASK = 0x3FF
private const val HIGH_SURROGATE = 0xD800
private const val LOW_SURROGATE = 0xDC00

// -- Sales Invoices ---------------------------------------------------------------------

/**
 * Money owed *to* the production — the web's `SalesPage` list: the search
 * and Create Invoice, the status chips, then the Invoice List. A row opens its
 * preview, spinning while the full record is read; everything else — Edit,
 * Delete, Mark Sent, History, the PDF — lives in that preview.
 */
@Composable
internal fun ColumnScope.SalesInvoicesPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    val rows = SalesInvoices.shown(state.salesInvoices, state.sales.filter, state.search, state.projectCurrency, nowMs)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_sales_invoices),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
        ZillitButton(
            text = str(S.desktop_inv_create_invoice),
            onClick = { onEvent(InvoicesEvent.StartSalesInvoice) },
            leadingIcon = ZillitIcons.Add,
            size = ButtonSize.Small,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SalesFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.sales.filter == filter,
                onClick = { onEvent(SalesEvent.SelectFilter(filter)) },
            )
        }
    }
    TableCard(title = str(S.desktop_invoice_list), icon = ZillitIcons.File, meta = countMeta(rows.size)) {
        ZillitDataTable(
            rows = rows,
            columns = salesColumns(state),
            key = { it.id },
            onRowClick = { if (state.sales.deletingId != it.id) onEvent(SalesEvent.Preview(it)) },
            isSelected = { it.id == state.sales.previewLoadingId },
            emptyTitle = if (state.salesInvoices.isEmpty()) {
                str(S.desktop_inv_sales_none_yet)
            } else {
                str(S.desktop_inv_sales_no_match)
            },
            loading = state.sales.loading && state.salesInvoices.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Invoice / Client / Gross / Due / Status — the web's five columns (`SalesPage.jsx:877-881`). */
private fun salesColumns(state: InvoicesUiState): List<TableColumn<SalesInvoice>> = listOf(
    // The invoice's `sales_invoices` chip beside its ref (`SalesPage.jsx:133-140, 967-971`).
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { invoice ->
        Box(Modifier.alpha(if (state.sales.deletingId == invoice.id) DIMMED else 1f)) {
            CellTextWithUnread(invoice.listRef, state.pageRowUnread(AccountantPage.Sales, invoice.id))
        }
    },
    TableColumn(str(S.desktop_client), ColumnWidth.Weight(WEIGHT_WIDEST)) { CellText(it.clientName) },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_due), ColumnWidth.Fixed(DUE_WIDTH)) {
        CellText(InvoiceFormat.date(it.dueDateMs), muted = true)
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitStatusPill(label = invoice.status.label, tone = invoice.status.tone())
            if (state.sales.previewLoadingId == invoice.id) ZillitSpinner(size = SPINNER)
        }
    },
)

/** The web's `STATUS_MAP` tones: draft blue, sent amber, paid green, overdue red. */
internal fun SalesInvoiceStatus.tone(): StatusTone = when (this) {
    SalesInvoiceStatus.Draft -> StatusTone.Progress
    SalesInvoiceStatus.Sent -> StatusTone.Pending
    SalesInvoiceStatus.Paid -> StatusTone.Done
    SalesInvoiceStatus.Overdue -> StatusTone.Rejected
    SalesInvoiceStatus.Cancelled -> StatusTone.Progress
}

private const val DIMMED = 0.4f
private val BANK_WIDTH = 64.dp
private val SPINNER = 14.dp
