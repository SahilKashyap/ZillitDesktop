package com.zillit.desktop.core.media

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitViewerClose
import com.zillit.desktop.core.designsystem.component.onBackdropTap
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
        Column(Modifier.fillMaxSize()) {
            ViewerHeader(fileName, refused && refusal != null, refusal, download, onClose)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                DocumentStage(fileName, document, download)
            }
        }
    }
}

/** Name on the left, the gated Download and the close on the right. */
@Composable
private fun ViewerHeader(
    fileName: String,
    refused: Boolean,
    refusal: String?,
    onDownload: (() -> Unit)?,
    onClose: () -> Unit,
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

@Composable
private fun DocumentStage(fileName: String, document: DocumentState, onDownload: (() -> Unit)?) {
    when (document) {
        DocumentState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSpinner(color = Color.White)
            StageNote(str(S.ah_loading))
        }

        DocumentState.Failed -> StageNote(str(S.desktop_could_not_load_file))

        is DocumentState.Ready -> ReadyStage(fileName, document.body, onDownload)
    }
}

@Composable
private fun ReadyStage(fileName: String, body: DocumentBody, onDownload: (() -> Unit)?) {
    when (body) {
        is DocumentBody.Picture -> Image(
            bitmap = body.image,
            contentDescription = fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(STAGE_PADDING),
        )

        is DocumentBody.Pages -> PagesStage(body.pages)

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

/** A PDF's pages, stacked to scroll. */
@Composable
private fun PagesStage(pages: List<ImageBitmap>) {
    ZillitScrollColumn(
        // The pages swallow their own presses: a click on a page, or on the
        // gap between two of them, is inside the document.
        modifier = Modifier.fillMaxSize().swallowPresses(),
        contentPadding = PaddingValues(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        pages.forEach { page ->
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
