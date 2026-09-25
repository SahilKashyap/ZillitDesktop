// The side-by-side pre-approval review — the web's `POMatchingOverlay`.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.ReviewOverlay
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

/**
 * Reviewing one pre-approval invoice against the order it claims to be for.
 *
 * Three panes, as the web has them: the invoice PDF on the left, the order's
 * own PDF in the middle, and the invoice's record on the right with the
 * decision under it. The decision is routed by the PO, not the pay method —
 * a matched invoice is confirmed from the "Confirm Match" menu, an unmatched
 * one is sent for approval or (with the rights) overridden
 * (`POMatchingOverlay.jsx:673-748`). There is no Cancel: the overlay closes
 * from its X or a click outside, as on the web.
 */
@Composable
internal fun PoReviewOverlay(
    state: InvoicesUiState,
    review: ReviewOverlay,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val invoice = review.invoice
    ZillitDialogShell(
        title = str(S.desktop_review_named, invoice.displayNumber),
        subtitle = str(S.desktop_inv_review_subtitle),
        visible = true,
        onDismiss = { if (!review.acting) onEvent(InvoicesEvent.CloseReview) },
        icon = ZillitIcons.Receipt,
        width = OVERLAY_WIDTH,
        maxHeight = OVERLAY_HEIGHT,
        scrollable = false,
        actions = { if (!review.loading) ReviewActions(state, review, onEvent) },
    ) {
        if (review.loading) {
            // The whole overlay waits on the full record (`POMatchingOverlay.jsx:359-366`).
            Box(Modifier.fillMaxWidth().height(PANE_HEIGHT), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitSpinner()
                    PaneMessage(str(S.desktop_inv_loading_invoice_details))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(PANE_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Pane(str(S.desktop_inv_invoice_pdf), Modifier.weight(1f)) { DocumentPane(review) }
                Pane(
                    title = str(S.desktop_inv_purchase_order_pdf),
                    modifier = Modifier.weight(1f),
                    extra = { OrderPicker(review, onEvent) },
                ) { OrderPdfPane(review) }
                Pane(str(S.desktop_inv_invoice_details), Modifier.width(DETAIL_WIDTH)) {
                    RecordPane(state, review, onEvent)
                }
            }
        }
    }
    if (review.historyOpen) {
        HistorySheet(
            invoiceNumber = invoice.displayNumber,
            rows = review.history,
            loading = review.historyLoading,
            nameOf = { id -> review.names[id] ?: state.userNames[id] ?: id.ifBlank { str(S.desktop_unknown) } },
            onClose = { onEvent(InvoicesEvent.ReviewHideHistory) },
        )
    }
}

/** One of the three columns: a titled bar, then whatever it holds. */
@Composable
private fun Pane(
    title: String,
    modifier: Modifier,
    extra: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
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
                .height(PANE_BAR)
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md),
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
            extra()
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
            review.previewLoading -> PaneMessage(str(S.desktop_dm_loading_attachment))
            pages.isNotEmpty() -> PreviewPages(pages, attachment?.name.orEmpty())
            // The web's own two words for a missing document (`POMatchingOverlay.jsx:385-387`).
            attachment != null -> PaneMessage(str(S.desktop_attachment_load_failed))
            else -> PaneMessage(str(S.desktop_inv_no_attachment_available))
        }
    }
}

// -- the order's PDF ----------------------------------------------------------

/**
 * The order the middle pane shows: a tag for one linked order, a select for
 * several (`POMatchingOverlay.jsx:403-415`).
 */
@Composable
private fun OrderPicker(review: ReviewOverlay, onEvent: (InvoicesEvent) -> Unit) {
    val linked = review.linkedPos
    when {
        linked.size == 1 -> ZillitStatusPill(
            label = linked.first().let { it.poNumber.ifBlank { it.poId } },
            tone = StatusTone.Progress,
        )
        linked.size > 1 -> ZillitSelect(
            value = review.selectedPo.coerceIn(0, linked.lastIndex),
            options = linked.indices.toList(),
            onSelect = { onEvent(InvoicesEvent.SelectReviewPo(it)) },
            label = { index -> linked[index].let { it.poNumber.ifBlank { it.poId } } },
            modifier = Modifier.widthIn(max = PICKER_WIDTH),
        )
    }
}

/** "Loading PO PDF...", the order's pages, or why there are none (`POMatchingOverlay.jsx:417-429`). */
@Composable
private fun OrderPdfPane(review: ReviewOverlay) {
    val colors = ZillitTheme.colors
    val pages = remember(review.poPdf) { review.poPdf?.bytes?.let(::decodePreviewPages).orEmpty() }
    Box(Modifier.fillMaxSize().background(colors.surfaceSunken), contentAlignment = Alignment.Center) {
        when {
            review.poPdfLoading -> Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitSpinner()
                PaneMessage(str(S.desktop_inv_loading_po_pdf))
            }
            pages.isNotEmpty() -> PreviewPages(pages, review.activePo?.poNumber.orEmpty())
            review.hasPo -> PaneMessage(str(S.desktop_inv_failed_po_pdf))
            else -> PaneMessage(str(S.desktop_inv_no_po_matched))
        }
    }
}

// -- the invoice's own record -------------------------------------------------

