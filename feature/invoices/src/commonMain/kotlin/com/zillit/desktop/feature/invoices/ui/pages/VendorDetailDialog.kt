// The vendor detail — the web's SuppliersPage "Vendor Detail" modal.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.MoneyTotal
import com.zillit.desktop.feature.invoices.domain.VendorComplianceCheck
import com.zillit.desktop.feature.invoices.domain.VendorHistory
import com.zillit.desktop.feature.invoices.domain.VendorPoRow
import com.zillit.desktop.feature.invoices.domain.VendorRow
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.VendorEvent
import com.zillit.desktop.feature.invoices.ui.VendorHistoryTab

/**
 * "Vendor Detail — {name}" (`SuppliersPage.jsx:571-957`): the compliance pill
 * and Edit (a master vendor only, into Account Hub → Vendors) in the header;
 * the company, tax and bank facts; the vendor's history in four tabs with its
 * payment summary; then the five compliance checks.
 */
@Composable
internal fun VendorDetailDialog(state: InvoicesUiState, row: VendorRow, onEvent: (InvoicesEvent) -> Unit) {
    val history = VendorHistory.of(row, state.invoices, state.vendorsPage.orders)
    ZillitDialogShell(
        title = str(S.desktop_inv_vendor_detail_title, row.name),
        onDismiss = { onEvent(VendorEvent.Close) },
        visible = true,
        width = DETAIL_WIDTH,
        icon = AhIcons.Vendor,
        actions = {
            ZillitStatusPill(label = row.complianceLabel, tone = row.complianceTone())
            if (row.vendor.id.isNotBlank()) {
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(VendorEvent.EditVendor(row.vendor.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            }
            ZillitButton(text = str(S.close), onClick = { onEvent(VendorEvent.Close) }, variant = ButtonVariant.Tertiary)
        },
    ) {
        VendorFacts(state, row)
        HistoryCard(state, row, history, onEvent)
        ComplianceAlerts(row)
    }
}

// -- the facts ---------------------------------------------------------------------------

@Composable
private fun VendorFacts(state: InvoicesUiState, row: VendorRow) {
    val vendor = row.vendor
    ZillitSectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            FactColumn(str(S.desktop_inv_company_information), Modifier.weight(1f)) {
                FactPair(str(S.name), row.name)
                FactPair(str(S.country), countryWithFlag(row.country))
                FactPair(str(S.city), vendor.city.ifBlank { "—" })
                FactPair(str(S.address), vendor.address.ifBlank { "—" })
                FactPair(str(S.email), vendor.email.ifBlank { "—" })
                FactPair(str(S.phone), vendor.phone.ifBlank { "—" })
                FactPair(str(S.type), vendor.type.ifBlank { "—" })
                FactPair(str(S.desktop_inv_spend), InvoiceFormat.money(row.totalSpend, row.currency.ifBlank { state.projectCurrency }))
            }
            FactColumn(str(S.desktop_inv_tax_and_compliance), Modifier.weight(1f)) {
                FactPair(str(S.desktop_tax_id), vendor.taxNumber.ifBlank { "—" }) {
                    if (row.hasTaxId) ZillitIcon(icon = ZillitIcons.Check, tint = ZillitTheme.colors.success, size = TICK)
                }
                FactPair(str(S.dm_section_compliance), null) {
                    ZillitStatusPill(label = row.complianceLabel, tone = row.complianceTone())
                }
                FactPair(str(S.desktop_terms), row.termsLabel)
            }
            FactColumn(str(S.desktop_inv_bank_payment_details), Modifier.weight(1f)) {
                FactPair(str(S.desktop_bank), if (row.hasBank) null else "—") {
                    if (row.hasBank) ZillitStatusPill(label = str(S.ah_verified), tone = StatusTone.Done)
                }
                FactPair(str(S.desktop_default_code), vendor.defaultNominalCode.ifBlank { "—" })
            }
        }
    }
}

@Composable
private fun FactColumn(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FactLabel(title)
        content()
    }
}

/** "Label: value", with room for a pill or a tick after it. */
@Composable
private fun FactPair(label: String, value: String?, trailing: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "$label:",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        value?.let { ZillitText(text = it, style = ZillitTheme.typography.bodySmall, maxLines = 2) }
        trailing?.invoke()
    }
}

// -- the history -------------------------------------------------------------------------

