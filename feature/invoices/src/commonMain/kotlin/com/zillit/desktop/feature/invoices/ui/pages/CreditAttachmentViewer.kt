package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.ui.AttachmentView
import com.zillit.desktop.feature.invoices.ui.CreditEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

/**
 * A credit note's attachment, in the app — the web's `CreditAttachmentViewer`
 * (`CreditsPage.jsx:113-164`): the picture or the PDF's pages, with Download;
 * "Loading attachment…" while it comes, "Failed to load attachment" when it
 * does not, and "Preview not available for this file type." for anything
 * else, with Download to view.
 */
@Composable
internal fun CreditAttachmentViewer(view: AttachmentView, onEvent: (InvoicesEvent) -> Unit) {
    val name = view.attachment.name.ifBlank { view.attachment.stored?.name.orEmpty() }.ifBlank { str(S.attachment) }
    val pages = remember(view.bytes) { view.bytes?.bytes?.let(::decodePreviewPages).orEmpty() }
    ZillitDialogShell(
        title = name,
        onDismiss = { onEvent(CreditEvent.CloseAttachment) },
        visible = true,
        width = VIEWER_WIDTH,
        scrollable = false,
        icon = ZillitIcons.Paperclip,
        actions = {
            if (view.bytes != null) {
                ZillitButton(
                    text = str(S.download),
                    onClick = { onEvent(CreditEvent.DownloadAttachment) },
                    leadingIcon = ZillitIcons.Download,
                )
            }
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(CreditEvent.CloseAttachment) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(VIEWER_HEIGHT), contentAlignment = Alignment.Center) {
            when {
                view.loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitSpinner()
                    MutedLine(str(S.desktop_dm_loading_attachment))
                }
                view.failed || view.bytes == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitIcon(icon = ZillitIcons.File, tint = ZillitTheme.colors.border, size = EMPTY_ICON)
                    MutedLine(str(S.desktop_attachment_load_failed))
                }
                pages.isNotEmpty() -> Box(Modifier.fillMaxSize()) { PreviewPages(pages, name) }
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitIcon(icon = ZillitIcons.File, tint = ZillitTheme.colors.border, size = EMPTY_ICON)
                    MutedLine(str(S.desktop_dm_preview_not_available_for_this_file_type))
                    ZillitButton(
                        text = str(S.desktop_dm_download_to_view),
                        onClick = { onEvent(CreditEvent.DownloadAttachment) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

private val VIEWER_WIDTH = 900.dp
private val VIEWER_HEIGHT = 620.dp
private val EMPTY_ICON = 40.dp
