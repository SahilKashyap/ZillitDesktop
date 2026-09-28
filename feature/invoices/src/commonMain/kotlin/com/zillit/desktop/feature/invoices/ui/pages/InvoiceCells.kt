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
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.BadgeTone
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceBadge
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

internal fun BadgeTone.statusTone(): StatusTone = when (this) {
    BadgeTone.Neutral -> StatusTone.Neutral
    BadgeTone.Pending -> StatusTone.Pending
    BadgeTone.Approved -> StatusTone.Done
    BadgeTone.Rejected -> StatusTone.Rejected
    BadgeTone.Override -> StatusTone.Escalated
    BadgeTone.Info -> StatusTone.Progress
    BadgeTone.Awaiting -> StatusTone.Escalated
    BadgeTone.Success -> StatusTone.Ready
}

/**
 * The web's `INVOICE_STATUS_MAP` colours (`lib/constants.jsx:82-97`), through
 * its pill tones: inbox purple; matching, pending, entry and held amber;
 * ready and posted teal; paid and approved green; disputed, rejected and
 * override red; cancelled grey; under review blue; anything else amber.
 */
internal fun InvoiceStatus.statusTone(): StatusTone = when (this) {
    InvoiceStatus.Inbox -> StatusTone.Escalated
    InvoiceStatus.Matching, InvoiceStatus.Approval, InvoiceStatus.Entry -> StatusTone.Pending
    InvoiceStatus.Held, InvoiceStatus.Unknown -> StatusTone.Pending
    InvoiceStatus.ReadyToPay, InvoiceStatus.Posted -> StatusTone.Done
    InvoiceStatus.Paid, InvoiceStatus.Approved -> StatusTone.Ready
    InvoiceStatus.Disputed, InvoiceStatus.Rejected, InvoiceStatus.Override -> StatusTone.Rejected
    InvoiceStatus.Cancelled -> StatusTone.Neutral
    InvoiceStatus.UnderReview -> StatusTone.Progress
}

@Composable
internal fun BadgePill(badge: InvoiceBadge) {
    ZillitStatusPill(label = badge.label, tone = badge.tone.statusTone())
}

@Composable
internal fun PoCell(invoice: Invoice) {
    val label = invoice.poLabel
    if (label == null) {
        ZillitStatusPill(label = str(S.desktop_no_po), tone = StatusTone.Rejected)
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

/**
 * A reference with its row's unread chip beside it — the web's
 * `<span>{row.ref}{renderUnread(row.id)}</span>`, the chip being
 * `getInvoiceTotalUnread(accountHubBadges, level_1, id)` and shown only above
 * nothing (`RegisterPage.jsx:527-538`, `MatchingPage.jsx:363-370`).
 */
@Composable
internal fun CellTextWithUnread(text: String, unread: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CellText(text)
        ZillitBadge(count = unread)
    }
}

/** One row's unread under [page]'s `level_1` — nothing for a page with none. */
internal fun InvoicesUiState.pageRowUnread(page: AccountantPage, id: String): Int =
    page.badgeKey?.let { rowUnread(it, id) } ?: 0

/** Invoice · Vendor · Gross — the three columns every table starts with. */
internal fun leadingColumns(state: InvoicesUiState): List<TableColumn<Invoice>> = listOf(
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(WEIGHT_NARROW)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_WIDE)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
)

internal fun poColumn(): TableColumn<Invoice> =
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(PO_WIDTH)) { PoCell(it) }

internal fun slaColumn(state: InvoicesUiState): TableColumn<Invoice> =
    TableColumn(str(S.desktop_sla), ColumnWidth.Fixed(SLA_WIDTH)) {
        CellText(state.vendors[it.vendorId]?.slaLabel ?: "—", muted = true)
    }

/**
 * The Approval Queue's SLA pill (`ApprovalPage.jsx:441-445, 611`): the
 * vendor's terms through `formatPaymentTerms` — a known `net_N` as "N days",
 * anything else as stored — in blue, or a grey "—" when there are none.
 */
internal fun slaPillColumn(state: InvoicesUiState): TableColumn<Invoice> =
    TableColumn(str(S.desktop_sla), ColumnWidth.Fixed(SLA_WIDTH)) { invoice ->
        val terms = state.vendors[invoice.vendorId]?.terms.orEmpty()
        if (terms.isBlank()) {
            ZillitStatusPill(label = "—", tone = StatusTone.Neutral)
        } else {
            ZillitStatusPill(label = paymentTermsLabel(terms), tone = StatusTone.Progress)
        }
    }

/** `formatPaymentTerms`: the four options the web knows, and any other value passed through. */
internal fun paymentTermsLabel(terms: String): String = when (terms) {
    "net_7", "net_14", "net_30", "net_60" -> str(S.ah_days_format, terms.removePrefix("net_").toInt())
    else -> terms
}

internal fun approvalColumn(state: InvoicesUiState, queue: Boolean): TableColumn<Invoice> =
    TableColumn(str(S.ah_step_approval), ColumnWidth.Weight(WEIGHT_MEDIUM)) { invoice ->
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
internal fun CountLine(
    count: Int,
    one: String = S.desktop_invoice_count_one,
    other: String = S.ah_run_invoices_count,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = countMeta(count, one, other),
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
internal fun countMeta(
    count: Int,
    one: String = S.desktop_invoice_count_one,
    other: String = S.ah_run_invoices_count,
): String = str(if (count == 1) one else other, count)

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
