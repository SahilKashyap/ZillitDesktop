package com.zillit.desktop.core.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitViewerClose
import com.zillit.desktop.core.designsystem.component.onBackdropTap
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.swallowPresses
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A received document, open over whatever showed it — the lightbox a picture
 * or a clip opens, with pages in it instead.
 *
 * Opening is *looking*, which no right governs, so the whole document is drawn
 * here for anyone who can see the message it came on. Saving to disk is the
 * separate, gated act: [onDownload] asks [canDownload] first and says
 * [refusal] where the button's promise was when the answer is no — the same
 * bargain the picture lightbox strikes. For a format the app cannot draw, that
 * download is also the way the file opens at all, so the stage offers it
 * beside the badge rather than only in the header.
 *
 * Escape closes it, as it does in every other viewer in the app, and so does a
 * click on the dark around the page.
 */
@Composable
fun DocumentViewer(
    fileName: String,
    document: DocumentState,
    onClose: () -> Unit,
    /** Saves to disk; null leaves the button off a build that cannot save. */
    onDownload: (() -> Unit)? = null,
    canDownload: () -> Boolean = { true },
    /** The sentence shown in place of the save; null never refuses. */
    refusal: String? = null,
) {
    var refused by remember(fileName) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Focused so Escape closes it, as every other viewer in the app does.
    LaunchedEffect(fileName) { runCatching { focus.requestFocus() } }
    val download: (() -> Unit)? = onDownload?.let {
        { if (canDownload()) it() else refused = true }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = VIEWER_SCRIM))
            .onBackdropTap(onClose)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape) onClose()
                escape
            },
    ) {
        // Which page is in view, and how big the pages are drawn. Both live
        // here rather than in the stage so the header can show them: a reader
        // of a forty-page call sheet needs to know where they are, and a
        // scanned page at window width is often unreadable.
        var zoom by remember(fileName) { mutableIntStateOf(FIT_ZOOM) }
        var page by remember(fileName) { mutableIntStateOf(1) }
        val pageCount = ((document as? DocumentState.Ready)?.body as? DocumentBody.Pages)?.pages?.size ?: 0
        Column(Modifier.fillMaxSize()) {
            ViewerHeader(
                fileName = fileName,
                refused = refused && refusal != null,
                refusal = refusal,
                onDownload = download,
                onClose = onClose,
                pageCount = pageCount,
                page = page,
                zoom = zoom,
                onZoom = { zoom = it },
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                DocumentStage(fileName, document, download, zoom) { page = it }
            }
        }
    }
}

/**
 * Name on the left; then, for a paged document, where the reader is and how
 * big the pages are; then the gated Download and the close.
 *
 * [pageCount] 0 is "not a paged document" — a picture or a text file — and
 * leaves both controls off rather than showing "1 of 0" over a photograph.
 */
@Suppress("LongParameterList")
@Composable
private fun ViewerHeader(
    fileName: String,
    refused: Boolean,
    refusal: String?,
    onDownload: (() -> Unit)?,
    onClose: () -> Unit,
    pageCount: Int = 0,
    page: Int = 1,
    zoom: Int = FIT_ZOOM,
    onZoom: (Int) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The viewer's own strip: a click here is not a click outside the
            // page, so it closes nothing.
            .swallowPresses()
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = fileName,
            style = ZillitTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (pageCount > 0) {
            ZillitText(
                text = str(S.docusign_page_of, page.coerceIn(1, pageCount), pageCount),
                style = ZillitTheme.typography.labelSmall,
                color = Color.White,
            )
            ZoomControl(zoom, onZoom)
        }
        if (refused && refusal != null) {
            ZillitText(
                text = refusal,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
        if (onDownload != null) {
            ZillitButton(
                text = str(S.download),
                onClick = onDownload,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
            )
        }
        ZillitViewerClose(onClose = onClose)
    }
}

/**
 * Fit, then a fixed ladder up to four times the page width.
 *
 * Fit is the first step, not a separate mode: a reader who zooms out past the
 * window has asked for the whole page.
 */
@Composable
private fun ZoomControl(zoom: Int, onZoom: (Int) -> Unit) {
    val index = ZOOM_STEPS.indexOf(zoom).coerceAtLeast(0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.Minus,
            contentDescription = str(S.docusign_zoom_out),
            onClick = { onZoom(ZOOM_STEPS[(index - 1).coerceAtLeast(0)]) },
            enabled = index > 0,
            tint = Color.White,
        )
        ZillitText(
            text = if (zoom == FIT_ZOOM) str(S.desktop_fit) else "$zoom%",
            style = ZillitTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.widthIn(min = ZOOM_LABEL_WIDTH),
            textAlign = TextAlign.Center,
        )
        ZillitIconButton(
            icon = ZillitIcons.Add,
            contentDescription = str(S.docusign_zoom_in),
            onClick = { onZoom(ZOOM_STEPS[(index + 1).coerceAtMost(ZOOM_STEPS.lastIndex)]) },
            enabled = index < ZOOM_STEPS.lastIndex,
            tint = Color.White,
        )
    }
}

