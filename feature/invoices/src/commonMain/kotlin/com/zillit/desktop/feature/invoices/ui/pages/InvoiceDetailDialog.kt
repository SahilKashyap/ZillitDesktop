// The shared detail dialog and its two sub-dialogs (history, reject).
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.feature.invoices.ui.decodePreviewPages
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.ResolvedTier
import com.zillit.desktop.feature.invoices.ui.InvoiceDetail
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

@Composable
internal fun InvoiceDetailDialog(state: InvoicesUiState, detail: InvoiceDetail, onEvent: (InvoicesEvent) -> Unit) {
    val invoice = detail.invoice
    val tiers = state.tiersOf(invoice)
    val viewer = state.viewer
    ZillitDialogShell(
        title = str(S.desktop_invoice_named, invoice.displayNumber),
        subtitle = state.vendorName(invoice),
        onDismiss = { onEvent(InvoicesEvent.CloseDetail) },
        visible = true,
        width = DETAIL_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.history),
                onClick = { onEvent(InvoicesEvent.ShowHistory) },
                variant = ButtonVariant.Tertiary,
            )
            if (InvoiceRules.showApproveReject(invoice, tiers, viewer)) {
                ZillitButton(
                    text = str(S.reject),
                    onClick = { onEvent(InvoicesEvent.StartReject) },
                    variant = ButtonVariant.Danger,
                    enabled = !detail.acting,
                )
                ZillitButton(
                    text = str(S.approve),
                    onClick = { onEvent(InvoicesEvent.Approve(invoice)) },
                    loading = detail.acting,
                )
            }
            if (InvoiceRules.showOverride(invoice, tiers, viewer)) {
                ZillitButton(
                    text = str(S.dm_nom_table_override),
                    onClick = { onEvent(InvoicesEvent.Override(invoice)) },
                    variant = ButtonVariant.Secondary,
                    loading = detail.acting,
                )
            }
            if (InvoiceRules.showOverrideAndPay(invoice, viewer) && !invoice.isApproved) {
                ZillitButton(
                    text = str(S.desktop_override_and_pay),
                    onClick = { onEvent(InvoicesEvent.OverrideAndPay(invoice)) },
                    loading = detail.acting,
                )
            }
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(InvoicesEvent.CloseDetail) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            AttachmentPane(detail, onEvent, Modifier.weight(1f))
            DetailPane(state, detail, tiers, Modifier.width(DETAIL_PANE_WIDTH))
        }
    }
    if (detail.historyOpen) HistoryDialog(detail, onEvent)
    if (detail.rejecting) RejectDialog(detail, onEvent)
}

/**
 * The document itself, scrolled like the web's iframe.
 *
 * Pages are stacked rather than paged through: an invoice's second page is
 * usually its line items, and a reader scrolling a browser's PDF view does
 * not click "next page" to reach them.
 */
@Composable
private fun PreviewPages(pages: List<ImageBitmap>, name: String) {
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)
                .padding(ZillitTheme.spacing.sm),
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