/**
 * The right pane, section for section as the web lays it out
 * (`POMatchingOverlay.jsx:445-655`): number, description, status and PO tags
 * with Query and History; the vendor; the facts; the linked orders; the hold
 * reason; who created and last changed it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordPane(state: InvoicesUiState, review: ReviewOverlay, onEvent: (InvoicesEvent) -> Unit) {
    val invoice = review.invoice
    val colors = ZillitTheme.colors
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = invoice.invoiceNumber.ifBlank { "—" },
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accentText,
            )
            ZillitText(
                text = invoice.description.ifBlank { str(S.desktop_no_description) },
                style = ZillitTheme.typography.titleSmall,
                color = colors.textPrimary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
            review.linkedPos.forEach { po ->
                ZillitStatusPill(label = po.poNumber.ifBlank { po.poId }, tone = StatusTone.Progress)
            }
            if (!review.hasPo) ZillitStatusPill(label = str(S.desktop_no_po), tone = StatusTone.Rejected)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitButton(
                text = str(S.ah_cd_query),
                onClick = { onEvent(QueryEvent.Open(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Chat,
            )
            ZillitButton(
                text = str(S.history),
                onClick = { onEvent(InvoicesEvent.ReviewShowHistory) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Clock,
            )
        }
        ZillitDivider()
        RecordVendor(state, invoice)
        ZillitDivider()
        RecordFacts(state, invoice)
        LinkedPoCards(state, invoice, selected = review.selectedPo) { index, po, number ->
            onEvent(InvoicesEvent.OpenLinkedPo(po.poId, number, index))
        }
        HoldReasonBlock(state, invoice) { review.names[it] }
        ZillitDivider()
        AuditFooter(
            invoice = invoice,
            nameOf = { review.names[it] },
            designationOf = { review.designations[it] },
            showUpdated = invoice.updatedAtMs != null,
        )
    }
}

// -- the decision -------------------------------------------------------------

/**
 * The footer (`POMatchingOverlay.jsx:673-748`): Hold on the left (not on a
 * held invoice); with no PO, Override (override rights only) and Send for
 * Approval; with one, the "Confirm Match ▾" menu. Everything that advances
 * is hidden while the invoice is held — it is released from the queue first.
 */
@Composable
private fun RowScope.ReviewActions(state: InvoicesUiState, review: ReviewOverlay, onEvent: (InvoicesEvent) -> Unit) {
    val canOverride = state.viewer.canOverride
    if (!review.isOnHold) {
        ZillitButton(
            text = str(S.desktop_hold),
            onClick = { onEvent(InvoicesEvent.StartHold(review.invoice)) },
            variant = ButtonVariant.Secondary,
            enabled = !review.acting,
        )
    }
    Spacer(Modifier.weight(1f))
    if (review.isOnHold) return
    if (!review.hasPo) {
        if (canOverride) {
            ZillitButton(
                text = if (review.acting) str(S.txt_processing) else str(S.dm_nom_table_override),
                onClick = { onEvent(InvoicesEvent.ReviewOverride) },
                variant = ButtonVariant.Danger,
                enabled = !review.acting,
            )
        }
        ZillitButton(
            text = if (review.acting) str(S.txt_processing) else str(S.cs_send_for_approval),
            onClick = { onEvent(InvoicesEvent.ReviewSendToApproval) },
            enabled = !review.acting,
            loading = review.acting,
        )
    } else {
        ConfirmMatchMenu(canOverride, review.acting, onEvent)
    }
}

/**
 * "Confirm Match ▾" and its two choices: send through the approval chain, or
 * — override rights only — skip it (`POMatchingOverlay.jsx:722-747`).
 */
@Composable
private fun ConfirmMatchMenu(canOverride: Boolean, acting: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = if (acting) str(S.desktop_inv_confirming) else str(S.desktop_inv_confirm_match),
            onClick = { open = !open },
            enabled = !acting,
            loading = acting,
        )
        ZillitMenuSurface(expanded = open, onDismissRequest = { open = false }) {
            ConfirmChoice(
                title = str(S.desktop_inv_confirm_send_for_approval),
                detail = str(S.desktop_inv_route_through_chain),
                danger = false,
            ) {
                open = false
                onEvent(InvoicesEvent.ReviewSendToApproval)
            }
            if (canOverride) {
                ZillitDivider()
                ConfirmChoice(
                    title = str(S.desktop_inv_confirm_override),
                    detail = str(S.desktop_inv_send_to_approval_queue),
                    danger = true,
                ) {
                    open = false
                    onEvent(InvoicesEvent.ReviewOverride)
                }
            }
        }
    }
}

@Composable
private fun ConfirmChoice(title: String, detail: String, danger: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .widthIn(min = MENU_WIDTH)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (danger) colors.danger else colors.textPrimary,
        )
        ZillitText(text = detail, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
    }
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

private val OVERLAY_WIDTH = 1400.dp
private val OVERLAY_HEIGHT = 900.dp
private val PANE_HEIGHT = 660.dp
private val PANE_BAR = 48.dp
private val DETAIL_WIDTH = 400.dp
private val PANE_ICON = 14.dp
private val PICKER_WIDTH = 200.dp
private val MENU_WIDTH = 240.dp
