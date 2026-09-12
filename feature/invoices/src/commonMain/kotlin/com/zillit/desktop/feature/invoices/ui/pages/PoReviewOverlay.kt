// The side-by-side pre-approval review — the web's `POMatchingOverlay`.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.ReviewOverlay
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

/**
 * Reviewing one pre-approval invoice against the order it claims to be for.
 *
 * Three panes, as the web has them: the invoice document on the left, the
 * order in the middle, and the invoice's own record on the right with the
 * decision under it. The decision is routed by the PO, not the pay method —
 * a matched invoice is confirmed, an unmatched one is sent for approval or
 * (with the rights) pushed past the chain.
 */
@Composable
internal fun PoReviewOverlay(
    state: InvoicesUiState,
    review: ReviewOverlay,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val invoice = review.invoice
    ZillitDialogShell(
        title = "Review ${invoice.displayNumber}",
        subtitle = "Check the invoice against its purchase order before it goes for approval.",
        visible = true,
        onDismiss = { if (!review.acting) onEvent(InvoicesEvent.CloseReview) },
        icon = ZillitIcons.Receipt,
        width = OVERLAY_WIDTH,
        maxHeight = OVERLAY_HEIGHT,
        scrollable = false,
        actions = { ReviewActions(state, review, onEvent) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(PANE_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Pane("Invoice document", Modifier.weight(1f)) { DocumentPane(review) }
            Pane("Purchase order", Modifier.weight(1f)) { OrderPane(state, review, onEvent) }
            Pane("Invoice details", Modifier.width(DETAIL_WIDTH)) { RecordPane(state, review) }
        }
    }
}

/** One of the three columns: a titled bar, then whatever it holds. */
@Composable
private fun Pane(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.File, tint = colors.accentText, size = PANE_ICON)
            ZillitText(
                text = title.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

// -- the invoice document -----------------------------------------------------

@Composable
private fun DocumentPane(review: ReviewOverlay) {
    val colors = ZillitTheme.colors
    val attachment = review.invoice.firstAttachment
    val pages = remember(review.preview) { review.preview?.bytes?.let(::decodePreviewPages).orEmpty() }
    Box(Modifier.fillMaxSize().background(colors.surfaceSunken), contentAlignment = Alignment.Center) {
        when {
            review.loading && attachment == null -> ZillitSpinner()
            attachment == null -> PaneMessage("No document attached")
            pages.isNotEmpty() -> ScrolledPages(pages, attachment.name)
            review.previewLoading -> ZillitSpinner()
            review.previewFailed -> PaneMessage("Could not load the document")
            else -> PaneMessage("${attachment.name.ifBlank { "Document" }} — open it from the invoice to read it")
        }
    }
}

@Composable
private fun ScrolledPages(pages: List<ImageBitmap>, name: String) {
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            pages.forEachIndexed { index, page ->
                Image(
                    bitmap = page,
                    contentDescription = if (pages.size == 1) name else "$name, page ${index + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ZillitScrollRail(scroll)
    }
}

// -- the order ----------------------------------------------------------------

/**
 * What the invoice is being checked against.
 *
 * The desktop has no reader for the order's own PDF, so this shows the order's
 * record instead — its figures beside the invoice's, which is what the check
 * is actually for. More than one linked order gets a selector, as on the web.
 */
@Composable
private fun OrderPane(state: InvoicesUiState, review: ReviewOverlay, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val linked = review.linkedPos
    if (linked.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitStatusPill(label = "No PO", tone = StatusTone.Rejected)
                PaneMessage("This invoice has no purchase order behind it.")
            }
        }
        return
    }
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (linked.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                linked.forEachIndexed { index, po ->
                    ZillitButton(
                        text = po.poNumber.ifBlank { "PO-" + po.poId.take(PO_ID_CHARS) },
                        onClick = { onEvent(InvoicesEvent.SelectReviewPo(index)) },
                        variant = if (index == review.selectedPo) ButtonVariant.Primary else ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
        val po = review.activePo ?: return@Column
        OrderSummary(state, review.invoice, po)
    }
}

@Composable
private fun OrderSummary(state: InvoicesUiState, invoice: Invoice, po: LinkedPo) {
    val colors = ZillitTheme.colors
    val currency = invoice.currency.ifBlank { state.projectCurrency }
    val poTotal = po.poGrossTotal
    val variance = poTotal?.let { invoice.grossAmount - it }
    ZillitText(
        text = po.poNumber.ifBlank { "PO-" + po.poId.take(PO_ID_CHARS) },
        style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = colors.accentText,
    )
    val vendor = state.vendors[po.poVendorId]?.name ?: state.vendorName(invoice)
    ZillitText(text = vendor, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary)
    Spacer(Modifier.height(ZillitTheme.spacing.xs))
    CompareRow("PO total", Money.format(poTotal, currency))
    CompareRow("Invoice gross", Money.format(invoice.grossAmount, currency))
    variance?.let {
        val over = it > VARIANCE_TOLERANCE
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = "Variance",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = (if (it > 0) "+" else "") + Money.format(it, currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = if (over) colors.danger else colors.success,
            )
        }
        if (over) {
            ZillitNotice(
                text = "The invoice is over the order by ${Money.format(it, currency)}.",
                tone = StatusTone.Pending,
            )
        }
    }
}

@Composable
private fun CompareRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric)
    }
}

