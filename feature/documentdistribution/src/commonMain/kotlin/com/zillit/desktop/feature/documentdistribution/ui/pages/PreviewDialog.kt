package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitViewerClose
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.permissions.gatedClick
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.FileKind
import com.zillit.desktop.feature.documentdistribution.domain.fileKindOf
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.PreviewState

/**
 * The in-app preview — the web's `FilePreviewModal`: PDF pages and images
 * inline, a vCard as text, everything else a "download to open" card. When
 * opened from the composer's watermark eye, the stamp is drawn over the
 * pages so the sender sees what each recipient will.
 *
 * It takes the WHOLE window, not the tool's pane: a document is read, not
 * glanced at, and a dialog in a pane beside the sidebar left the page the size
 * of a postcard. A header bar carries the name and the actions, and Escape
 * closes it.
 */
@Composable
internal fun FilePreviewDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val preview = state.preview ?: return
    val close = { onEvent(DocDistEvent.ClosePreview) }
    // A full-window layer, as the call log uses for a dialog whose host is a narrow pane.
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = close,
        properties = PopupProperties(focusable = true),
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(
            Modifier.fillMaxSize()
                .background(VIEWER_BACKDROP)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (escape) close()
                    escape
                },
        ) {
            ViewerHeader(state, preview, onEvent)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                PreviewBody(preview, previewStamp(state, preview), onEvent, state.viewer.canPost)
            }
        }
    }
}

/** The composer's stamp, only when this preview was opened from its watermark eye. */
private fun previewStamp(state: DocDistUiState, preview: PreviewState) =
    state.composer.watermarkPreview?.takeIf { it.id == preview.document.id }?.let { state.composer.watermark }

/** The name and what can be done with the file, over the dark viewer. */
@Suppress("LongMethod") // One bar; splitting it separates each action from the gate that guards it.
@Composable
private fun ViewerHeader(state: DocDistUiState, preview: PreviewState, onEvent: (DocDistEvent) -> Unit) {
    val document = preview.document
    val canPost = state.viewer.canPost
    val canDownload = state.viewer.canDownload
    val available = !preview.loading && preview.error == null
    val actionable = previewStamp(state, preview) == null && available
    Row(
        modifier = Modifier.fillMaxWidth().background(VIEWER_BAR)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FileGlyph(document, size = HEADER_GLYPH.dp)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = document.name,
                style = ZillitTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
            )
            ZillitText(
                text = formatBytes(document.sizeBytes) +
                    prettyIsoDate(document.documentDate).takeIf { it != "—" }?.let { " · $it" }.orEmpty(),
                style = ZillitTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
            )
        }
        if (actionable) {
            ZillitButton(
                text = str(S.desktop_docdist_send_by_email),
                onClick = gatedClick(canPost, { onEvent(askPost) }) {
                    onEvent(DocDistEvent.DistributeDocument(document.id))
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
            )
            if (document.isWatermarkable) {
                ZillitButton(
                    text = str(S.dd_watermark),
                    onClick = gatedClick(canDownload, { onEvent(askDownload) }) {
                        onEvent(DocDistEvent.OpenWatermarkDownload(document.id))
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Shield,
                )
            }
            ZillitButton(
                text = str(S.download),
                onClick = gatedClick(canDownload, { onEvent(askDownload) }) {
                    onEvent(DocDistEvent.DownloadDocument(document.id))
                },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
                loading = preview.downloading,
            )
        }
        ZillitViewerClose(onClose = { onEvent(DocDistEvent.ClosePreview) })
    }
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun PreviewBody(
    preview: PreviewState,
    stamp: com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle?,
    onEvent: (DocDistEvent) -> Unit,
    canPost: Boolean,
) {
    val kind = fileKindOf(preview.document.contentType, preview.document.name)
    when {
        preview.loading -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSpinner()
            ZillitText(text = str(S.dd_opening_document), color = Color(0xFFCBD5E1))
        }
        preview.error != null -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            modifier = Modifier.padding(ZillitTheme.spacing.xl),
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = Color(0xFFFBBF24), size = 48.dp)
            ZillitText(
                text = str(S.dd_load_failed),
                style = ZillitTheme.typography.titleMedium,
                color = Color.White,
            )
            ZillitText(
                text = preview.error,
                color = Color(0xFFCBD5E1),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            ZillitButton(
                text = str(S.dd_remove_record),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.RemoveMissingRecord) },
                variant = ButtonVariant.Danger,
                leadingIcon = ZillitIcons.Trash,
            )
        }
        kind == FileKind.Pdf && (preview.pages.isNotEmpty() || preview.pageCount > 0) ->
            PdfPages(preview.document.id, preview.pages, maxOf(preview.pageCount, preview.pages.size), stamp)
        kind == FileKind.Image && preview.bytes != null -> Box(
            Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            StampedImage(preview.bytes, stamp, fit = true)
        }
        kind == FileKind.VCard -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xl),
        ) {
            ZillitText(
                text = preview.text.orEmpty().ifBlank { "(empty)" },
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFFE2E8F0),
            )
        }
        else -> DownloadCard(kind, preview, onEvent)
    }
}

