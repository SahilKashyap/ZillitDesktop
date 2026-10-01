package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.domain.ScanMode
import com.zillit.desktop.feature.costumesetsync.domain.ScanPage
import com.zillit.desktop.feature.costumesetsync.domain.SyncHost
import com.zillit.desktop.feature.costumesetsync.domain.cleanScan
import com.zillit.desktop.feature.costumesetsync.domain.decodeRaster
import com.zillit.desktop.feature.costumesetsync.domain.enhance
import com.zillit.desktop.feature.costumesetsync.domain.scanFileName
import com.zillit.desktop.feature.costumesetsync.domain.scanStamp
import com.zillit.desktop.feature.costumesetsync.domain.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pictures a page can be read from: the phone's HEIC is left out, as nothing here can decode it. */
private val SCAN_EXTENSIONS = IMAGE_EXTENSIONS - setOf("heic", "heif")

/**
 * The document scanner (the web's `DocumentScanner`): paper — a receipt, a care label, a sketch, a call sheet — into
 * the record, one or more pages at a time, cleaned up black & white or in colour when they are attached.
 *
 * The web runs the camera inside the dialog; the desktop cannot embed one, so pages come from the host's camera
 * window ([com.zillit.desktop.feature.costumesetsync.domain.SyncHost.capturePhoto], when it has one) or from picture
 * files — the web's own fallback when its camera is missing. The pages, the black & white / colour switch, the page
 * strip and the clean-up are the same either way. [onScans] gets the cleaned JPEG files; a page that will not decode
 * keeps them all and says so, so Use pages can be pressed again.
 */
@Composable
fun DocumentScannerDialog(open: Boolean, onClose: () -> Unit, onScans: (List<PickedFile>) -> Unit) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val scan = remember(open) { ScanState() }
    val count = scan.pages.size
    FormDialog(
        open = open,
        title = t("csync_scan_document"),
        onDismiss = { if (!scan.busy) onClose() },
        confirmLabel = when (count) {
            1 -> t("csync_scan_attach_one")
            // With no pages the web leaves the count blank; the doubled space it leaves is closed up.
            0 -> t("csync_scan_attach_n", "n" to "").replace("  ", " ")
            else -> t("csync_scan_attach_n", "n" to count)
        },
        onConfirm = { scope.launch { scan.attach(ctx.now(), onScans, onClose) } },
        confirmEnabled = count > 0,
        busy = scan.busy,
        width = 760.dp,
    ) {
        SourceRow(
            hasCamera = ctx.host.hasCamera,
            enabled = !scan.busy && !scan.taking,
            onCapture = { scope.launch { scan.capture(ctx.host) } },
            onFiles = { scope.launch { scan.addFiles(ctx.host) } },
        )
        ModeBar(scan.mode, { scan.mode = it }, count)
        if (scan.error.isNotBlank()) {
            Notice(tone = NoticeTone.Warn) { ZillitText(scan.error, style = ZillitTheme.typography.bodyMedium) }
        }
        if (count == 0) PageGuide() else PageStrip(scan)
    }
}

/** What a scan in progress holds: the pages taken so far, the clean-up mode, and where the dialog stands. */
@Stable
private class ScanState {
    val pages = mutableStateListOf<ScanPage>()
    var mode by mutableStateOf(ScanMode.Document)
    var busy by mutableStateOf(false)
    var taking by mutableStateOf(false)
    var error by mutableStateOf("")
    private var nextId = 0

    fun add(files: List<PickedFile>) {
        files.filter { it.isImage }.forEach { pages += ScanPage(nextId++, it.bytes) }
    }

    suspend fun capture(host: SyncHost) {
        taking = true
        host.capturePhoto()?.let { add(listOf(it)) }
        taking = false
    }

    suspend fun addFiles(host: SyncHost) = add(host.pick(SCAN_EXTENSIONS, true))