@Composable
private fun HistoryCard(
    state: InvoicesUiState,
    row: VendorRow,
    history: VendorHistory,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val totalSpend = grossTotal(state, history.invoices)
    val tab = state.vendorsPage.historyTab
    ZillitSectionCard(
        title = str(S.desktop_inv_vendor_history_title, row.name.uppercase()),
        icon = AhIcons.List,
        meta = str(S.desktop_inv_total_spend_value, totalSpend.text),
    ) {
        ZillitTabStrip(
            tabs = VendorHistoryTab.entries.map { entry ->
                val count = when (entry) {
                    VendorHistoryTab.PurchaseOrders -> history.purchaseOrders.size
                    VendorHistoryTab.Invoices -> history.invoices.size
                    VendorHistoryTab.Payments -> history.paid.size
                    VendorHistoryTab.CreditNotes -> 0
                }
                ZillitTab(id = entry.name, label = entry.label(count))
            },
            activeId = tab.name,
            onSelect = { id -> VendorHistoryTab.entries.firstOrNull { it.name == id }?.let { onEvent(VendorEvent.SelectTab(it)) } },
        )
        when (tab) {
            VendorHistoryTab.PurchaseOrders -> OrdersTab(state, history.purchaseOrders)
            VendorHistoryTab.Invoices -> InvoicesTab(state, history.invoices, totalSpend)
            VendorHistoryTab.Payments -> PaymentsTab(state, history.paid)
            VendorHistoryTab.CreditNotes -> EmptyLine(str(S.desktop_inv_no_credits_for_vendor))
        }
        PaymentSummary(state, history)
    }
}

@Composable
private fun OrdersTab(state: InvoicesUiState, orders: List<VendorPoRow>) {
    when {
        state.vendorsPage.ordersLoading -> EmptyLine(str(S.desktop_inv_loading_pos))
        orders.isEmpty() -> EmptyLine(str(S.desktop_inv_no_pos_for_vendor))
        else -> {
            HistoryRow(
                listOf(
                    str(S.desktop_po), str(S.description), str(S.desktop_inv_po_value), str(S.desktop_invoiced),
                    str(S.remaining), str(S.desktop_inv_percent_used), str(S.status),
                ),
                header = true,
            )
            orders.forEach { order ->
                val currency = order.po.currency.ifBlank { state.projectCurrency }
                HistoryRow(
                    listOf(
                        order.po.poNumber.ifBlank { "—" },
                        order.po.description.ifBlank { "—" },
                        InvoiceFormat.money(order.po.grossTotal, currency),
                        InvoiceFormat.money(order.invoiced, currency),
                        InvoiceFormat.money(kotlin.math.abs(order.remaining), currency),
                    ),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        ZillitProgressBar(
                            fraction = order.percentUsed / FULL,
                            modifier = Modifier.fillMaxWidth().height(BAR),
                            fillColor = when {
                                order.percentUsed >= FULL_INT -> ZillitTheme.colors.danger
                                order.percentUsed >= NEARLY -> ZillitTheme.colors.accent
                                else -> ZillitTheme.colors.teal
                            },
                        )
                        CellText("${order.percentUsed}%", muted = true)
                    }
                    Box(Modifier.weight(1f)) {
                        ZillitStatusPill(label = order.po.statusLabel, tone = poTone(order.po.statusKey))
                    }
                }
            }
            HistoryRow(
                listOf(
                    "",
                    countMeta(orders.size, S.desktop_inv_po_count_one, S.desktop_po_count_pos),
                    total(state, orders.map { it.po.grossTotal to it.po.currency }).text,
                    total(state, orders.map { it.invoiced to it.po.currency }).text,
                    total(state, orders.map { kotlin.math.abs(it.remaining) to it.po.currency }).text,
                    "",
                    "",
                ),
                footer = true,
            )
        }
    }
}

@Composable
private fun InvoicesTab(state: InvoicesUiState, invoices: List<Invoice>, totalSpend: MoneyTotal) {
    if (invoices.isEmpty()) {
        EmptyLine(str(S.desktop_inv_no_invoices_for_vendor))
        return
    }
    HistoryRow(
        listOf(
            str(S.ah_run_detail_col_invoice), str(S.description), str(S.date), str(S.desktop_net),
            str(S.ah_lbl_vat), str(S.desktop_gross), str(S.desktop_po), str(S.dm_rule_nominal), str(S.status),
        ),
        header = true,
    )
    invoices.forEach { invoice ->
        val currency = invoice.currency.ifBlank { state.projectCurrency }
        HistoryRow(
            listOf(
                invoice.invoiceNumber.ifBlank { invoice.reference }.ifBlank { "—" },
                invoice.description.ifBlank { "—" },
                InvoiceFormat.date(invoice.invoiceDateMs),
                InvoiceFormat.money(invoice.netAmount, currency),
                InvoiceFormat.money(invoice.taxAmount, currency),
                InvoiceFormat.money(invoice.grossAmount, currency),
            ),
        ) {
            Box(Modifier.weight(1f)) { PoPill(invoice) }
            CellWeighted(invoice.nominalCode.ifBlank { "—" })
            Box(Modifier.weight(1f)) {
                ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
            }
        }
    }
    HistoryRow(
        listOf("", countMeta(invoices.size), "", "—", "—", totalSpend.text, "", "", ""),
        footer = true,
    )
}

