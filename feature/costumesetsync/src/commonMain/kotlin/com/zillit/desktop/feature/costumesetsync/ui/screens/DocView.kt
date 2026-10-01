package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.docName
import com.zillit.desktop.feature.costumesetsync.domain.fdxText
import com.zillit.desktop.feature.costumesetsync.domain.isTextDoc
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PdfPages
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.openPdf
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * What the file pane shows for the kept file on screen — the web's `DocFile` states: its PDF (rendered page by
 * page here, where the browser's own viewer does it on the web), Final Draft / Fountain / plain text as text,
 * and for anything else (a spreadsheet) or a file that would not come, the "can't be shown here" panel.
 */
internal sealed interface DocView {
    data object Loading : DocView
    data object Failed : DocView
    data object Unsupported : DocView
    class Pdf(val pages: PdfPages) : DocView
    class Text(val lines: List<String>) : DocView
}

/**
 * A kept file's own address: its `url`, else the signed / public one on its attachment, else the stored key
 * resolved against the project's storage (the web's `resolveReferenceUrl`). Null when none will resolve.
 */
internal suspend fun docUrl(ctx: SyncCtx, doc: Rec): String? {
    val attachment = doc.rec("attachment")
    return doc.str("url").ifEmpty { null }
        ?: attachment?.str("signed_url")?.ifEmpty { null }
        ?: attachment?.str("public_url")?.ifEmpty { null }
        ?: attachment?.let { ctx.host.resolveUrl(it.str("media"), it.str("bucket"), it.str("region")) }
            ?.ifEmpty { null }
}

private const val PDF_MAGIC = "%PDF"

/** Reads the bytes behind [doc] and decides how to show them: by what the bytes are (`%PDF`), then by its name. */
internal suspend fun loadDocView(ctx: SyncCtx, doc: Rec): DocView {
    val bytes = docUrl(ctx, doc)?.let { ctx.host.fetch(it) } ?: return DocView.Failed
    // Opening a PDF is not cancellable half-way: it either lands in the pane (which closes it) or is closed here.
    val view = withContext(NonCancellable + Dispatchers.Default) { classify(bytes, docName(doc)) }
    if (!currentCoroutineContext().isActive) (view as? DocView.Pdf)?.pages?.close()
    return view
}

internal fun classify(bytes: ByteArray, name: String): DocView = when {
    bytes.size >= PDF_MAGIC.length && bytes.decodeToString(0, PDF_MAGIC.length) == PDF_MAGIC ->
        openPdf(bytes)?.let { DocView.Pdf(it) } ?: DocView.Failed
    isTextDoc(name) -> {
        val raw = bytes.decodeToString().removePrefix("﻿")
        val text = if (name.endsWith(".fdx", ignoreCase = true)) fdxText(raw) else raw
        DocView.Text(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'))
    }
    else -> DocView.Unsupported
}

/** The file pane: the web's `.csync-docview__file`, filled by whatever [loadDocView] makes of [doc]. */
@Composable
internal fun RowScope.FilePane(doc: Rec, canDownload: Boolean) {
    val colors = ZillitTheme.colors
    val ctx = LocalSync.current
    var view by remember(doc.id) { mutableStateOf<DocView>(DocView.Loading) }
    val latest by rememberUpdatedState(view)
    LaunchedEffect(doc.id) { view = loadDocView(ctx, doc) }
    // Never the previous document's file under this one's name: a PDF's pages are closed with its pane.
    DisposableEffect(doc.id) { onDispose { (latest as? DocView.Pdf)?.pages?.close() } }
    Box(
        Modifier.weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (val shown = view) {
            DocView.Loading -> ZillitSpinner(size = 22.dp)
            DocView.Failed -> MutedText(t("csync_doc_preview_failed"), Modifier.padding(16.dp), maxLines = 3)
            DocView.Unsupported -> CantShow(canDownload)
            is DocView.Pdf -> PdfView(shown.pages)
            is DocView.Text -> TextView(shown.lines)
        }
    }
}

private val TEXT_STYLE_SIZE = 12.sp

/** Text-like files, as the web's `<pre>`: monospace, wrapped, selectable. */
@Composable
private fun TextView(lines: List<String>) {
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(lines) { line ->
                ZillitText(
                    line.ifEmpty { " " },
                    style = ZillitTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = TEXT_STYLE_SIZE,
                        lineHeight = TEXT_STYLE_SIZE * 1.4f,
                    ),
                )
            }
        }
    }
}

private val ZOOMS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private const val FIT = 2
private const val PAGE_STEP = 40
private val PAGE_MARGIN = 14.dp
private val PAGE_GAP = 12.dp
private val SCROLLBAR_ROOM = 10.dp
private const val BYTES_PER_PIXEL = 4
private const val CACHE_BYTES = 96L * 1024 * 1024

/** Rendered pages kept while they are near the viewport, bounded by bytes: the oldest go first. */
private class PageCache {
    private val map = LinkedHashMap<Pair<Int, Int>, ImageBitmap>()
    private var bytes = 0L

