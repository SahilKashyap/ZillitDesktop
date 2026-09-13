// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.productionreport.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.domain.SheetPdfPage
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.PdfOverlay
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.CloseDisc
import com.zillit.desktop.feature.productionreport.ui.components.ModalScrim
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportIconButton
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.components.swallowClicks
import com.zillit.desktop.feature.productionreport.ui.decodeImageBitmap
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * "Generating PDF", then the viewer — the web's iframe becomes page images
 * with the browser viewer's zoom and download.
 */
@Composable
internal fun PdfOverlayView(pdf: PdfOverlay?, onEvent: (ReportEvent) -> Unit) {
    pdf ?: return
    val close = { onEvent(ListEvent.ClosePdf) }
    if (pdf.loading) {
        ModalScrim(onDismiss = null, onEscape = close) { GeneratingCard() }
    } else {
        ModalScrim(onDismiss = null, onEscape = close) { ViewerCard(pdf, onEvent) }
    }
}

@Composable
private fun GeneratingCard() {
    val colors = ReportTheme.colors
    Row(
        Modifier
            .shadow(24.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .swallowClicks()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
        Column {
            Text("Generating PDF", style = reportText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
            Text(
                "Fetching the latest published version — this can take a few seconds…",
                style = reportText(12.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ViewerCard(pdf: PdfOverlay, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    var zoom by remember(pdf.reportId) { mutableIntStateOf(FIT) }
    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    Column(
        Modifier
            .widthIn(max = 980.dp)
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.9f)
            .shadow(24.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .swallowClicks(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("PDF View", style = reportText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
                Text(
                    pdf.title,
                    style = reportText(11.sp),
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pdf.pages.isNotEmpty()) {
                Text(
                    "Page ${currentPage.coerceAtMost(pdf.pages.size)} of ${pdf.pages.size}",
                    style = reportText(12.sp, FontWeight.Medium),
                    color = colors.textSecondary,
                )
            }
            ZoomControl(zoom, onZoom = { zoom = it })
            ReportButton(
                "Download",
                { onEvent(ListEvent.DownloadPdf) },
                kind = ButtonKind.Outline,
                icon = ZillitIcons.Download,
                height = 30.dp,
                fontSize = 12.sp,
                enabled = pdf.bytes != null,
            )
            CloseDisc { onEvent(ListEvent.ClosePdf) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).background(if (colors.isDark) Color(0xFF1A1D2B) else Color(0xFFE4E7EC)),
        ) {
            val viewport = maxWidth
            val fitWidth = viewport - 48.dp
            val pageWidth = if (zoom == FIT) fitWidth else fitWidth * (zoom / 100f)
            if (pdf.pages.isEmpty()) {
                Text(
                    "This PDF has no pages.",
                    style = reportText(13.sp),
                    color = colors.textMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(maxOf(viewport, pageWidth + 48.dp)).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(24.dp),
                    ) {
                        itemsIndexed(pdf.pages, key = { _, page -> page.page }) { _, page ->
                            PageImage(page, Modifier.width(pageWidth))
                        }
                    }
                }
            }
        }
    }
}

private const val FIT = 0
private val ZOOM_STEPS = listOf(FIT, 50, 75, 100, 125, 150, 200)

@Composable
private fun ZoomControl(zoom: Int, onZoom: (Int) -> Unit) {
    val colors = ReportTheme.colors
    val index = ZOOM_STEPS.indexOf(zoom).coerceAtLeast(0)
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReportIconButton(
            ReportIcons.Minus,
            "Zoom out",
            { onZoom(ZOOM_STEPS[(index - 1).coerceAtLeast(0)]) },
            enabled = index > 0,
        )
        Text(
            if (zoom == FIT) "Fit" else "$zoom%",
            style = reportText(12.sp, FontWeight.Medium),
            color = colors.textSecondary,
            modifier = Modifier.width(40.dp).padding(horizontal = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        ReportIconButton(
            ZillitIcons.Add,
            "Zoom in",
            { onZoom(ZOOM_STEPS[(index + 1).coerceAtMost(ZOOM_STEPS.lastIndex)]) },
            enabled = index < ZOOM_STEPS.lastIndex,
        )
    }
}

@Composable
private fun PageImage(page: SheetPdfPage, modifier: Modifier) {
    val bitmap = remember(page.page, page.imageBytes.size) { decodeImageBitmap(page.imageBytes) }
    val ratio = if (page.heightPx > 0) page.widthPx.toFloat() / page.heightPx else A4_RATIO
    Box(modifier.aspectRatio(ratio).shadow(4.dp).background(Color.White)) {
        bitmap?.let {
            Image(
                it,
                contentDescription = "Page ${page.page + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val A4_RATIO = 0.7071f
