package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.WireAttachment
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.PaymentsEvent
import com.zillit.desktop.feature.invoices.ui.WireAttachmentPreview
import com.zillit.desktop.feature.invoices.ui.WireAttachmentsView
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

/**
 * "Wire Confirmation — {ref}" — the bank confirmations on a paid wire
 * (`WireAttachmentsModal`, `PaymentsPage.jsx:704-867`): the vendor and the
 * reference, each file with View and Remove, and Add Attachment.
 */
@Composable
internal fun WireAttachmentsDialog(
    state: InvoicesUiState,
    view: WireAttachmentsView,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val invoice = view.invoice
    ZillitDialogShell(
        title = str(S.desktop_inv_wire_confirmation_title, invoice.displayNumber),
        // `vendorMap[vendor_id] || "Unknown"` (`PaymentsPage.jsx:772`).
        subtitle = state.vendors[invoice.vendorId]?.name?.ifBlank { null } ?: str(S.desktop_unknown),
        visible = true,
        onDismiss = { onEvent(PaymentsEvent.CloseWireAttachments) },
        icon = ZillitIcons.Paperclip,
        width = WIRE_DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.desktop_inv_add_attachment),
                onClick = { onEvent(PaymentsEvent.AddWireAttachment) },
                enabled = !view.uploading,
                loading = view.uploading,
            )
        },
    ) {
        MutedLine("${invoice.displayNumber} · ${invoice.description.ifBlank { "—" }}")
        ZillitDivider()
        if (view.attachments.isEmpty() && !view.uploading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                MutedLine(str(S.desktop_inv_no_wire_attachments))
            }
        }
        view.attachments.forEach { attachment -> AttachmentRow(view, attachment, onEvent) }
        if (view.uploading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitSpinner()
                MutedLine(str(S.ah_uploading))
            }
        }
    }
    view.viewing?.let { WireAttachmentViewer(it, onEvent) }
}

@Composable
private fun AttachmentRow(view: WireAttachmentsView, attachment: WireAttachment, onEvent: (InvoicesEvent) -> Unit) {
    val removing = view.removing == attachment.key
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = attachment.name,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            attachment.detail.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
        }
        ZillitButton(
            text = str(S.view),
            onClick = { onEvent(PaymentsEvent.ViewWireAttachment(attachment)) },
            size = ButtonSize.Small,
        )
        // Every Remove waits while any one is in flight (ZL-20536).
        ZillitButton(
            text = if (removing) str(S.desktop_tax_removing) else str(S.remove),
            onClick = { onEvent(PaymentsEvent.RemoveWireAttachment(attachment.key)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            enabled = view.removing == null,
            loading = removing,
        )
    }
}

/**
 * One confirmation, shown — the web's `WireAttachmentViewer`: an image or a
 * PDF's pages, "Loading attachment…" first, "Failed to load attachment" when
 * the file will not come, and "Preview not available for this file type."
 * with a download for anything else.
 */
@Composable
private fun WireAttachmentViewer(preview: WireAttachmentPreview, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val pages = remember(preview.bytes) { preview.bytes?.bytes?.let(::decodePreviewPages).orEmpty() }
    ZillitDialogShell(
        title = preview.attachment.name,
        visible = true,
        onDismiss = { onEvent(PaymentsEvent.CloseWireAttachmentViewer) },
        icon = ZillitIcons.File,
        width = VIEWER_WIDTH,
        scrollable = false,
        actions = {
            if (preview.bytes != null) {
                ZillitButton(
                    text = str(S.download),
                    onClick = { onEvent(PaymentsEvent.DownloadWireAttachment) },
                    size = ButtonSize.Small,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(VIEWER_HEIGHT)
                .background(colors.surfaceSunken, ZillitTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            when {
                preview.loading -> MutedLine(str(S.desktop_dm_loading_attachment))
                preview.failed -> ViewerNote(str(S.desktop_attachment_load_failed))
                pages.isNotEmpty() -> PreviewPages(pages, preview.attachment.name)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ViewerNote(str(S.desktop_dm_preview_not_available_for_this_file_type))
                    ZillitButton(
                        text = str(S.desktop_inv_download_file),
                        onClick = { onEvent(PaymentsEvent.DownloadWireAttachment) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerNote(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(ZillitIcons.Paperclip, tint = ZillitTheme.colors.textMuted, size = ICON_SIZE)
        MutedLine(text)
    }
}

private val WIRE_DIALOG_WIDTH = 520.dp
private val VIEWER_WIDTH = 900.dp
private val VIEWER_HEIGHT = 620.dp
private val ICON_SIZE = 36.dp