/** A rendered page or picture with the composer's stamp drawn across it. */
@Composable
private fun StampedImage(
    bytes: ByteArray,
    stamp: com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle?,
    fit: Boolean = false,
    /** A fixed page width; null lets a picture take what it needs, up to [PAGE_WIDTH]. */
    pageWidth: androidx.compose.ui.unit.Dp? = null,
) {
    val bitmap = remember(bytes) { decodeImageBitmap(bytes) } ?: return
    // The stamp is drawn at the size it would have on a 900dp page and grows or
    // shrinks with the page, so zooming does not change what it covers.
    val scale = ((pageWidth ?: PAGE_WIDTH.dp) / PAGE_WIDTH.dp).coerceAtLeast(MIN_STAMP_SCALE)
    Box(
        modifier = if (pageWidth != null) Modifier.width(pageWidth) else Modifier.widthIn(max = PAGE_WIDTH.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
        if (stamp != null) {
            val lines = stamp.render(emptyList()).split('\n').filter { it.isNotBlank() }.take(2)
            Column(modifier = Modifier.rotate(-30f), horizontalAlignment = Alignment.CenterHorizontally) {
                lines.forEachIndexed { index, line ->
                    val size = (STAMP_SP * scale * stamp.size.scale * (if (index == 0) 1.0 else 0.7)).toFloat()
                    ZillitText(
                        text = line,
                        style = ZillitTheme.typography.titleLarge.copy(
                            fontSize = size.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        ),
                        color = hexColor(stamp.color).copy(alpha = stamp.opacity.toFloat()),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(kind: FileKind, preview: PreviewState, onEvent: (DocDistEvent) -> Unit) {
    val (title, sub) = when (kind) {
        FileKind.Word -> str(S.desktop_docdist_word_document) to str(S.desktop_docdist_word_preview_hint)
        FileKind.Excel -> str(S.desktop_file_kind_spreadsheet) to str(S.desktop_docdist_excel_preview_hint)
        FileKind.ImageUnsupported ->
            str(S.desktop_preview_not_available) to str(S.desktop_docdist_image_not_renderable)
        else -> str(S.desktop_docdist_preview_not_available_for_type) to str(S.desktop_docdist_download_to_open_native)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.padding(ZillitTheme.spacing.xl),
    ) {
        FileGlyph(preview.document, size = 64.dp)
        ZillitText(text = title, style = ZillitTheme.typography.titleMedium, color = Color.White)
        ZillitText(text = sub, color = Color(0xFFCBD5E1), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        ZillitButton(
            text = str(S.dd_download_to_open),
            onClick = { onEvent(DocDistEvent.DownloadDocument(preview.document.id)) },
            leadingIcon = ZillitIcons.Download,
            loading = preview.downloading,
        )
    }
}

/**
 * The PDF as a scrolling stack of white pages: fitted to the width of the
 * viewer, a page counter, and zoom from half to double. Only the pages near the
 * screen are laid out and decoded, so a 23-page document opens at once.
 */
@Suppress("LongMethod") // One viewer; the toolbar, the list and the zoom share their state.
@Composable
private fun PdfPages(
    documentId: String,
    pages: List<ByteArray>,
    pageCount: Int,
    stamp: com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle?,
) {
    var zoom by remember(documentId) { mutableStateOf(1f) }
    val listState = rememberLazyListState()
    val across = rememberScrollState()
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = ZillitTheme.spacing.xl
        val room = maxWidth - gutter * 2
        // 100% is a comfortable reading width, not the whole window: a page drawn 1,200dp
        // across on a wide screen is bigger than anyone reads at.
        val base = minOf(room, READING_WIDTH.dp)
        val fit = (room / base).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val atFit = abs(zoom - fit) < FIT_TOLERANCE
        val pageWidth = base * zoom
        // Zoomed past the viewer, the stack scrolls sideways rather than being clipped.
        val content = maxOf(maxWidth, pageWidth + gutter * 2)
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(VIEWER_BAR).padding(
                    horizontal = ZillitTheme.spacing.md,
                    vertical = ZillitTheme.spacing.xs,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = str(S.docusign_page_of, current.coerceAtMost(pageCount), pageCount),
                    style = ZillitTheme.typography.label,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = "−",
                    onClick = { zoom = (zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = zoom > MIN_ZOOM,
                )
                ZillitText(
                    text = "${(zoom * PERCENT).roundToInt()}%",
                    style = ZillitTheme.typography.label,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier.width(ZOOM_LABEL.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                ZillitButton(
                    text = "+",
                    onClick = { zoom = (zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = zoom < MAX_ZOOM,
                )
                // One button, two jobs: fill the window, and once there, go back to reading size.
                val backToReading = atFit && fit != 1f
                ZillitButton(
                    text = str(zoomButtonLabel(backToReading)),
                    onClick = { zoom = if (backToReading) 1f else fit },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !atFit || backToReading,
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f).horizontalScroll(across)) {
                ZillitLazyColumn(
                    modifier = Modifier.width(content).fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = gutter),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(pageCount, key = { it }) { index ->
                        Box(
                            Modifier.shadow(PAGE_SHADOW.dp, ZillitTheme.shapes.small).background(Color.White),
                        ) {
                            val drawn = pages.getOrNull(index)
                            if (drawn != null) {
                                StampedImage(drawn, stamp, pageWidth = pageWidth)
                            } else {
                                // Not drawn yet: a blank page of the usual shape, so the scroll bar and
                                // the counter are right from the start.
                                Box(
                                    Modifier.width(pageWidth).height(pageWidth * A4_RATIO),
                                    contentAlignment = Alignment.Center,
                                ) { ZillitSpinner() }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun zoomButtonLabel(backToReading: Boolean): String =
    if (backToReading) S.desktop_docdist_preview_reset_zoom else S.desktop_docdist_preview_fit_width

private val VIEWER_BACKDROP = Color(0xFF1F2937)
private val VIEWER_BAR = Color(0xFF111827)



private const val HEADER_GLYPH = 36
private const val PAGE_WIDTH = 900

/** 100%: a page large enough to read comfortably on a full-window viewer. */
private const val READING_WIDTH = 1280
private const val FIT_TOLERANCE = 0.01f
private const val STAMP_SP = 34.0
private const val MIN_STAMP_SCALE = 0.3f
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 2f
private const val ZOOM_STEP = 0.25f
private const val ZOOM_LABEL = 48
private const val PERCENT = 100
private const val PAGE_SHADOW = 6

/** Height over width of an A4 page, for a page still being drawn. */
private const val A4_RATIO = 1.414f