@Composable
private fun AttachmentPane(detail: InvoiceDetail, onEvent: (InvoicesEvent) -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val attachment = detail.invoice.firstAttachment
    // Rendering a long PDF is not free, so it is done once per fetched file.
    val pages = remember(detail.preview) { detail.preview?.bytes?.let(::decodePreviewPages).orEmpty() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .background(colors.surfaceSunken, ZillitTheme.shapes.medium)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            when {
                attachment == null -> ZillitText(
                    text = if (detail.loading) str(S.ah_loading) else str(S.desktop_no_document_attached),
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                pages.isNotEmpty() -> PreviewPages(pages, attachment.name)
                detail.previewLoading -> ZillitSpinner()
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = attachment.name.ifBlank { attachment.extension.uppercase().ifBlank { str(S.document) } },
                        style = ZillitTheme.typography.titleSmall,
                        color = colors.textPrimary,
                    )
                    ZillitText(
                        text = when {
                            detail.previewFailed -> str(S.desktop_inv_could_not_load_preview)
                            attachment.isPdf -> str(S.desktop_inv_pdf_not_rendered)
                            else -> attachment.mimeType
                        },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
        if (attachment != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = attachment.name.ifBlank { str(S.attachment) },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = str(S.desktop_save_and_open),
                    onClick = { onEvent(InvoicesEvent.OpenAttachment) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    loading = detail.opening,
                )
            }
        }
    }
}

@Composable
private fun DetailPane(state: InvoicesUiState, detail: InvoiceDetail, tiers: List<ResolvedTier>, modifier: Modifier) {
    val invoice = detail.invoice
    val colors = ZillitTheme.colors
    val currency = invoice.currency.ifBlank { state.projectCurrency }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(text = invoice.displayNumber, style = ZillitTheme.typography.titleLarge, color = colors.gold)
        if (invoice.description.isNotBlank()) {
            ZillitText(
                text = invoice.description,
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        Badges(state, invoice, tiers)
        VendorBlock(state, invoice)
        if (invoice.status == InvoiceStatus.Held) {
            ZillitNotice(
                text = str(S.desktop_call_on_hold) + invoice.holdReason.humanised().let { if (it.isBlank()) {
                    ""
                } else {
                    " — $it"
                }} +
                    invoice.holdNote.let { if (it.isBlank()) "" else ": $it" },
                tone = StatusTone.Escalated,
            )
        }
        ZillitDivider()
        val bank = state.banks.firstOrNull { it.id == invoice.bankId }?.displayName ?: invoice.bankId.ifBlank { "—" }
        val episode = if (state.viewer.isTelevision) {
            listOf(str(S.episode) to invoice.episode.ifBlank { "—" })
        } else {
            emptyList()
        }
        FieldGrid(
            listOf(
                str(S.department) to state.departmentName(invoice.departmentId),
                str(S.asset_currency) to currency,
                str(S.desktop_payment_method) to invoice.payMethod.label,
                str(S.desktop_bank) to bank,
            ) + episode,
        )
        ZillitDivider()
        FieldGrid(
            listOf(
                str(S.desktop_net) to InvoiceFormat.money(invoice.netAmount, currency),
                str(S.ah_lbl_vat) to InvoiceFormat.money(invoice.taxAmount, currency),
                str(S.desktop_gross) to InvoiceFormat.money(invoice.grossAmount, currency),
            ),
        )
        FieldGrid(
            listOf(
                str(S.desktop_invoice_date_title) to InvoiceFormat.date(invoice.invoiceDateMs),
                str(S.desktop_due_date_title) to InvoiceFormat.date(invoice.dueDateMs),
                str(S.ah_lbl_eff_date) to InvoiceFormat.date(invoice.effectiveDateMs),
            ),
        )
        if (invoice.linkedPos.isNotEmpty()) {
            ZillitDivider()
            ZillitSectionLabel(str(S.desktop_inv_linked_purchase_orders))
            invoice.linkedPos.forEach { po ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceSunken, ZillitTheme.shapes.medium)
                        .padding(ZillitTheme.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = po.poNumber.ifBlank { "PO-" + po.poId.take(PO_ID_CHARS) },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = state.vendors[po.poVendorId]?.name ?: "",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    ZillitText(
                        text = InvoiceFormat.money(po.poGrossTotal, currency),
                        style = ZillitTheme.typography.numeric,
                        color = colors.textPrimary,
                    )
                }
            }
        }
        if (InvoiceRules.showChain(invoice, tiers)) {
            ZillitDivider()
            ZillitSectionLabel(str(S.ah_run_approval_chain))
            ApprovalChainPanel(detail, tiers)
        }
        if (invoice.isRejected && invoice.rejectionReason.isNotBlank()) {
            ZillitNotice(
                text = str(
                    S.desktop_inv_rejected_by_at,
                    detail.nameOf(invoice.rejectedBy),
                    InvoiceFormat.dateTime(invoice.rejectedAtMs),
                ) +
                    "\n${invoice.rejectionReason}",
                tone = StatusTone.Rejected,
            )
        }
        ZillitDivider()
        FieldGrid(
            listOf(
                str(S.cs_created_by) to
                    "${detail.nameOf(invoice.userId)} · ${InvoiceFormat.dateTime(invoice.createdAtMs)}",
                str(S.desktop_updated_by) to if (invoice.updatedBy.isBlank()) {
                    "—"
                } else {
                    "${detail.nameOf(invoice.updatedBy)} · ${InvoiceFormat.dateTime(invoice.updatedAtMs)}"
                },
            ),
        )
    }
}

@Composable
private fun Badges(state: InvoicesUiState, invoice: Invoice, tiers: List<ResolvedTier>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            invoice.payMethod.isUrgent -> BadgePill(InvoiceRules.urgentBadge(invoice))
            invoice.isApproved -> ZillitStatusPill(label = str(S.approved), tone = StatusTone.Done)
            invoice.status == InvoiceStatus.Approval && tiers.isNotEmpty() ->
                ZillitStatusPill(
                    label = str(S.desktop_inv_pending_of, invoice.approvedCount, tiers.size),
                    tone = StatusTone.Pending,
                )
            else -> ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
        }
        if (!invoice.payMethod.isUrgent) PoCell(invoice)
        if (invoice.ocrConfidence != null) ZillitStatusPill(label = str(S.desktop_ocr), tone = StatusTone.Escalated)
        if (state.viewer.isAccountant && invoice.approvalStatus.label != invoice.statusLabel) {
            ZillitStatusPill(label = invoice.approvalStatus.label, tone = StatusTone.Neutral)
        }
    }
}

