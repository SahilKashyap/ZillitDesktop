package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * An attachment, opened over the mailbox: a picture fitted to the stage, a
 * PDF's pages stacked to scroll, text as text — or, for a file the app cannot
 * draw, a plain "preview not available" beside the Download that gets it.
 *
 * On screen at once with a spinner, filled when the bytes arrive. Kept
 * composed with a null [preview] so the shell's exit can play; the last file
 * shown is remembered so the fading card still has content.
 */
@Composable
internal fun AttachmentPreviewDialog(
    preview: AttachmentPreview?,
    /** The open file's download, if one was started — from here or from its chip. */
    download: AttachmentDownload?,
    /** False on a build with nowhere to save; Download is then not offered. */
    canDownload: Boolean,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    var shown by remember { mutableStateOf(preview) }
    if (preview != null) shown = preview
    val current = shown

    ZillitDialogShell(
        title = current?.attachment?.fileName.orEmpty(),
        subtitle = current?.attachment?.readableSize?.takeIf { it.isNotBlank() },
        onDismiss = onClose,
        visible = preview != null,
        width = PREVIEW_WIDTH,
        maxHeight = PREVIEW_MAX_HEIGHT,
        // Pages and text scroll inside the stage, with the header held still.
        scrollable = false,
        headerTrailing = {
            if (current != null && canDownload) DownloadControl(download, onDownload)
        },
    ) {
        if (current == null) return@ZillitDialogShell
        val focus = remember { FocusRequester() }
        // Focused so Escape closes it, as every other viewer in the app does.
        LaunchedEffect(current.attachment.id) { runCatching { focus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(STAGE_HEIGHT)
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.surfaceSunken)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (escape) onClose()
                    escape
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                current.isLoading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitSpinner()
                    StageNote(str(S.desktop_dm_loading_attachment))
                }
                current.failed != null -> StageNote(current.failed, color = ZillitTheme.colors.danger)
                else -> PreviewStage(current, canDownload, download == null, onDownload)
            }
        }
    }
}

@Composable
private fun PreviewStage(
    preview: AttachmentPreview,
    canDownload: Boolean,
    downloadIdle: Boolean,
    onDownload: () -> Unit,
) {
    when (val body = preview.body) {
        is PreviewBody.Picture -> Image(
            bitmap = body.image,
            contentDescription = preview.attachment.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.md),
        )
        is PreviewBody.Pages -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            body.pages.forEach { page ->
                Image(
                    bitmap = page,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .widthIn(max = PAGE_MAX_WIDTH)
                        .fillMaxWidth()
                        .aspectRatio(page.width.toFloat() / page.height.coerceAtLeast(1))
                        // A page is paper whatever the theme.
                        .background(Color.White),
                )
            }
        }
        is PreviewBody.Text -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        ) {
            SelectionContainer {
                ZillitText(
                    text = body.text,
                    style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = ZillitTheme.colors.textPrimary,
                )
            }
        }
        PreviewBody.Unsupported, null -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitFileBadge(fileName = preview.attachment.fileName)
            StageNote(str(S.desktop_dm_preview_not_available_for_this_file_type))
            if (canDownload && downloadIdle) {
                ZillitButton(
                    text = str(S.desktop_dm_download_to_view),
                    onClick = onDownload,
                    leadingIcon = ZillitIcons.Download,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

/** Download, then what became of it: the spinner, where it went, or why not. */
@Composable
private fun DownloadControl(download: AttachmentDownload?, onDownload: () -> Unit) {
    when (download) {
        null -> ZillitButton(
            text = str(S.dm_nda_download),
            onClick = onDownload,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
        )
        AttachmentDownload.InProgress -> ZillitButton(
            text = str(S.drive_detail_downloading),
            onClick = {},
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            loading = true,
        )
        is AttachmentDownload.Saved -> ZillitText(
            text = str(S.docusign_download_saved, download.path.parentFolder()),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.success,
            maxLines = 1,
            modifier = Modifier.widthIn(max = SAVED_NOTE_WIDTH),
        )
        is AttachmentDownload.Failed -> ZillitText(
            text = download.reason,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.danger,
            maxLines = 1,
            modifier = Modifier.widthIn(max = SAVED_NOTE_WIDTH),
        )
    }
}

@Composable
private fun StageNote(text: String, color: Color = ZillitTheme.colors.textMuted) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

/** The folder part of a path, on either separator — this ships to Windows too. */
private fun String.parentFolder(): String {
    val cut = maxOf(lastIndexOf('/'), lastIndexOf('\\'))
    return if (cut <= 0) this else substring(0, cut)
}

private val PREVIEW_WIDTH = 1000.dp
private val PREVIEW_MAX_HEIGHT = 860.dp
private val STAGE_HEIGHT = 720.dp
private val PAGE_MAX_WIDTH = 900.dp
private val SAVED_NOTE_WIDTH = 320.dp
