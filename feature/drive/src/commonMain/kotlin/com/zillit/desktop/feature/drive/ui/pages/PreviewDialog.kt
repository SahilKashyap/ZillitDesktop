package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.PreviewState
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The in-app preview — the web's preview modal for images and its
 * `DocumentViewer` for PDFs, plus plain text. Video and audio never reach
 * here (they go to the host's player); office documents open in the editor.
 * Anything else gets a card that says so, with the way out to the browser.
 */
@Composable
internal fun PreviewDialog(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val preview = state.preview
    val item = preview?.item
    val canDownload = item != null && state.viewer.may(DriveAction.Download, item)
    ZillitDialogShell(
        title = item?.name.orEmpty(),
        subtitle = item?.let { formatBytes(it.sizeBytes).takeIf { s -> s != "—" } },
        visible = preview != null,
        onDismiss = { onEvent(DriveEvent.ClosePreview) },
        icon = ZillitIcons.Eye,
        width = PREVIEW_WIDTH,
        maxHeight = PREVIEW_HEIGHT,
        scrollable = false,
        actions = {
            if (preview?.url != null && preview.kind == PreviewKind.Document) {
                ZillitButton(
                    text = str(S.desktop_drive_open_in_browser),
                    onClick = { onEvent(DriveEvent.OpenPreviewInBrowser) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Link,
                )
            }
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DriveEvent.ClosePreview) },
                variant = ButtonVariant.Tertiary,
            )
            if (canDownload && item != null) {
                ZillitButton(
                    text = str(S.download),
                    onClick = { onEvent(DriveEvent.Download(item)) },
                    leadingIcon = ZillitIcons.Download,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_BODY)
                .clip(ZillitTheme.shapes.medium)
                .background(VIEWER_BACKDROP),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) PreviewBody(preview)
        }
    }
}

@Composable
private fun PreviewBody(preview: PreviewState) {
    when {
        preview.loading -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSpinner(color = Color.White)
            ZillitText(
                text = str(S.desktop_loading_preview),
                style = ZillitTheme.typography.bodySmall,
                color = Color.White,
            )
        }

        preview.error != null -> Unavailable(preview.error)
        preview.kind == PreviewKind.Image && preview.imageBytes != null -> {
            val bitmap = remember(preview.imageBytes) { decodeImageBitmap(preview.imageBytes) }
            if (bitmap == null) {
                Unavailable(str(S.desktop_drive_image_not_decoded))
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = preview.item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.md),
                )
            }
        }

        preview.kind == PreviewKind.Pdf && preview.pages.isNotEmpty() -> Pages(preview.pages)
        preview.kind == PreviewKind.Text && preview.text != null -> TextBody(preview.text)
        else -> Unavailable(str(S.desktop_drive_preview_unsupported_type))
    }
}

@Composable
private fun Pages(pages: List<ByteArray>) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            state = scroll,
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            pages.forEach { page ->
                val bitmap = remember(page) { decodeImageBitmap(page) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(ZillitTheme.shapes.small).background(Color.White),
                    )
                }
            }
        }
        ZillitScrollRail(scroll, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun TextBody(text: String) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.surface),
            state = scroll,
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        ) {
            ZillitText(
                text = text,
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        ZillitScrollRail(scroll, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun Unavailable(reason: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.padding(ZillitTheme.spacing.xl),
    ) {
        ZillitIcon(icon = ZillitIcons.File, tint = Color.White.copy(alpha = ICON_ALPHA), size = UNAVAILABLE_ICON)
        ZillitText(
            text = reason,
            style = ZillitTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private val PREVIEW_WIDTH = 960.dp
private val PREVIEW_HEIGHT = 760.dp
private val PREVIEW_BODY = 600.dp
private val UNAVAILABLE_ICON = 48.dp
private val VIEWER_BACKDROP = Color(0xFF1B1D24)
private const val ICON_ALPHA = 0.6f
