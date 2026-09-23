package com.zillit.desktop.feature.recce.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.recce.domain.ReccePdfPage
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.ReccePdfViewer

/**
 * The generated report, in the app — where the web opens the PDF in a new
 * tab: the file's name on a dark bar with Download, Print and Close, the
 * pages on a slate canvas.
 */
@Composable
internal fun ReccePdfOverlay(viewer: ReccePdfViewer?, onEvent: (RecceEvent) -> Unit) {
    val held = remember { arrayOfNulls<ReccePdfViewer>(1) }
    viewer?.let { held[0] = it }
    AnimatedVisibility(visible = viewer != null, enter = fadeIn(tween(ENTER_MS)), exit = fadeOut(tween(EXIT_MS))) {
        val shown = viewer ?: held[0] ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onEvent(RecceEvent.ClosePdf) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .widthIn(max = MAX_WIDTH)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .shadow(18.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(SLATE)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                ViewerBar(shown, onEvent)
                Pages(shown)
            }
        }
    }
}

@Composable
private fun ViewerBar(viewer: ReccePdfViewer, onEvent: (RecceEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(BAR).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(
            text = viewer.name,
            style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.download),
            onClick = { onEvent(RecceEvent.DownloadPdf) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Download,
            enabled = viewer.bytes != null,
            loading = viewer.downloading,
        )
        ZillitButton(
            text = str(S.print),
            onClick = { onEvent(RecceEvent.PrintPdf) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Print,
            enabled = viewer.bytes != null,
            loading = viewer.printing,
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.close),
            onClick = { onEvent(RecceEvent.ClosePdf) },
            tint = Color.White,
        )
    }
}

@Composable
private fun Pages(viewer: ReccePdfViewer) {
    when {
        viewer.failed != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ZillitText(text = viewer.failed, style = ZillitTheme.typography.bodyMedium, color = Color.White)
        }
        viewer.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        else -> ZillitLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(viewer.pages.size) { index -> Page(viewer.pages[index]) }
        }
    }
}

@Composable
private fun Page(page: ReccePdfPage) {
    val image = remember(page) { decodeImageBitmap(page.imageBytes) } ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier
            .widthIn(max = PAGE_WIDTH)
            .fillMaxWidth()
            .aspectRatio(page.widthPx.toFloat() / page.heightPx.coerceAtLeast(1))
            .shadow(8.dp, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White),
        contentScale = ContentScale.Fit,
    )
}

private val SLATE = Color(0xFF2B2F36)
private val BAR = Color(0xFF1C1F24)
private val MAX_WIDTH = 1100.dp
private val PAGE_WIDTH = 900.dp
private const val SCRIM_ALPHA = 0.7f
private const val ENTER_MS = 160
private const val EXIT_MS = 120
