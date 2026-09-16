package com.zillit.desktop.feature.sides.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.sides.ui.SidesEvent
import com.zillit.desktop.feature.sides.ui.SidesPdfView
import com.zillit.desktop.feature.sides.ui.components.SidesLoader
import com.zillit.desktop.feature.sides.ui.decodeImageBitmap

/**
 * The in-app preview — the web's `PdfViewerModal`.
 *
 * View is open to every viewer; only leaving with the file is gated, and
 * both buttons that do (Open in browser, the not-a-PDF Download) stay on
 * screen and ask an admin when refused. A Final Draft file cannot be
 * rendered and says so instead of failing.
 */
@Composable
internal fun SidesPdfOverlay(view: SidesPdfView, canDownload: Boolean, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = view.title,
        subtitle = view.subtitle.takeIf { it.isNotBlank() },
        onDismiss = { onEvent(SidesEvent.ClosePdf) },
        visible = true,
        scrollable = false,
        width = 1100.dp,
        maxHeight = 900.dp,
        actions = {
            if (view.info.isNotEmpty()) {
                ZillitTooltip(view.info.joinToString("\n") { (label, value) -> "$label: $value" }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ZillitIcon(icon = ZillitIcons.Info, tint = colors.accentText, size = 14.dp)
                        ZillitText("Selection details", style = ZillitTheme.typography.label, color = colors.accentText)
                    }
                }
            }
            if (!view.loading && view.url.isNotBlank()) {
                ZillitButton(
                    text = if (canDownload) "Open in browser" else "Open in browser (request access)",
                    onClick = { onEvent(SidesEvent.PdfOpenExternal) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Globe,
                )
            }
            ZillitButton(
                text = "Close",
                onClick = { onEvent(SidesEvent.ClosePdf) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(720.dp).clip(ZillitTheme.shapes.medium).background(Color(0xFF525659))) {
            when {
                view.error.isNotBlank() -> Centre {
                    ZillitText(view.error, style = ZillitTheme.typography.bodyMedium, color = Color.White)
                }
                view.notPdf -> NotPdf(canDownload, onEvent)
                view.loading -> Centre { SidesLoader("Loading preview…") }
                else -> PageStack(view)
            }
        }
    }
}

@Composable
private fun Centre(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun NotPdf(canDownload: Boolean, onEvent: (SidesEvent) -> Unit) {
    Centre {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = Color.White.copy(alpha = 0.8f), size = 40.dp)
            ZillitText("Preview not available", style = ZillitTheme.typography.titleMedium, color = Color.White)
            ZillitText(
                text = "This is a Final Draft (.fdx) or non-PDF file, which can’t be previewed here. " +
                    if (canDownload) {
                        "Download it to open in Final Draft, or open it in your browser."
                    } else {
                        "You do not have permission to download this file — request access from an admin to open it."
                    },
                style = ZillitTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitButton(
                    text = if (canDownload) "Download" else "Request download access",
                    onClick = { onEvent(SidesEvent.PdfDownload) },
                    size = ButtonSize.Small,
                    leadingIcon = if (canDownload) ZillitIcons.Download else ZillitIcons.Lock,
                )
            }
        }
    }
}

@Composable
private fun PageStack(view: SidesPdfView) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        items(view.pages, key = { it.page }) { page ->
            val bitmap = remember(page.page) { decodeImageBitmap(page.imageBytes) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Page ${page.page + 1}",
                    modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
                )
            }
        }
    }
}
