// A linked purchase order, read-only — the web's `LinkedPoViewer`.
package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PoLine
import com.zillit.desktop.feature.invoices.domain.PurchaseOrderRecord
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.LinkedPoView

/**
 * A linked order opened from its card (`LinkedPoViewer.jsx`): "Loading PO …"
 * while it is read, "Couldn't load this PO's details." if it cannot be, and
 * otherwise the order itself, read-only — no approve, reject, edit or query,
 * only View PDF, as the web mounts `PODetailModal` with `readOnly`.
 */
@Composable
internal fun LinkedPoSheet(state: InvoicesUiState, view: LinkedPoView, onEvent: (InvoicesEvent) -> Unit) {
    val record = view.record
    if (record == null) {
        ZillitDialogShell(
            title = view.poNumber.ifBlank { str(S.purchase_order) },
            onDismiss = { onEvent(InvoicesEvent.CloseLinkedPo) },
            visible = true,
            icon = ZillitIcons.Receipt,
        ) {
            ZillitText(
                text = if (view.failed) {
                    str(S.desktop_inv_po_load_failed)
                } else {
                    str(S.desktop_inv_loading_po_n, view.poNumber)
                },
                style = ZillitTheme.typography.bodyMedium,
                color = if (view.failed) ZillitTheme.colors.danger else ZillitTheme.colors.textMuted,
            )
        }
        return
    }
    ZillitDialogShell(
        title = record.label,
        subtitle = record.description.ifBlank { str(S.desktop_no_description) },
        onDismiss = { onEvent(InvoicesEvent.CloseLinkedPo) },
        visible = true,
        icon = ZillitIcons.Receipt,
        width = SHEET_WIDTH,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.ah_view_pdf),
                onClick = { onEvent(InvoicesEvent.OpenLinkedPoPdf) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.File,
                loading = view.openingPdf,
            )
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(InvoicesEvent.CloseLinkedPo) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BODY_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Column(
                modifier = Modifier.width(DETAILS_WIDTH),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                OrderDetails(state, view, record)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                OrderLines(record)
            }
        }
    }
}

/** The order's left column: vendor, then the facts two to a row (`PODetailModal.jsx:482-615`). */
@Composable
private fun OrderDetails(state: InvoicesUiState, view: LinkedPoView, record: PurchaseOrderRecord) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        RecordCaption(str(S.ah_lbl_vendor))
        ZillitText(
            text = state.vendors[record.vendorId]?.name?.takeIf { it.isNotBlank() }
                ?: record.vendorName.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
        )
        if (record.vendorAddress.isNotBlank()) {
            ZillitText(text = record.vendorAddress, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
    val gross = record.grossTotal ?: record.linesGross
    val facts = buildList {
        val currency = record.currency.ifBlank { state.projectCurrency }
        add(str(S.desktop_po_amount_gross) to InvoiceFormat.money(gross, currency))
        add(str(S.asset_currency) to record.currency.ifBlank { state.projectCurrency })
        add(str(S.desktop_inv_eff_date) to InvoiceFormat.date(record.effectiveDateMs))
        add(str(S.delivery_date) to InvoiceFormat.date(record.deliveryDateMs))
        add(str(S.department) to state.departmentName(record.departmentId))
        if (state.viewer.isTelevision && record.episode.isNotBlank()) add(str(S.episode) to record.episode)
    }
    facts.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            row.forEach { (label, value) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    RecordCaption(label)
                    ZillitText(text = value, style = ZillitTheme.typography.bodySmall, color = colors.textPrimary)
                }
            }
            if (row.size == 1) Column(Modifier.weight(1f)) {}
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        RecordCaption(str(S.created_by_new))
        ZillitText(
            text = view.creatorName?.takeIf { it.isNotBlank() } ?: record.createdBy.ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textPrimary,
        )
        view.creatorDesignation?.takeIf { it.isNotBlank() }?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
    if (record.notes.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            RecordCaption(str(S.notes))
            ZillitText(text = record.notes, style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
}

/** The order's lines and their gross (`PODetailModal.jsx:724-827`); a split child shows no price of its own. */
@Composable
private fun ColumnScope.OrderLines(record: PurchaseOrderRecord) {
    RecordCaption(str(S.ah_line_items))
    ZillitDataTable(
        rows = record.lines,
        columns = lineColumns(record),
        emptyTitle = str(S.ah_no_line_items),
        modifier = Modifier.weight(1f),
    )
    ZillitDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitText(
            text = str(S.ah_lbl_gross_total),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = InvoiceFormat.money(record.linesGross, record.currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.accentText,
        )
    }
}

private fun lineColumns(record: PurchaseOrderRecord): List<TableColumn<PoLine>> = listOf(
    TableColumn(str(S.description), ColumnWidth.Weight(2f)) { CellText(it.description.ifBlank { "—" }) },
    TableColumn(str(S.ah_account_label), ColumnWidth.Fixed(ACCOUNT_WIDTH)) {
        CellText(it.account.ifBlank { "—" }, muted = true)
    },
    TableColumn(str(S.ah_lbl_qty), ColumnWidth.Fixed(QTY_WIDTH), numeric = true) { line ->
        CellText(if (line.splitParentId != null) "—" else line.quantity?.let(InvoiceFormat::plain).orEmpty())
    },
    TableColumn(str(S.ah_lbl_unit_price), ColumnWidth.Fixed(MONEY_WIDTH), numeric = true) { line ->
        if (line.splitParentId != null) {
            CellText("—", muted = true)
        } else {
            MoneyText(line.unitPrice ?: 0.0, record.currency, record.currency)
        }
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(MONEY_WIDTH), numeric = true) { line ->
        val net = line.total ?: 0.0
        val tax = if (line.splitParentId == null) line.taxRate?.let { net * it / PERCENT_DIVISOR } ?: 0.0 else 0.0
        MoneyText(net + tax, record.currency, record.currency)
    },
)

private val SHEET_WIDTH = 1200.dp
private val BODY_HEIGHT = 560.dp
private val DETAILS_WIDTH = 420.dp
private val ACCOUNT_WIDTH = 100.dp
private val QTY_WIDTH = 70.dp
private val MONEY_WIDTH = 120.dp
private const val PERCENT_DIVISOR = 100.0
