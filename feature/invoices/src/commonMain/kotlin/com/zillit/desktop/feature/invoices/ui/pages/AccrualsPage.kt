// Accruals: the list and the accrual detail — the web's AccrualsPage.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.AccrualDetail
import com.zillit.desktop.feature.invoices.domain.AccrualFilter
import com.zillit.desktop.feature.invoices.domain.AccrualSort
import com.zillit.desktop.feature.invoices.domain.AccrualStatus
import com.zillit.desktop.feature.invoices.domain.Accruals
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.ui.AccrualEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * What is committed but not yet invoiced — the web's `AccrualsPage`: the
 * search, Regenerate All and Export; the chips with Dept and Sort beside
 * them; then one row per purchase order. A row opens the accrual's detail.
 */
@Composable
internal fun ColumnScope.AccrualsPage(
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
            placeholder = str(S.desktop_inv_search_accruals_long),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
        ZillitButton(
            text = if (state.busy) str(S.desktop_inv_regenerating) else str(S.desktop_regenerate_all),
            onClick = { onEvent(InvoicesEvent.RegenerateAccruals) },
            leadingIcon = AhIcons.Refresh,
            size = ButtonSize.Small,
            loading = state.busy,
            enabled = !state.busy,
        )
        ExportActions(InvoiceExport.Accruals, state.busy, onEvent)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccrualFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.accrualFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectAccrualFilter(filter)) },
            )
        }
        Spacer(Modifier.weight(1f))
        SelectCaption(str(S.desktop_po_dept_column))
        val departments = listOf<String?>(null) + state.departmentNames.entries.sortedBy { it.value.lowercase() }.map { it.key }
        ZillitSelect(
            value = state.accrualsPage.departmentId,
            options = departments,
            onSelect = { onEvent(AccrualEvent.SelectDepartment(it)) },
            label = { id -> if (id == null) str(S.all_departments) else state.departmentName(id) },
            modifier = Modifier.width(DEPARTMENT_WIDTH),
        )
        SelectCaption(str(S.drive_sort))
        ZillitSelect(
            value = state.accrualsPage.sort,
            options = AccrualSort.entries,
            onSelect = { onEvent(AccrualEvent.SelectSort(it)) },
            label = { it.label },
            modifier = Modifier.width(SORT_WIDTH),
        )
    }
    val rows = state.shownAccruals
    TableCard(
        title = str(S.desktop_accruals),
        icon = ZillitIcons.File,
        meta = str(S.desktop_accrual_count_other, rows.size),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = accrualColumns(state),
            key = { it.id },
            onRowClick = { onEvent(AccrualEvent.Open(it.id)) },
            emptyTitle = if (state.accruals.isEmpty()) {
                str(S.desktop_inv_accruals_none_found)
            } else {
                str(S.desktop_inv_accruals_no_match)
            },
            loading = state.accrualsLoading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SelectCaption(text: String) {
    ZillitText(
        text = "${text.uppercase()}:",
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

private fun accrualColumns(state: InvoicesUiState): List<TableColumn<Accrual>> = listOf(
    TableColumn(str(S.desktop_po), ColumnWidth.Weight(1f)) { CellText(it.poNumber.ifBlank { "—" }) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.accrualVendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) { CellText(it.description, muted = true) },
    TableColumn(str(S.desktop_po_total), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.poTotal, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_invoiced), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.invoicedAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_accrual), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.accrualAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_used), ColumnWidth.Fixed(USED_WIDTH), numeric = true) { UsedCell(it) },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) { accrual ->
        ZillitStatusPill(
            label = accrual.statusLabel,
            tone = if (accrual.status == AccrualStatus.Reversed) StatusTone.Neutral else StatusTone.Pending,
        )
    },
)

/** The web's `UsedBar` and the unclamped percentage — red once the order is fully invoiced. */
@Composable
private fun UsedCell(accrual: Accrual) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitProgressBar(
            fraction = (accrual.usedPercent / FULL).coerceIn(0.0, 1.0).toFloat(),
            modifier = Modifier.width(BAR_WIDTH).height(BAR_HEIGHT),
            fillColor = if (accrual.usedPercent >= FULL) ZillitTheme.colors.danger else ZillitTheme.colors.accent,
        )
        CellText(accrual.usedLabel, muted = true)
    }
}

// -- the detail ---------------------------------------------------------------------------

/**
 * "{PO} — {description}" (`AccrualsPage.jsx:79-269`): four figures, the order
 * and its status, the vendor, the order's details, then the invoices booked
 * against it with their total.
 */
@Composable
internal fun AccrualDetailDialog(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val page = state.accrualsPage
    if (page.detailId == null) return
    val detail = page.detail
    ZillitDialogShell(
        title = detail?.title ?: str(S.desktop_inv_accrual_details),
        onDismiss = { onEvent(AccrualEvent.Close) },
        visible = true,
        width = DETAIL_WIDTH,
        icon = ZillitIcons.Ledger,
        actions = {
            ZillitButton(text = str(S.close), onClick = { onEvent(AccrualEvent.Close) }, variant = ButtonVariant.Tertiary)
        },
    ) {
        when {
            page.detailLoading -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitSpinner()
                MutedLine(str(S.ah_loading))
            }
            detail == null -> Box(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl), Alignment.Center) {
                MutedLine(str(S.desktop_inv_accrual_not_found))
            }
            else -> AccrualDetailBody(state, detail)
        }
    }
}