@Composable
private fun VendorBlock(state: InvoicesUiState, invoice: Invoice) {
    val colors = ZillitTheme.colors
    val vendor = state.vendors[invoice.vendorId]
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = state.vendorName(invoice),
            style = ZillitTheme.typography.titleSmall,
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

@Composable
private fun FieldGrid(fields: List<Pair<String, String>>) {
    val colors = ZillitTheme.colors
    fields.chunked(FIELDS_PER_ROW).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            row.forEach { (label, value) ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                    ZillitText(
                        text = value,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textPrimary,
                        maxLines = 2,
                    )
                }
            }
            repeat(FIELDS_PER_ROW - row.size) { Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ApprovalChainPanel(detail: InvoiceDetail, tiers: List<ResolvedTier>) {
    val colors = ZillitTheme.colors
    val invoice = detail.invoice
    val done = invoice.approvals.associateBy { it.tierNumber }
    val current = tiers.map { it.number }.firstOrNull { it !in done }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        tiers.forEach { tier ->
            val approval = done[tier.number]
            val (fill, ring) = when {
                approval != null -> colors.success to colors.success
                tier.number == current && invoice.status == InvoiceStatus.Approval -> colors.warning to colors.warning
                else -> colors.surfaceSunken to colors.border
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(TIER_DOT).background(fill, CircleShape).border(2.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = tier.number.toString(),
                        style = ZillitTheme.typography.labelSmall,
                        color = if (approval != null || tier.number == current) {
                            colors.textOnAccent
                        } else {
                            colors.textMuted
                        },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = if (approval != null) {
                            "${detail.nameOf(approval.userId)} · ${InvoiceFormat.dateTime(approval.approvedAtMs)}"
                        } else {
                            tier.userIds.joinToString(", ") { detail.nameOf(it) }
                        },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textPrimary,
                        maxLines = 2,
                    )
                    ZillitText(
                        text = when {
                            approval != null -> str(S.approved)
                            tier.number == current -> str(S.av_subtab_awaiting_approval)
                            else -> str(S.pending)
                        },
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

// Sub-dialogs ----------------------------------------------------------------

@Composable
private fun HistoryDialog(detail: InvoiceDetail, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.history),
        subtitle = str(S.desktop_invoice_named, detail.invoice.displayNumber),
        onDismiss = { onEvent(InvoicesEvent.HideHistory) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(InvoicesEvent.HideHistory) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        val rows = detail.history
        when {
            detail.historyLoading || rows == null -> LoadingRow()
            rows.isEmpty() -> ZillitText(
                text = str(S.history_no_history),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                rows.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        ZillitText(
                            text = entry.action.humanised(),
                            style = ZillitTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                        )
                        ZillitText(
                            text = "${detail.nameOf(entry.actionBy)} · ${InvoiceFormat.dateTime(entry.actionAtMs)}",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        if (entry.note.isNotBlank()) {
                            ZillitText(
                                text = entry.note,
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RejectDialog(detail: InvoiceDetail, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.ah_reject_invoice),
        subtitle = str(S.desktop_invoice_named, detail.invoice.displayNumber),
        onDismiss = { onEvent(InvoicesEvent.CancelReject) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelReject) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.reject),
                onClick = { onEvent(InvoicesEvent.ConfirmReject) },
                variant = ButtonVariant.Danger,
                enabled = detail.rejectReason.isNotBlank(),
                loading = detail.acting,
            )
        },
    ) {
        ZillitTextField(
            value = detail.rejectReason,
            onValueChange = { onEvent(InvoicesEvent.RejectReasonChanged(it)) },
            label = str(S.docusign_decline_reason_label),
            placeholder = str(S.desktop_inv_why_rejected_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** `returned_to_approval` → "Returned to approval". */
internal fun String.humanised(): String =
    trim().replace('_', ' ').replace('-', ' ').lowercase().replaceFirstChar { it.uppercase() }

private const val FIELDS_PER_ROW = 3
private const val PO_ID_CHARS = 5
private val DETAIL_WIDTH = 1180.dp
private val DETAIL_PANE_WIDTH = 480.dp
private val PREVIEW_HEIGHT = 560.dp
private val TIER_DOT = 28.dp