// -- the invoice's own record -------------------------------------------------

@Composable
private fun RecordPane(state: InvoicesUiState, review: ReviewOverlay) {
    val invoice = review.invoice
    val colors = ZillitTheme.colors
    val currency = invoice.currency.ifBlank { state.projectCurrency }
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = invoice.displayNumber,
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
            )
            ZillitText(
                text = invoice.description.ifBlank { "No description" },
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitStatusPill(label = invoice.statusLabel, tone = StatusTone.Pending)
                if (!review.hasPo) ZillitStatusPill(label = "No PO", tone = StatusTone.Rejected)
            }
            if (invoice.status == com.zillit.desktop.feature.invoices.domain.InvoiceStatus.Held) {
                ZillitNotice(
                    text = listOf(invoice.holdReason, invoice.holdNote).filter { it.isNotBlank() }
                        .joinToString(" · ").ifBlank { "On hold" },
                    tone = StatusTone.Pending,
                )
            }
            Spacer(Modifier.height(ZillitTheme.spacing.xs))
            Fact("Vendor", state.vendorName(invoice))
            Fact("Department", state.departmentName(invoice.departmentId))
            Fact("Pay method", invoice.payMethod.label)
            Fact("Currency", currency)
            Fact("Net", Money.format(invoice.netAmount, currency))
            Fact("Tax", Money.format(invoice.taxAmount, currency))
            Fact("Gross", Money.format(invoice.grossAmount, currency))
            Fact("Invoice date", InvoiceFormat.date(invoice.invoiceDateMs))
            Fact("Due", InvoiceFormat.date(invoice.dueDateMs))
            Fact("Raised by", review.nameOf(invoice.userId))
            if (invoice.updatedBy.isNotBlank()) Fact("Last change", review.nameOf(invoice.updatedBy))
        }
        ZillitScrollRail(scroll)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(FACT_LABEL),
            maxLines = 1,
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

// -- the decision -------------------------------------------------------------

/**
 * What can be done from here — routed by the PO, as the web routes it.
 *
 * A matched invoice is confirmed and sent on, or (with override rights)
 * confirmed and pushed past the chain. An unmatched one can still go for
 * approval, and only an override user may skip it. A held invoice is released
 * from the queue first, so it offers nothing here.
 */
@Composable
private fun ReviewActions(state: InvoicesUiState, review: ReviewOverlay, onEvent: (InvoicesEvent) -> Unit) {
    val canOverride = state.viewer.canOverride
    if (!review.isOnHold) {
        ZillitButton(
            text = "Hold",
            onClick = { onEvent(InvoicesEvent.StartHold(review.invoice)) },
            variant = ButtonVariant.Secondary,
            enabled = !review.acting,
        )
    }
    ZillitButton(
        text = "Close",
        onClick = { onEvent(InvoicesEvent.CloseReview) },
        variant = ButtonVariant.Tertiary,
        enabled = !review.acting,
    )
    if (review.isOnHold) return
    if (canOverride) {
        ZillitButton(
            text = if (review.hasPo) "Confirm & override" else "Override",
            onClick = { onEvent(InvoicesEvent.ReviewOverride) },
            variant = ButtonVariant.Secondary,
            enabled = !review.acting,
        )
    }
    ZillitButton(
        text = if (review.hasPo) "Confirm & send for approval" else "Send for approval",
        onClick = { onEvent(InvoicesEvent.ReviewSendToApproval) },
        loading = review.acting,
        enabled = !review.acting,
    )
}

@Composable
private fun PaneMessage(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(ZillitTheme.spacing.lg),
    )
}

private val OVERLAY_WIDTH = 1280.dp
private val OVERLAY_HEIGHT = 860.dp
private val PANE_HEIGHT = 640.dp
private val DETAIL_WIDTH = 340.dp
private val FACT_LABEL = 96.dp
private val PANE_ICON = 14.dp
private const val PO_ID_CHARS = 5
private const val VARIANCE_TOLERANCE = 0.01
