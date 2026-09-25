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
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.ResolvedTier
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.InvoiceDetail
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.PaymentsEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.feature.invoices.domain.PoPills
import com.zillit.desktop.feature.invoices.domain.InvoiceLabels

@Composable
internal fun InvoiceDetailDialog(state: InvoicesUiState, detail: InvoiceDetail, onEvent: (InvoicesEvent) -> Unit) {
    val invoice = detail.invoice
    val tiers = state.tiersOf(invoice)
    val viewer = state.viewer
    // A closed cost-report period drops every mutating action (`InvoiceDetailModal.jsx:871-874`).
    val locked = state.isLocked(invoice)
    val department = !state.isAccountant
    // Query is not an Approval Queue action, on either side (ZL-20913, `allowQuery`).
    val allowQuery = if (department) {
        state.departmentTab != DepartmentTab.ApprovalQueue
    } else {
        state.page != AccountantPage.ApprovalQueue
    }
    // The department board hands its modal a Delete only when the row may be
    // deleted (`onDelete={detailRow.canDelete ? … }`), and never in a locked period.
    // The accountant Approval Queue hands it one too, on its own gate
    // (`ApprovalPage.jsx:754`); the Register and Payments never do.
    val canDelete = !locked && if (department) {
        state.departmentCanDelete(invoice)
    } else {
        state.page == AccountantPage.ApprovalQueue && queueCanDelete(state, invoice)
    }
    // …and no Override or Override & Pay at all (`DepartmentInvoiceModule.jsx:1246-1277`).
    val decisions = detail.decisions && !locked
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
            if (allowQuery) {
                ZillitButton(
                    text = str(S.ah_query_label),
                    onClick = { onEvent(QueryEvent.Open(invoice)) },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.Chat,
                )
            }
            // Kept to the left, away from Approve: destructive and irreversible.
            if (canDelete) {
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                    variant = ButtonVariant.Danger,
                    enabled = !detail.acting,
                )
            }
            if (decisions && InvoiceRules.showApproveReject(invoice, tiers, viewer)) {
                ZillitButton(
                    text = str(S.reject),
                    onClick = { onEvent(InvoicesEvent.StartReject) },
                    variant = ButtonVariant.Danger,
                    enabled = !detail.acting,
                )
                ZillitButton(
                    text = if (detail.acting) str(S.desktop_inv_approving) else str(S.approve),
                    onClick = { onEvent(InvoicesEvent.Approve(invoice)) },
                    loading = detail.acting,
                )
            }
            if (decisions && !department && InvoiceRules.showOverride(invoice, tiers, viewer)) {
                ZillitButton(
                    text = if (detail.acting) str(S.desktop_ce_cards_overriding) else str(S.dm_nom_table_override),
                    onClick = { onEvent(InvoicesEvent.Override(invoice)) },
                    loading = detail.acting,
                )
            }
            // `showOverrideAndPay` alone — no "not approved" test here (`InvoiceDetailModal.jsx:910`).
            if (decisions && !department && InvoiceRules.showOverrideAndPay(invoice, viewer)) {
                ZillitButton(
                    text = if (detail.acting) str(S.txt_processing) else str(S.desktop_override_and_pay),
                    onClick = { onEvent(InvoicesEvent.OverrideAndPay(invoice)) },
                    variant = ButtonVariant.Danger,
                    loading = detail.acting,
                )
            }
            // Payment Runs' wire or faster payment: its own Mark Paid, until it is
            // paid (`InvoiceDetailModal.jsx:927-936`, `PaymentsPage.jsx:2399-2403`).
            if (detail.markPaid && !locked && invoice.status != InvoiceStatus.Paid) {
                val marking = invoice.id in state.pay.markingPaid
                ZillitButton(
                    text = if (marking) str(S.desktop_payroll_marking) else str(S.desktop_mark_paid_title),
                    onClick = { onEvent(PaymentsEvent.MarkPaidFromDetail(invoice)) },
                    variant = ButtonVariant.Danger,
                    enabled = state.viewer.canOperateRuns && !marking,
                    loading = marking,
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
            DetailPane(state, detail, tiers, onEvent, Modifier.width(DETAIL_PANE_WIDTH))
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
internal fun PreviewPages(pages: List<ImageBitmap>, name: String) {
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

/**
 * The right pane, section for section as the web's `InvoiceDetailModal` lays
 * it out (`InvoiceDetailModal.jsx:564-766`): number, description and badges;
 * the vendor; then — under the period-lock banner when the invoice is in a
 * closed period — the hold banner, the facts, the linked orders, the approval
 * chain, the rejection, and who created and last changed it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailPane(
    state: InvoicesUiState,
    detail: InvoiceDetail,
    tiers: List<ResolvedTier>,
    onEvent: (InvoicesEvent) -> Unit,
    modifier: Modifier,
) {
    val invoice = detail.invoice
    val colors = ZillitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = invoice.invoiceNumber.ifBlank { "—" },
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accentText,
            )
            ZillitText(
                text = invoice.description.ifBlank { str(S.desktop_no_description) },
                style = ZillitTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Badges(invoice, tiers)
        }
        ZillitDivider()
        RecordVendor(state, invoice)
        ZillitDivider()
        if (state.isLocked(invoice)) InvoiceLockBanner(state)
        if (invoice.status == InvoiceStatus.Held) HeldBanner(state, detail)
        RecordFacts(state, invoice)
        LinkedPoCards(state, invoice, selected = null) { _, po, number ->
            onEvent(InvoicesEvent.OpenLinkedPo(po.poId, number))
        }
        if (InvoiceRules.showChain(invoice, tiers)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                RecordCaption(str(S.ah_approval_chain))
                ApprovalChainPanel(detail, tiers)
            }
        }
        if (invoice.rejectionReason.isNotBlank()) RejectionBlock(detail)
        ZillitDivider()
        AuditFooter(
            invoice = invoice,
            nameOf = { detail.names[it] },
            designationOf = { detail.designations[it] },
            showUpdated = invoice.updatedBy.isNotBlank(),
        )
    }
}

/**
 * The header's badges, in the web's three shapes (`InvoiceDetailModal.jsx:585-607`):
 *
 * - a wire or cheque: the request ("No PO · " when nothing is linked), the
 *   linked POs, and "Approved" once approved or overridden;
 * - a chain in progress: "Pending x/y", the linked POs, or the typed number;
 * - anything else: the status, the linked POs, or "No PO".
 */
@Composable
private fun Badges(invoice: Invoice, tiers: List<ResolvedTier>) {
    val linked = @Composable {
        invoice.linkedPos.forEach { po ->
            ZillitStatusPill(label = po.poNumber.ifBlank { po.poId }, tone = StatusTone.Progress)
        }
    }
    when {
        invoice.isUrgentRaw -> {
            BadgePill(PoPills.registerUrgentStatus(invoice))
            linked()
            if (invoice.approvalStatus == ApprovalStatus.Approved || invoice.status == InvoiceStatus.Override) {
                ZillitStatusPill(label = str(S.approved), tone = StatusTone.Ready)
            }
        }
        invoice.status == InvoiceStatus.Approval && tiers.isNotEmpty() -> {
            ZillitStatusPill(
                label = str(S.desktop_inv_pending_of, invoice.approvedCount, tiers.size),
                tone = StatusTone.Pending,
            )
            linked()
            if (invoice.linkedPos.isEmpty() && invoice.poNumber.isNotBlank()) {
                ZillitStatusPill(label = invoice.poNumber, tone = StatusTone.Progress)
            }
        }
        else -> {
            ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
            linked()
            if (!invoice.hasMatchedPo) ZillitStatusPill(label = str(S.desktop_no_po), tone = StatusTone.Rejected)
        }
    }
}

/**
 * "On Hold", the reason (or "No reason provided"), and the note with any user
 * id the directory knows swapped for the name (`InvoiceDetailModal.jsx:614-620`).
 */
@Composable
private fun HeldBanner(state: InvoicesUiState, detail: InvoiceDetail) {
    val invoice = detail.invoice
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warningSoft, ZillitTheme.shapes.medium)
            .border(1.dp, colors.warning.copy(alpha = HELD_BORDER_ALPHA), ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(S.desktop_on_hold_title).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.warning,
        )
        ZillitText(
            text = invoice.holdReason.takeIf { it.isNotBlank() }?.let(InvoiceLabels::format)
                ?: str(S.desktop_inv_no_hold_reason),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
        )
        if (invoice.holdNote.isNotBlank()) {
            ZillitText(
                text = InvoiceLabels.resolveUserIds(invoice.holdNote) { detail.names[it] ?: state.userNames[it] },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** "Rejection Reason", the reason, and who rejected it when (`InvoiceDetailModal.jsx:797-808`). */
@Composable
private fun RejectionBlock(detail: InvoiceDetail) {
    val invoice = detail.invoice
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.dangerSoft, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(S.cs_rejection_reason).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.danger,
        )
        ZillitText(text = invoice.rejectionReason, style = ZillitTheme.typography.bodySmall, color = colors.textPrimary)
        if (invoice.rejectedBy.isNotBlank()) {
            ZillitText(
                text = str(
                    S.desktop_inv_by_at,
                    detail.nameOf(invoice.rejectedBy),
                    InvoiceFormat.dateTime(invoice.rejectedAtMs),
                ),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * The chain laid across, as the web draws it (`InvoiceDetailModal.jsx:746-797`):
 * a green tick for each signed tier with who signed and their designation, an
 * amber dot for the tier waiting now, grey for the rest, joined by lines —
 * green after a signed tier.
 *
 * A waiting tier names the people who can sign it, with the first one's
 * designation. The web means to do the same but reads a shape its tier
 * resolver never returns, so it always prints "Awaiting"; that is kept only
 * for a tier nobody is configured on.
 */
@Composable
private fun ApprovalChainPanel(detail: InvoiceDetail, tiers: List<ResolvedTier>) {
    val colors = ZillitTheme.colors
    val invoice = detail.invoice
    val done = invoice.approvals.associateBy { it.tierNumber }
    val next = ApprovalChain.nextTier(tiers, invoice.approvals)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Top,
    ) {
        tiers.forEachIndexed { index, tier ->
            val approval = done[tier.number]
            val current = approval == null && tier.number == next
            Column(
                modifier = Modifier.widthIn(min = TIER_MIN_WIDTH),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                Box(
                    modifier = Modifier
                        .size(TIER_DOT)
                        .background(
                            when {
                                approval != null -> colors.success
                                current -> colors.warning
                                else -> colors.surfaceSunken
                            },
                            CircleShape,
                        )
                        .then(
                            if (approval == null && !current) {
                                Modifier.border(2.dp, colors.border, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        approval != null ->
                            ZillitIcon(icon = ZillitIcons.Check, tint = colors.textOnAccent, size = TIER_GLYPH)
                        current -> Box(Modifier.size(CURRENT_DOT).background(colors.textOnAccent, CircleShape))
                    }
                }
                val (who, designation) = when {
                    approval != null ->
                        detail.nameOf(approval.userId) to detail.designations[approval.userId]
                    else -> tier.userIds.map { detail.nameOf(it) }.joinToString(", ")
                        .ifBlank { str(S.ds_sent_filter_awaiting) } to
                        tier.userIds.firstOrNull()?.let { detail.designations[it] }.takeIf { current }
                }
                val tone = when {
                    approval != null -> colors.success
                    current -> colors.warning
                    else -> colors.textMuted
                }
                ZillitText(
                    text = who,
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = tone,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                designation?.takeIf { it.isNotBlank() }?.let {
                    ZillitText(
                        text = it,
                        style = ZillitTheme.typography.labelSmall,
                        color = tone,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            if (index < tiers.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(top = TIER_DOT / 2)
                        .width(CONNECTOR_WIDTH)
                        .height(2.dp)
                        .background(if (approval != null) colors.success else colors.border),
                )
            }
        }
    }
}

// Sub-dialogs ----------------------------------------------------------------

@Composable
private fun HistoryDialog(detail: InvoiceDetail, onEvent: (InvoicesEvent) -> Unit) {
    HistorySheet(
        invoiceNumber = detail.invoice.displayNumber,
        rows = detail.history,
        loading = detail.historyLoading,
        nameOf = detail::nameOf,
        onClose = { onEvent(InvoicesEvent.HideHistory) },
    )
}

/** An invoice's audit trail, newest first — shared by the detail dialog and the coding screen. */
@Composable
internal fun HistorySheet(
    invoiceNumber: String,
    rows: List<HistoryEntry>?,
    loading: Boolean,
    nameOf: (String) -> String,
    onClose: () -> Unit,
    subtitle: String? = null,
) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.history),
        subtitle = subtitle ?: str(S.desktop_invoice_named, invoiceNumber),
        onDismiss = onClose,
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = onClose,
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        when {
            loading || rows == null -> LoadingRow()
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
                            text = "${nameOf(entry.actionBy)} · ${InvoiceFormat.dateTime(entry.actionAtMs)}",
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

/**
 * The reject sub-dialog, in the web's words (`InvoiceDetailModal.jsx:108-154`):
 * "Reject `ref`", why a reason is needed, the reason — required, and said so
 * in red until there is one — and "Reject Invoice" ("Rejecting..." while it runs).
 */
@Composable
private fun RejectDialog(detail: InvoiceDetail, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_reject_named, detail.invoice.displayNumber),
        onDismiss = { onEvent(InvoicesEvent.CancelReject) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelReject) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (detail.acting) str(S.desktop_ce_cards_rejecting) else str(S.ah_reject_invoice),
                onClick = { onEvent(InvoicesEvent.ConfirmReject) },
                variant = ButtonVariant.Danger,
                enabled = detail.rejectReason.isNotBlank() && !detail.acting,
                loading = detail.acting,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_inv_reject_prompt),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = detail.rejectReason,
            onValueChange = { onEvent(InvoicesEvent.RejectReasonChanged(it)) },
            placeholder = str(S.desktop_inv_rejection_reason_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (detail.rejectReason.isBlank()) {
            ZillitText(
                text = str(S.desktop_inv_rejection_reason_required),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/** `returned_to_approval` → "Returned to approval". */
internal fun String.humanised(): String =
    trim().replace('_', ' ').replace('-', ' ').lowercase().replaceFirstChar { it.uppercase() }

private val DETAIL_WIDTH = 1180.dp
private val DETAIL_PANE_WIDTH = 480.dp
private val PREVIEW_HEIGHT = 560.dp
private val TIER_DOT = 28.dp
private val TIER_MIN_WIDTH = 80.dp
private val TIER_GLYPH = 14.dp
private val CURRENT_DOT = 10.dp
private val CONNECTOR_WIDTH = 32.dp
private const val HELD_BORDER_ALPHA = 0.5f
