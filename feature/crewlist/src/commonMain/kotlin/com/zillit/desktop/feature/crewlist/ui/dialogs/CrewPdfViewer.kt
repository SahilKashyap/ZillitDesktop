package com.zillit.desktop.feature.crewlist.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.ui.PdfViewerState
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.CrewIcons
import com.zillit.desktop.feature.crewlist.ui.components.onBackdropTap
import com.zillit.desktop.feature.crewlist.ui.components.swallowPresses
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The generated PDF, in the app — the web's `DocumentViewer` as the crew list
 * opens it: the file's name on a dark bar, Publish {tool} (asked first),
 * Publish to Doc Distribution, and Close; the pages on a slate canvas. The
 * desktop adds Download beside them and a zoom.
 */
@Composable
internal fun CrewPdfViewer(
    viewer: PdfViewerState?,
    copy: CrewCopy,
    compact: Boolean,
    publishing: Boolean,
    onPublish: () -> Unit,
    onDistribute: () -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    val held = remember { arrayOfNulls<PdfViewerState>(1) }
    viewer?.let { held[0] = it }
    AnimatedVisibility(
        visible = viewer != null,
        enter = fadeIn(tween(ENTER_MS)),
        exit = fadeOut(tween(EXIT_MS)),
    ) {
        val shown = viewer ?: held[0] ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM))
                .onBackdropTap(onClose),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(if (compact) 12.dp else 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = MAX_WIDTH)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .animateEnterExit(
                            enter = scaleIn(initialScale = 0.97f, animationSpec = tween(ENTER_MS)),
                            exit = scaleOut(targetScale = 0.97f, animationSpec = tween(EXIT_MS)),
                        )
                        .shadow(18.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(SLATE)
                        .swallowPresses(),
                ) {
                    ViewerBar(shown, copy, compact, publishing, onPublish, onDistribute, onDownload, onClose)
                    Pages(shown)
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // The bar's four actions, passed straight through.
private fun ViewerBar(
    viewer: PdfViewerState,
    copy: CrewCopy,
    compact: Boolean,
    publishing: Boolean,
    onPublish: () -> Unit,
    onDistribute: () -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(BAR).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ViewerTitle(viewer, Modifier.weight(1f))
        ZillitButton(
            text = str(S.download),
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            enabled = !viewer.loading && viewer.failed == null,
            onClick = onDownload,
        )
        if (!compact) {
            ZillitButton(
                text = copy.t("publish_to_doc_distribution", str(S.dd_publish_to_distribution)),
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
                onClick = onDistribute,
            )
            ZillitButton(
                text = copy.t("PublishCrewList", str(S.desktop_cl_publish_tool)),
                size = ButtonSize.Small,
                loading = publishing,
                enabled = !publishing,
                onClick = onPublish,
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = copy.t("Close", str(S.close)),
            tint = Color.White,
            onClick = onClose,
        )
    }
}

/** The document mark, the file's name, and how many pages it has once drawn. */
@Composable
private fun ViewerTitle(viewer: PdfViewerState, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ZillitIcon(icon = CrewIcons.Document, tint = Color.White.copy(alpha = 0.8f), size = 18.dp)
        Column {
            ZillitText(
                text = viewer.pdf.fileName.removeSuffix(".pdf"),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = Color.White,
                maxLines = 1,
            )
            if (viewer.pages.isNotEmpty()) {
                val count = viewer.pages.size
                ZillitText(
                    text = if (count == 1) "1 page" else "$count pages",
                    style = ZillitTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Pages(viewer: PdfViewerState) {
    var zoom by remember(viewer.pdf) { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxSize()) {
        when {
            viewer.loading -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZillitSpinner(size = 28.dp, color = Color.White)
                ZillitText(
                    str(S.desktop_loading_preview),
                    style = ZillitTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            viewer.failed != null -> ZillitText(
                text = viewer.failed,
                style = ZillitTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val fit = (maxWidth - 72.dp).coerceAtMost(PAGE_MAX_WIDTH)
                ZillitLazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
                ) {
                    itemsIndexed(viewer.pages, key = { index, _ -> index }) { _, page ->
                        val width = fit * zoom
                        val ratio = if (page.widthPx > 0) page.heightPx.toFloat() / page.widthPx else A4_RATIO
                        Image(
                            bitmap = page.image,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .width(width)
                                .height(width * ratio)
                                .shadow(6.dp, RoundedCornerShape(2.dp))
                                .background(Color.White),
                        )
                    }
                }
            }
        }
        if (!viewer.loading && viewer.failed == null) {
            ZoomControl(
                zoom = zoom,
                onZoom = { zoom = it.coerceIn(MIN_ZOOM, MAX_ZOOM) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun ZoomControl(zoom: Float, onZoom: (Float) -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(BAR)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIconButton(
            CrewIcons.Minus,
            str(S.docusign_zoom_out),
            tint = Color.White,
            onClick = { onZoom(zoom - ZOOM_STEP) },
        )
        ZillitText(
            text = "${(zoom * 100).toInt()}%",
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        ZillitIconButton(
            ZillitIcons.Add,
            str(S.docusign_zoom_in),
            tint = Color.White,
            onClick = { onZoom(zoom + ZOOM_STEP) },
        )
    }
}

private val SLATE = Color(0xFF334155)
private val BAR = Color(0xFF1F2937)
private val MAX_WIDTH = 1080.dp
private val PAGE_MAX_WIDTH = 900.dp
private const val SCRIM = 0.55f
private const val ENTER_MS = 220
private const val EXIT_MS = 160
private const val A4_RATIO = 1.414f
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 2f
private const val ZOOM_STEP = 0.2f