    fun get(index: Int, widthPx: Int): ImageBitmap? = map[index to widthPx]?.also {
        map.remove(index to widthPx)
        map[index to widthPx] = it
    }

    fun put(index: Int, widthPx: Int, bitmap: ImageBitmap) {
        map.put(index to widthPx, bitmap)?.let { bytes -= it.size() }
        bytes += bitmap.size()
        while (bytes > CACHE_BYTES && map.size > 1) {
            val oldest = map.entries.first()
            bytes -= oldest.value.size()
            map.remove(oldest.key)
        }
    }

    private fun ImageBitmap.size(): Long = width.toLong() * height * BYTES_PER_PIXEL
}

/**
 * A PDF as the browser's viewer shows it on the web: the pages one under another on a grey desk, scrolling,
 * with a slim bar for the page you are on and the zoom (fit to width first; zoom past it scrolls sideways).
 * Pages are drawn when they scroll into view; the list grows [PAGE_STEP] pages at a time.
 */
@Composable
private fun PdfView(pages: PdfPages) {
    var zoomAt by remember(pages) { mutableIntStateOf(FIT) }
    var shown by remember(pages) { mutableIntStateOf(PAGE_STEP) }
    val list = rememberLazyListState()
    val cache = remember(pages) { PageCache() }
    val current by remember(list) { derivedStateOf { list.firstVisibleItemIndex.coerceAtMost(pages.sizes.lastIndex) } }
    Column(Modifier.fillMaxSize()) {
        PdfBar(current + 1, pages.sizes.size, zoomAt, { zoomAt = it })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val avail = maxWidth
            val pageW = ((avail - PAGE_MARGIN * 2 - SCROLLBAR_ROOM) * ZOOMS[zoomAt]).coerceAtLeast(MIN_PAGE_W)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(
                    Modifier.width(maxOf(pageW + PAGE_MARGIN * 2 + SCROLLBAR_ROOM, avail)).fillMaxHeight(),
                    state = list,
                    contentPadding = PaddingValues(vertical = PAGE_MARGIN),
                    verticalArrangement = Arrangement.spacedBy(PAGE_GAP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(minOf(shown, pages.sizes.size)) { i -> PdfPage(pages, i, pageW, cache) }
                    if (shown < pages.sizes.size) item {
                        ZillitButton(
                            t("csync_doc_show_more_pages", "n" to minOf(PAGE_STEP, pages.sizes.size - shown)),
                            onClick = { shown += PAGE_STEP },
                            variant = ButtonVariant.Secondary,
                        )
                    }
                }
            }
        }
    }
}

private val MIN_PAGE_W = 120.dp

/** `Page 3 of 41`, then zoom out / level / zoom in / fit to width. No download or print: those are Open in new tab. */
@Composable
private fun PdfBar(page: Int, count: Int, zoomAt: Int, onZoom: (Int) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MutedText(t("csync_doc_page_of", "page" to page, "pages" to count), Modifier.weight(1f))
        ZillitIconButton(
            ZillitIcons.Minus, t("csync_doc_zoom_out"), onClick = { onZoom(zoomAt - 1) }, enabled = zoomAt > 0,
        )
        ZillitText(
            "${(ZOOMS[zoomAt] * PERCENT).toInt()}%",
            Modifier.width(44.dp),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        ZillitIconButton(
            ZillitIcons.Add,
            t("csync_doc_zoom_in"),
            onClick = { onZoom(zoomAt + 1) },
            enabled = zoomAt < ZOOMS.lastIndex,
        )
        ZillitIconButton(
            ZillitIcons.Maximize, t("csync_doc_fit_width"), onClick = { onZoom(FIT) }, enabled = zoomAt != FIT,
        )
    }
}

private const val PERCENT = 100

/** One sheet of paper: white at the page's own proportions, its picture drawn when it comes into view. */
@Composable
private fun PdfPage(pages: PdfPages, index: Int, width: Dp, cache: PageCache) {
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    var bitmap by remember(pages, index) { mutableStateOf(cache.get(index, widthPx)) }
    var failed by remember(pages, index) { mutableStateOf(false) }
    // A zoom keeps the old picture (stretched) until the sharper one is ready.
    LaunchedEffect(pages, index, widthPx) {
        val ready = cache.get(index, widthPx) ?: pages.render(index, widthPx)?.also { cache.put(index, widthPx, it) }
        if (ready == null) failed = true else bitmap = ready
    }
    val size = pages.sizes[index]
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier.width(width).aspectRatio(size.width / size.height).clip(shape).background(Color.White)
            .border(1.dp, ZillitTheme.colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        val picture = bitmap
        when {
            picture != null -> Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            failed -> MutedText(t("csync_doc_preview_failed"), Modifier.padding(12.dp), maxLines = 2)
            else -> ZillitSpinner(size = 18.dp)
        }
    }
}
