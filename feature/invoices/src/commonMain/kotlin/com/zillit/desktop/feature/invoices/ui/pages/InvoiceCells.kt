// Table cells shared by the department and accountant pages.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.domain.InvoiceExportFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.BadgeTone
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceBadge
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

internal fun BadgeTone.statusTone(): StatusTone = when (this) {
    BadgeTone.Neutral -> StatusTone.Neutral
    BadgeTone.Pending -> StatusTone.Pending
    BadgeTone.Approved -> StatusTone.Done
    BadgeTone.Rejected -> StatusTone.Rejected
    BadgeTone.Override -> StatusTone.Escalated
}

internal fun InvoiceStatus.statusTone(): StatusTone = when (this) {
    InvoiceStatus.Approved, InvoiceStatus.Paid, InvoiceStatus.Posted -> StatusTone.Done
    InvoiceStatus.Rejected, InvoiceStatus.Cancelled, InvoiceStatus.Disputed -> StatusTone.Rejected
    InvoiceStatus.Approval, InvoiceStatus.UnderReview -> StatusTone.Pending
    InvoiceStatus.Matching, InvoiceStatus.Entry -> StatusTone.Progress
    InvoiceStatus.ReadyToPay -> StatusTone.Ready
    InvoiceStatus.Held, InvoiceStatus.Override -> StatusTone.Escalated
    InvoiceStatus.Inbox, InvoiceStatus.Unknown -> StatusTone.Neutral
}

@Composable
internal fun BadgePill(badge: InvoiceBadge) {
    ZillitStatusPill(label = badge.label, tone = badge.tone.statusTone())
}

@Composable
internal fun PoCell(invoice: Invoice) {
    val label = invoice.poLabel
    if (label == null) {
        ZillitStatusPill(label = "No PO", tone = StatusTone.Rejected)
    } else {
        ZillitStatusPill(label = label, tone = StatusTone.InTransit)
    }
}

@Composable
internal fun MoneyText(amount: Double?, currency: String, projectCurrency: String) {
    ZillitText(
        text = InvoiceFormat.money(amount, currency.ifBlank { projectCurrency }),
        style = ZillitTheme.typography.numeric,
        color = ZillitTheme.colors.textPrimary,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun CellText(text: String, muted: Boolean = false, maxLines: Int = 1) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = if (muted) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
        maxLines = maxLines,
    )
}

/** Invoice · Vendor · Gross — the three columns every table starts with. */
internal fun leadingColumns(state: InvoicesUiState): List<TableColumn<Invoice>> = listOf(
    TableColumn("Invoice", ColumnWidth.Weight(WEIGHT_NARROW)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_WIDE)) { CellText(state.vendorName(it)) },
    TableColumn("Gross", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
)

internal fun poColumn(): TableColumn<Invoice> = TableColumn("PO", ColumnWidth.Fixed(PO_WIDTH)) { PoCell(it) }

internal fun slaColumn(state: InvoicesUiState): TableColumn<Invoice> =
    TableColumn("SLA", ColumnWidth.Fixed(SLA_WIDTH)) {
        CellText(state.vendors[it.vendorId]?.slaLabel ?: "—", muted = true)
    }

internal fun approvalColumn(state: InvoicesUiState, queue: Boolean): TableColumn<Invoice> =
    TableColumn("Approval", ColumnWidth.Weight(WEIGHT_MEDIUM)) { invoice ->
        val tiers = state.tiersOf(invoice)
        BadgePill(if (queue) InvoiceRules.queueBadge(invoice, tiers) else InvoiceRules.approvalBadge(invoice, tiers))
    }

internal fun InvoicesUiState.tiersOf(invoice: Invoice) = ApprovalChain.tiersFor(tierConfigs, invoice)

@Composable
internal fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl), contentAlignment = Alignment.Center) {
        ZillitSpinner()
    }
}

/** "12 invoices", or whatever the page is counting — a creditor row is not an invoice. */
@Composable
internal fun CountLine(count: Int, noun: String = "invoice", extra: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = "$count $noun${if (count == 1) "" else "s"}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        extra?.invoke()
    }
}

/** Column weights, relative to the plain 1f text columns. */
/**
 * The pay-method pill's colour — the web's own mapping.
 *
 * Wire stands out because it leaves the building fastest and is hardest to
 * recall; cheque is green because it is printed, not sent.
 */
internal fun PayMethod.tone(): StatusTone = when (this) {
    PayMethod.Wire -> StatusTone.Rejected
    PayMethod.Cheque -> StatusTone.Done
    else -> StatusTone.Progress
}

/**
 * A table in its own titled card — the web's `Panel`.
 *
 * Every list on the web sits inside one: a white card with a hairline border,
 * an accented icon, a bold title and the row count on the right. A bare table
 * on the page background is the single most visible way this port stopped
 * looking like the web.
 */
@Composable
internal fun ColumnScope.TableCard(
    title: String,
    icon: ImageVector,
    meta: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitSectionCard(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        title = title,
        icon = icon,
        meta = meta,
        padded = false,
        action = action,
        content = content,
    )
}

/**
 * The web's export menu: the same list as PDF or as a spreadsheet.
 *
 * Two buttons rather than a menu — there are only ever two formats, and a
 * menu to choose between two things is a click nobody needs.
 */
@Composable
internal fun ExportActions(export: InvoiceExport, busy: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    InvoiceExportFormat.entries.forEach { format ->
        ZillitButton(
            text = format.label,
            onClick = { onEvent(InvoicesEvent.Export(export, format)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !busy,
        )
    }
}

/** "12 invoices", "1 vendor" — the count the web prints beside a panel title. */
internal fun countMeta(count: Int, noun: String = "invoice"): String =
    "$count $noun" + if (count == 1) "" else "s"

internal const val WEIGHT_NARROW = 1.1f
internal const val WEIGHT_MEDIUM = 1.3f
internal const val WEIGHT_WIDE = 1.4f
internal const val WEIGHT_WIDEST = 1.6f
internal val GROSS_WIDTH = 120.dp
internal val PO_WIDTH = 120.dp
internal val SLA_WIDTH = 80.dp
internal val ACTIONS_WIDTH = 96.dp
internal val SEARCH_WIDTH = 260.dp

/** Widths the payments and sales tables share with the register's. */
internal val TICK_COUNT_WIDTH = 70.dp
internal val RUN_ACTIONS_WIDTH = 220.dp

/** A muted line of explanation under a control or above a table. */
@Composable
internal fun MutedLine(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}