@Composable
private fun AccrualDetailBody(state: InvoicesUiState, detail: AccrualDetail) {
    val accrual = detail.accrual
    val po = detail.po
    val currency = po?.currency?.ifBlank { null } ?: state.projectCurrency
    val money = { value: Double -> InvoiceFormat.money(value, currency) }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitStatTile(str(S.desktop_po_total), money(accrual.poTotal), Modifier.weight(1f))
        ZillitStatTile(str(S.desktop_invoiced), money(accrual.invoicedAmount), Modifier.weight(1f))
        ZillitStatTile(str(S.desktop_accrual), money(accrual.accrualAmount), Modifier.weight(1f), tone = StatusTone.Pending)
        ZillitStatTile(str(S.desktop_inv_percent_used), accrual.usedLabel, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FactLabel(str(S.purchase_order))
            ZillitText(
                text = po?.poNumber?.ifBlank { null } ?: accrual.poNumber.ifBlank { "—" },
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.accentText,
            )
        }
        ZillitStatusPill(
            label = po?.status?.takeIf { it.isNotBlank() }?.lowercase()?.localised() ?: "—",
            tone = if (po?.isPosted == true) StatusTone.Done else StatusTone.Pending,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FactLabel(str(S.ah_lbl_vendor))
        ZillitText(
            text = detail.vendor?.name?.ifBlank { null } ?: state.vendors[accrual.vendorId]?.name ?: "—",
            style = ZillitTheme.typography.label,
        )
        detail.vendor?.address?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
        detail.vendor?.contactLine?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        DetailFact(str(S.desktop_po_amount_gross), money(accrual.poTotal), Modifier.weight(1f))
        DetailFact(str(S.asset_currency), currency, Modifier.weight(1f))
        DetailFact(str(S.desktop_inv_eff_date), InvoiceFormat.date(po?.effectiveDateMs), Modifier.weight(1f))
        DetailFact(str(S.delivery_date), InvoiceFormat.date(po?.deliveryDateMs), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        val treatment = po?.vatTreatment?.takeIf { it.isNotBlank() && it != "pending" }?.localised() ?: "—"
        DetailFact(str(S.desktop_card_tax_treatment), treatment, Modifier.weight(1f))
        DetailFact(
            str(S.department),
            accrual.departmentId.takeIf { it.isNotBlank() }?.let { state.departmentName(it) } ?: "—",
            Modifier.weight(1f),
        )
        DetailFact(
            str(S.description),
            po?.description?.ifBlank { null } ?: accrual.description.ifBlank { "—" },
            Modifier.weight(2f),
        )
    }
    po?.notes?.takeIf { it.isNotBlank() }?.let { DetailFact(str(S.notes), it, Modifier.fillMaxWidth()) }
    RelatedInvoices(state, detail)
}

@Composable
private fun DetailFact(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FactLabel(label)
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall)
    }
}

/** "Related Invoices (n)" and their "Total Invoiced" — or the empty note. */
@Composable
private fun RelatedInvoices(state: InvoicesUiState, detail: AccrualDetail) {
    val colors = ZillitTheme.colors
    FactLabel(str(S.desktop_inv_related_invoices, detail.invoices.size))
    if (detail.invoices.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken)
                .padding(ZillitTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            MutedLine(str(S.desktop_inv_no_invoices_linked_to_po))
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).border(1.dp, colors.border, ZillitTheme.shapes.medium)) {
        RelatedRow(
            listOf(str(S.desktop_inv_invoice_hash), str(S.description), str(S.amount), str(S.date), str(S.status)),
            header = true,
        )
        detail.invoices.forEach { invoice ->
            RelatedRow(
                listOf(
                    invoice.invoiceNumber.ifBlank { "—" },
                    invoice.description.ifBlank { "—" },
                    InvoiceFormat.money(invoice.grossAmount, invoice.currency.ifBlank { state.projectCurrency }),
                    InvoiceFormat.date(invoice.invoiceDateMs),
                ),
            ) {
                Box(Modifier.weight(1f)) {
                    ZillitStatusPill(
                        label = invoice.statusRaw.replace('_', ' ').localised().ifBlank { invoice.statusLabel },
                        tone = when (invoice.status) {
                            InvoiceStatus.Approved, InvoiceStatus.Paid -> StatusTone.Done
                            InvoiceStatus.Rejected -> StatusTone.Rejected
                            else -> StatusTone.Pending
                        },
                    )
                }
            }
        }
        val total = state.rates.copy(defaultCode = state.rates.defaultCode.ifBlank { state.projectCurrency })
            .total(detail.invoices.map { it.grossAmount to it.currency })
        RelatedRow(listOf(str(S.desktop_inv_total_invoiced), "", total.text, "", ""), footer = true)
    }
}

@Composable
private fun RelatedRow(
    cells: List<String>,
    header: Boolean = false,
    footer: Boolean = false,
    extra: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (header || footer) colors.surfaceSunken else colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, text ->
            ZillitText(
                text = if (header) text.uppercase() else text,
                style = when {
                    header -> ZillitTheme.typography.columnHeader
                    footer -> ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                    else -> ZillitTheme.typography.bodySmall
                },
                color = when {
                    header -> colors.textMuted
                    footer && index == 2 -> colors.accentText
                    else -> colors.textPrimary
                },
                maxLines = 1,
                modifier = Modifier.weight(if (index == 1) RELATED_DESCRIPTION else 1f),
            )
        }
        extra?.invoke(this)
    }
}

private val DEPARTMENT_WIDTH = 200.dp
private val SORT_WIDTH = 160.dp
private val USED_WIDTH = 130.dp
private val BAR_WIDTH = 46.dp
private val BAR_HEIGHT = 5.dp
private val DETAIL_WIDTH = 900.dp
private const val FULL = 100.0
private const val RELATED_DESCRIPTION = 2f