    /** Cleans every page and hands the files over; on a page that will not decode they all stay, with the reason. */
    suspend fun attach(nowMs: Long, onScans: (List<PickedFile>) -> Unit, onClose: () -> Unit) {
        busy = true
        error = ""
        val stamp = scanStamp(nowMs)
        val files = withContext(Dispatchers.Default) {
            pages.mapIndexed { i, page -> cleanScan(page.bytes, mode, scanFileName(stamp, i, pages.size)) }
        }
        if (files.any { it == null }) {
            error = t("csync_scan_save_failed")
        } else {
            onScans(files.filterNotNull())
            onClose()
        }
        busy = false
    }
}

@Composable
private fun PageStrip(scan: ScanState) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        scan.pages.forEachIndexed { i, page ->
            PageTile(page, scan.mode, i + 1, removable = !scan.busy) { scan.pages.remove(page) }
        }
    }
}

@Composable
private fun SourceRow(hasCamera: Boolean, enabled: Boolean, onCapture: () -> Unit, onFiles: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasCamera) {
            ZillitButton(
                t("csync_scan_capture"),
                onClick = onCapture,
                leadingIcon = AhIcons.Camera,
                enabled = enabled,
            )
        }
        ZillitButton(
            t("csync_scan_add_files"),
            onClick = onFiles,
            variant = if (hasCamera) ButtonVariant.Secondary else ButtonVariant.Primary,
            leadingIcon = ZillitIcons.Photo,
            enabled = enabled,
        )
    }
    if (!hasCamera) MutedText(t("csync_scan_no_camera_here"), maxLines = 2)
}

/** Black & white · Colour, and how many pages are in (`.csync-scan__bar`). */
@Composable
private fun ModeBar(mode: ScanMode, onMode: (ScanMode) -> Unit, pages: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            listOf(ScanMode.Document to "csync_scan_bw", ScanMode.Color to "csync_scan_colour").forEach { (m, key) ->
                if (mode == m) {
                    InkButton(t(key), onClick = { onMode(m) }, size = ButtonSize.Small)
                } else {
                    ZillitButton(
                        t(key),
                        onClick = { onMode(m) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
        MutedText(
            when (pages) {
                0 -> t("csync_scan_hold_flat")
                1 -> t("csync_scan_pages_one")
                else -> t("csync_scan_pages_n", "n" to pages)
            },
        )
    }
}

/** The page-shaped frame (`.csync-scan__guide`) the strip starts as: where the first page will land. */
@Composable
private fun PageGuide() {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().height(GUIDE_HEIGHT).clip(shape).background(colors.surfaceHover)
            .border(1.dp, colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(GUIDE_PAGE_WIDTH, GUIDE_PAGE_HEIGHT).clip(RoundedCornerShape(4.dp)).background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.File, tint = colors.textMuted, size = 28.dp)
        }
    }
}

private val GUIDE_HEIGHT = 190.dp
private val GUIDE_PAGE_WIDTH = 120.dp
private val GUIDE_PAGE_HEIGHT = 150.dp
private val PAGE_TILE_WIDTH = 132.dp
private val PAGE_TILE_HEIGHT = 176.dp
private const val PREVIEW_SIDE = 420

/** One page of the strip, drawn as it will be attached (cleaned up in the chosen mode), with its number and a ✕. */
@Composable
private fun PageTile(page: ScanPage, mode: ScanMode, number: Int, removable: Boolean, onRemove: () -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val preview by produceState<ImageBitmap?>(null, page, mode) {
        value = withContext(Dispatchers.Default) {
            decodeRaster(page.bytes, PREVIEW_SIDE)?.let { enhance(it, mode).toImageBitmap() }
        }
    }
    Box(
        Modifier.size(PAGE_TILE_WIDTH, PAGE_TILE_HEIGHT).clip(shape).background(colors.surfaceHover)
            .border(1.dp, colors.border, shape),
    ) {
        preview?.let {
            Image(
                it,
                contentDescription = "p$number",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ZillitText(
            "p$number",
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            textAlign = TextAlign.Start,
            maxLines = 1,
        )
        if (removable) RemoveMark(onRemove, Modifier.align(Alignment.TopEnd).padding(4.dp), size = 18.dp)
    }
}