@Composable
private fun DocumentStage(
    fileName: String,
    document: DocumentState,
    onDownload: (() -> Unit)?,
    zoom: Int = FIT_ZOOM,
    onPage: (Int) -> Unit = {},
) {
    when (document) {
        DocumentState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSpinner(color = Color.White)
            StageNote(str(S.ah_loading))
        }

        DocumentState.Failed -> StageNote(str(S.desktop_could_not_load_file))

        is DocumentState.Ready -> ReadyStage(fileName, document.body, onDownload, zoom, onPage)
    }
}

@Composable
private fun ReadyStage(
    fileName: String,
    body: DocumentBody,
    onDownload: (() -> Unit)?,
    zoom: Int = FIT_ZOOM,
    onPage: (Int) -> Unit = {},
) {
    when (body) {
        is DocumentBody.Picture -> Image(
            bitmap = body.image,
            contentDescription = fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(STAGE_PADDING),
        )

        is DocumentBody.Pages -> PagesStage(body.pages, zoom, onPage)

        is DocumentBody.Text -> ZillitScrollColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(STAGE_PADDING)
                .clip(ZillitTheme.shapes.medium)
                // Text gets a page of its own: monospace straight onto the
                // scrim is a terminal, not a document.
                .background(ZillitTheme.colors.surface)
                .swallowPresses(),
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

        DocumentBody.Unsupported -> UndrawableStage(fileName, onDownload)
    }
}

/**
 * A PDF's pages, stacked to scroll.
 *
 * At [FIT_ZOOM] a page is the window's width (up to [PAGE_MAX_WIDTH]); above
 * it the pages are drawn wider than the window and the stage scrolls sideways
 * too, which is the whole point of zooming in on a scan.
 *
 * [onPage] reports the page the reader is looking at — the first one whose top
 * is at or above the viewport's own top, so a page that fills the window
 * counts as the page in view rather than the one before it.
 */
@Composable
private fun PagesStage(pages: List<ImageBitmap>, zoom: Int, onPage: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val current by remember(pages) {
        derivedStateOf { listState.firstVisibleItemIndex + 1 }
    }
    LaunchedEffect(current) { onPage(current) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = minOf(maxWidth - STAGE_GUTTER, PAGE_MAX_WIDTH)
        val pageWidth = if (zoom == FIT_ZOOM) fit else fit * (zoom / PERCENT)
        val horizontal = rememberScrollState()
        LazyColumn(
            state = listState,
            // The pages swallow their own presses: a click on a page, or on
            // the gap between two of them, is inside the document.
            modifier = Modifier
                .fillMaxSize()
                .swallowPresses()
                .then(if (zoom == FIT_ZOOM) Modifier else Modifier.horizontalScroll(horizontal))
                .then(rememberWheelScroll(listState)),
            contentPadding = PaddingValues(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(pages) { page ->
                Image(
                    bitmap = page,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .width(pageWidth)
                        .aspectRatio(page.width.toFloat() / page.height.coerceAtLeast(1))
                        // A page is paper whatever the theme.
                        .background(Color.White),
                )
            }
        }
    }
}

/**
 * A file with no renderer here — a spreadsheet, an archive. Said plainly,
 * rather than left as an empty stage, with the download that is the only way
 * into it.
 */
@Composable
private fun UndrawableStage(fileName: String, onDownload: (() -> Unit)?) {
    Column(
        modifier = Modifier.swallowPresses(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitFileBadge(fileName = fileName)
        StageNote(str(S.desktop_dm_preview_not_available_for_this_file_type))
        if (onDownload != null) {
            ZillitButton(
                text = str(S.desktop_dm_download_to_view),
                onClick = onDownload,
                leadingIcon = ZillitIcons.Download,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun StageNote(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = Color.White,
        textAlign = TextAlign.Center,
    )
}

/**
 * Fetches a file and renders it, keyed on [key] so stepping to another file
 * says "loading" rather than holding the last one under a new name.
 *
 * The fetch and the rasterising both happen off the UI thread: a long PDF
 * takes seconds, and the thread that would otherwise spend them is the one
 * drawing the spinner.
 */
@Composable
fun rememberDocument(
    key: Any?,
    fileName: String,
    contentType: String?,
    load: suspend () -> ByteArray?,
): DocumentState {
    val state by produceState<DocumentState>(DocumentState.Loading, key) {
        // A new file: "loading", not the last one's pages under its name.
        value = DocumentState.Loading
        val rendered = withContext(Dispatchers.Default) {
            runCatching { load() }.getOrNull()?.let { renderDocumentBody(fileName, contentType, it) }
        }
        value = rendered?.let(DocumentState::Ready) ?: DocumentState.Failed
    }
    return state
}

private const val VIEWER_SCRIM = 0.86f
private val STAGE_PADDING = 32.dp
private val PAGE_MAX_WIDTH = 900.dp

/** Fit first, then the ladder the other viewers in the app step through. */
private const val FIT_ZOOM = 0
private val ZOOM_STEPS = listOf(FIT_ZOOM, 50, 75, 100, 125, 150, 200, 300, 400)
private const val PERCENT = 100f
private val ZOOM_LABEL_WIDTH = 44.dp

/** Room for the scrim to show around a page drawn at Fit. */
private val STAGE_GUTTER = 48.dp