@Composable
private fun PaymentsTab(state: InvoicesUiState, paid: List<Invoice>) {
    if (paid.isEmpty()) {
        EmptyLine(str(S.desktop_inv_no_payments_for_vendor))
        return
    }
    HistoryRow(
        listOf(
            str(S.ah_run_detail_col_invoice), str(S.description), str(S.date), str(S.desktop_gross),
            str(S.desktop_po), str(S.status),
        ),
        header = true,
    )
    paid.forEach { invoice ->
        HistoryRow(
            listOf(
                invoice.invoiceNumber.ifBlank { invoice.reference }.ifBlank { "—" },
                invoice.description.ifBlank { "—" },
                InvoiceFormat.date(invoice.invoiceDateMs),
                InvoiceFormat.money(invoice.grossAmount, invoice.currency.ifBlank { state.projectCurrency }),
            ),
        ) {
            Box(Modifier.weight(1f)) { PoPill(invoice) }
            Box(Modifier.weight(1f)) { ZillitStatusPill(label = str(S.desktop_paid), tone = StatusTone.Done) }
        }
    }
    HistoryRow(
        listOf(
            "",
            countMeta(paid.size, S.desktop_inv_payment_count_one, S.desktop_inv_payment_count_other),
            "",
            grossTotal(state, paid).text,
            "",
            "",
        ),
        footer = true,
    )
}

/** The web's PO chip: "N POs", the number, a dash — or "No PO". */
@Composable
private fun PoPill(invoice: Invoice) {
    ZillitStatusPill(label = if (invoice.hasPo) invoice.poLabel ?: "—" else str(S.desktop_no_po), tone = StatusTone.Progress)
}

/** Payments made, total paid, outstanding, invoices and disputes — under every tab. */
@Composable
private fun PaymentSummary(state: InvoicesUiState, history: VendorHistory) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        MutedLine(str(S.desktop_inv_summary_payments_made, history.paid.size))
        MutedLine(str(S.desktop_inv_summary_total_paid, grossTotal(state, history.paid).text))
        MutedLine(str(S.desktop_inv_summary_outstanding, grossTotal(state, history.outstanding).text))
        MutedLine(str(S.desktop_inv_summary_invoices, history.invoices.size))
        MutedLine(
            str(
                S.desktop_inv_summary_credits,
                history.disputedCount.takeIf { it > 0 }?.toString() ?: str(S.none),
            ),
        )
    }
}

// -- the compliance checks ------------------------------------------------------------------

@Composable
private fun ComplianceAlerts(row: VendorRow) {
    val missing = VendorComplianceCheck.of(row)
    ZillitSectionCard(
        title = str(S.desktop_inv_ai_compliance_alerts),
        icon = ZillitIcons.Warning,
        action = {
            if (missing.isEmpty()) {
                ZillitStatusPill(label = str(S.desktop_inv_all_clear), tone = StatusTone.Done)
            } else {
                ZillitStatusPill(
                    label = countMeta(missing.size, S.desktop_inv_actions_needed_one, S.desktop_inv_actions_needed_other),
                    tone = StatusTone.Rejected,
                )
            }
        },
    ) {
        if (missing.isEmpty()) {
            MutedLine(str(S.desktop_inv_no_compliance_issues))
            return@ZillitSectionCard
        }
        missing.forEach { check ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                ZillitIcon(icon = AhIcons.XCircle, tint = ZillitTheme.colors.danger, size = ALERT_ICON)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitText(
                        text = check.title(row.name),
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    )
                    MutedLine(check.description)
                }
            }
        }
    }
}

// -- the small parts ------------------------------------------------------------------------

/** A mixed list summed through the project's rates, as `describeAmountTotal` does. */
private fun total(state: InvoicesUiState, rows: List<Pair<Double, String>>): MoneyTotal =
    state.rates.copy(defaultCode = state.rates.defaultCode.ifBlank { state.projectCurrency }).total(rows)

private fun grossTotal(state: InvoicesUiState, invoices: List<Invoice>): MoneyTotal =
    total(state, invoices.map { it.grossAmount to it.currency })

/** The web's PO status tones: posted and approved green, rejected red, closed grey, the rest amber. */
private fun poTone(key: String): StatusTone = when (key) {
    "posted", "approved" -> StatusTone.Done
    "rejected" -> StatusTone.Rejected
    "closed" -> StatusTone.Neutral
    else -> StatusTone.Pending
}

/**
 * One table row: the text cells, then any drawn ones [extra] adds after them,
 * each an equal share of the width but the description's.
 */
@Composable
private fun HistoryRow(
    cells: List<String>,
    header: Boolean = false,
    footer: Boolean = false,
    extra: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                color = if (header) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(if (index == 1) DESCRIPTION_SHARE else 1f),
            )
        }
        extra?.invoke(this)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CellWeighted(text: String) {
    Box(Modifier.weight(1f)) { CellText(text, muted = true) }
}

@Composable
private fun EmptyLine(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        MutedLine(text)
    }
}

private val DETAIL_WIDTH = 1100.dp
private val LABEL_WIDTH = 96.dp
private val TICK = 12.dp
private val ALERT_ICON = 16.dp
private val BAR = 5.dp
private const val DESCRIPTION_SHARE = 1.8f
private const val FULL = 100f
private const val FULL_INT = 100
private const val NEARLY = 75
