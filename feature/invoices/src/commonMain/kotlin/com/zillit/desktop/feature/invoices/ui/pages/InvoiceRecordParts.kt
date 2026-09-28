// The invoice's own record as the web's detail modal and PO matching overlay
// both lay it out: vendor, three-up facts, linked POs, hold reason, audit.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceLabels
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/** A small upper-case caption over a value — the web's `text-2xs uppercase text-gray-400`. */
@Composable
internal fun RecordCaption(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Fields three to a row, a caption over each value; a short row keeps its columns. */
@Composable
internal fun RecordGrid(fields: List<Pair<String, String>>, mono: Boolean = false) {
    val colors = ZillitTheme.colors
    fields.chunked(RECORD_COLUMNS).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            row.forEach { (label, value) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    RecordCaption(label)
                    ZillitText(
                        text = value,
                        style = (if (mono) ZillitTheme.typography.numeric else ZillitTheme.typography.bodySmall)
                            .copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 2,
                    )
                }
            }
            repeat(RECORD_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
        }
    }
}

/**
 * The vendor: name, then the address, then phone | email
 * (`InvoiceDetailModal.jsx` / `POMatchingOverlay.jsx` Vendor section).
 */
@Composable
internal fun RecordVendor(state: InvoicesUiState, invoice: Invoice) {
    val colors = ZillitTheme.colors
    val vendor = state.vendors[invoice.vendorId]
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        RecordCaption(str(S.ah_lbl_vendor))
        ZillitText(
            text = vendor?.name?.takeIf { it.isNotBlank() } ?: "—",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
        )
        vendor?.address?.takeIf { it.isNotBlank() }?.let {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2)
        }
        val contact = listOfNotNull(
            vendor?.phone?.takeIf { it.isNotBlank() },
            vendor?.email?.takeIf { it.isNotBlank() },
        )
        if (contact.isNotEmpty()) {
            ZillitText(
                text = contact.joinToString(" | "),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * Department / Currency / Payment Method; Company / Bank / Episode (television
 * only); the Net / Tax / Gross amounts ("—" when never captured); Invoice /
 * Due / Effective dates.
 */
@Composable
internal fun RecordFacts(state: InvoicesUiState, invoice: Invoice) {
    val currency = invoice.currency.ifBlank { state.projectCurrency }
    RecordGrid(
        listOf(
            str(S.department) to state.departmentName(invoice.departmentId),
            str(S.asset_currency) to currency,
            str(S.desktop_payment_method) to invoice.payMethodLabel,
        ),
    )
    val company = state.companies.firstOrNull { it.id == invoice.companyId }?.name?.takeIf { it.isNotBlank() } ?: "—"
    val bank = state.banks.firstOrNull { it.id == invoice.bankId }?.displayName ?: "—"
    val episode = if (state.viewer.isTelevision) {
        listOf(str(S.episode) to invoice.episode.ifBlank { "—" })
    } else {
        emptyList()
    }
    RecordGrid(listOf(str(S.company) to company, str(S.desktop_bank) to bank) + episode)
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        RecordCaption(str(S.desktop_inv_amounts))
        RecordGrid(
            listOf(
                str(S.desktop_net) to InvoiceFormat.money(invoice.netAmount, currency),
                str(S.ah_lbl_vat) to InvoiceFormat.money(invoice.taxAmount, currency),
                str(S.desktop_gross) to InvoiceFormat.money(invoice.grossAmount, currency),
            ),
            mono = true,
        )
    }
    RecordGrid(
        listOf(
            str(S.desktop_invoice_date_title) to InvoiceFormat.date(invoice.invoiceDateMs),
            str(S.desktop_due_date_title) to InvoiceFormat.date(invoice.dueDateMs),
            str(S.ah_lbl_eff_date) to InvoiceFormat.date(invoice.effectiveDateMs),
        ),
    )
}

/**
 * "Linked POs (N)" — one card per linked order, its face enriched from the
 * order's own record once read (`useLinkedPoSummaries`): number, vendor,
 * description and gross in the order's currency. A click hands the card to
 * [onOpen]; [selected] outlines the one the PDF pane is showing.
 */
@Composable
internal fun LinkedPoCards(
    state: InvoicesUiState,
    invoice: Invoice,
    selected: Int?,
    onOpen: (index: Int, po: LinkedPo, number: String) -> Unit,
) {
    if (invoice.linkedPos.isEmpty()) return
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        RecordCaption(str(S.desktop_inv_linked_pos_n, invoice.linkedPos.size))
        invoice.linkedPos.forEachIndexed { index, link ->
            val po = state.poSummaries[link.poId]
            val number = po?.poNumber?.takeIf { it.isNotBlank() } ?: link.poNumber.ifBlank { link.poId }
            val vendor = (po?.vendorId?.let { state.vendors[it]?.name } ?: state.vendors[link.poVendorId]?.name)
                .orEmpty()
            val gross = po?.grossTotal ?: link.poGrossTotal
            val active = index == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (active) colors.infoSoft else colors.surface, ZillitTheme.shapes.small)
                    .border(1.dp, if (active) colors.info else colors.border, ZillitTheme.shapes.small)
                    .clickable { onOpen(index, link, number) }
                    .padding(ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = number,
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                    if (vendor.isNotBlank()) {
                        ZillitText(text = vendor, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                    }
                    po?.description?.takeIf { it.isNotBlank() }?.let {
                        ZillitText(
                            text = it,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
                if (gross != null) {
                    ZillitText(
                        text = InvoiceFormat.money(gross, po?.currency?.takeIf { it.isNotBlank() } ?: invoice.currency),
                        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The overlay's "Hold Reason" block, shown whenever a reason is on record
 * (`POMatchingOverlay.jsx:588-597`): the reason humanised, and the notes with
 * any user id the directory knows swapped for the name.
 */
@Composable
internal fun HoldReasonBlock(state: InvoicesUiState, invoice: Invoice, nameOf: (String) -> String?) {
    if (invoice.holdReason.isBlank()) return
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warningSoft)
            .border(1.dp, colors.warning.copy(alpha = HOLD_BORDER_ALPHA))
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(S.desktop_inv_hold_reason).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.warning,
        )
        ZillitText(
            text = InvoiceLabels.format(invoice.holdReason),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        if (invoice.holdNote.isNotBlank()) {
            ZillitText(
                text = str(S.desktop_inv_notes_label) + " " +
                    InvoiceLabels.resolveUserIds(invoice.holdNote) { nameOf(it) ?: state.userNames[it] },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * Created By / Updated By, each a name, its designation and when
 * (`InvoiceDetailModal.jsx:346-366`). [showUpdated] is the host's own rule:
 * the detail shows it whenever there is an editor, the overlay whenever there
 * is an edit time.
 */
@Composable
internal fun AuditFooter(
    invoice: Invoice,
    nameOf: (String) -> String?,
    designationOf: (String) -> String?,
    showUpdated: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        AuditCell(
            caption = str(S.created_by_new),
            name = nameOf(invoice.userId),
            designation = designationOf(invoice.userId),
            atMs = invoice.createdAtMs,
            showTimeWhenMissing = true,
            modifier = Modifier.weight(1f),
        )
        if (showUpdated) {
            AuditCell(
                caption = str(S.ah_lbl_updated_by),
                name = invoice.updatedBy.takeIf { it.isNotBlank() }?.let(nameOf),
                designation = invoice.updatedBy.takeIf { it.isNotBlank() }?.let(designationOf),
                atMs = invoice.updatedAtMs,
                showTimeWhenMissing = false,
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AuditCell(
    caption: String,
    name: String?,
    designation: String?,
    atMs: Long?,
    showTimeWhenMissing: Boolean,
    modifier: Modifier,
) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        RecordCaption(caption)
        ZillitText(
            text = name?.takeIf { it.isNotBlank() } ?: "—",
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
        )
        designation?.takeIf { it.isNotBlank() }?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        if (atMs != null || showTimeWhenMissing) {
            ZillitText(
                text = InvoiceFormat.dateTime(atMs),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * The web's `PeriodLockBanner` (`ui/PeriodLockBanner.jsx`): why nothing on
 * this invoice can be decided — it is dated inside the closed cost-report period.
 */
@Composable
internal fun InvoiceLockBanner(state: InvoicesUiState) {
    ZillitNotice(
        text = str(S.desktop_inv_locked_period_invoice, state.periodLock.lockedThrough),
        tone = StatusTone.Rejected,
        icon = ZillitIcons.Lock,
    )
}

private const val RECORD_COLUMNS = 3
private const val HOLD_BORDER_ALPHA = 0.4f
