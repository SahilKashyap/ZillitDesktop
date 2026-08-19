package com.zillit.desktop.core.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The preview between picking a file and sending it — the desktop's
 * `GalleryItemView` (Android `mediaHandler/gallery/GalleryViewer.kt`,
 * `activity_gallery_item_viewer.xml`): the picked items large, one at a
 * time, with a filmstrip when there are several, a caption, "Media
 * selected: N", remove-item while more than one remains, and Send. A
 * picture's Edit opens the three tools of `EditImageActivity`'s menu —
 * draw, crop, add text (`fragment_edit_image_main_menu.xml`) — plus a
 * quarter-turn rotate; videos, documents and audio show their poster or a
 * file glyph and take no tools.
 *
 * Every composer that attaches a file hosts this the same way: map its
 * picked files onto [PreviewItem]s, keep the list stable across
 * recompositions (`remember` it — a fresh list restarts the session), and
 * take [onSend]'s [PreviewResult]s — the edited pictures re-encoded, the
 * rest as they came — plus the caption. Nothing here uploads or posts.
 *
 * Kept composed with an empty [items] so the shell's exit can play; the
 * last non-empty list is remembered so the fading card still has content.
 */
@Composable
fun MediaPreviewDialog(
    items: List<PreviewItem>,
    onSend: (List<PreviewResult>, caption: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the caption field starts with — the composer's half-typed draft, usually. */
    initialCaption: String = "",
    /** The caption's ceiling; the field counts against it and Send refuses past it. */
    captionLimit: Int = DEFAULT_CAPTION_LIMIT,
    visible: Boolean = items.isNotEmpty(),
) {
    var shown by remember { mutableStateOf(items) }
    if (items.isNotEmpty()) shown = items
    if (shown.isEmpty()) return
    val session = remember(shown) { MediaPreviewSession(shown, initialCaption) }
    DecodePictures(session)

    // The on-screen measurer draws the canvas; a second one, at density 1
    // and with its own cache, composites on a background thread — so no
    // Skia paragraph is ever painted from two threads at once.
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val rasterMeasurer = remember(resolver) { TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr) }
    var sending by remember(session) { mutableStateOf(false) }
    var sendError by remember(session) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val editing = session.tool != null

    ZillitDialogShell(
        title = if (editing) "Edit picture" else "Send media",
        subtitle = if (editing) "Draw, crop, or add text — then Done." else "Check what you picked and add a caption.",
        icon = ZillitIcons.Photo,
        visible = visible,
        onDismiss = { if (editing) session.tool = null else onCancel() },
        width = DIALOG_WIDTH,
        maxHeight = DIALOG_MAX_HEIGHT,
        // The picture takes whatever height the dialog gets and the caption
        // sits under it; a fixed-height canvas in a scrolling body pushed
        // the caption below the fold on a laptop screen (the image reply
        // learned this live).
        scrollable = false,
        modifier = modifier,
        actions = {
            if (editing) {
                EditActions(session)
            } else {
                PreviewActions(
                    session = session,
                    sending = sending,
                    captionLimit = captionLimit,
                    error = sendError,
                    onCancel = onCancel,
                    onSend = {
                        sending = true
                        scope.launch {
                            // Compositing and encoding a full photo is real work;
                            // off the UI thread so the dialog does not freeze.
                            val results = withContext(Dispatchers.Default) { session.results(rasterMeasurer) }
                            sending = false
                            if (results != null) onSend(results, session.caption.trim()) else sendError = ENCODE_FAILED
                        }
                    },
                )
            }
        },
    ) {
        if (editing) EditBody(session, measurer) else PreviewBody(session, measurer, captionLimit)
    }
}

/** Preview mode: the item large, its toolbar, the filmstrip, the caption. */
@Composable
private fun ColumnScope.PreviewBody(session: MediaPreviewSession, measurer: TextMeasurer, captionLimit: Int) {
    val item = session.current ?: return
    PreviewToolbar(session, measurer)
    ItemPreview(session, item, Modifier.weight(1f))
    if (session.items.size > 1) Filmstrip(session)
    ZillitTextField(
        value = session.caption,
        onValueChange = { session.caption = it },
        placeholder = "Add a caption…",
        singleLine = false,
        maxLength = captionLimit,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Edit mode: the tool tabs, the active tool's row, and the canvas taking input. */
@Composable
private fun ColumnScope.EditBody(session: MediaPreviewSession, measurer: TextMeasurer) {
    val edit = session.currentEdit ?: return
    val tool = session.tool ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        EditTool.entries.forEach { candidate ->
            ZillitChoiceChip(
                label = candidate.name,
                selected = tool == candidate,
                onClick = { session.tool = candidate },
            )
        }
    }
    when (tool) {
        EditTool.Draw -> PenToolbar(edit.pen)
        EditTool.Crop -> CropToolbar(
            canApply = edit.cropBand != null,
            onApply = { edit.applyCrop(measurer) },
            onReset = { edit.cropBand = null },
        )
        EditTool.Text -> TextToolbar(edit.text, onPlace = { placeText(edit) })
    }
    EditableImageCanvas(edit, tool, Modifier.weight(1f), measurer)
}

/** Drops the typed line a little in from the top-left, each new one a line lower — the drag does the rest. */
private fun placeText(edit: ImageEditState) {
    val bitmap = edit.working ?: return
    val sizePx = textSizeFor(bitmap, edit.text.sizeIndex)
    val at = Offset(
        x = bitmap.width * TEXT_PLACE_X,
        y = (bitmap.height * TEXT_PLACE_Y + edit.text.texts.size * sizePx * TEXT_LINE_GAP)
            .coerceAtMost(bitmap.height - sizePx),
    )
    edit.text.place(at, sizePx)
}

/** "Media selected: N", Cancel, Send — the gallery viewer's footer. */
@Composable
private fun RowScope.PreviewActions(
    session: MediaPreviewSession,
    sending: Boolean,
    captionLimit: Int,
    error: String?,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    ZillitText(
        text = error ?: "Media selected: ${session.items.size}",
        style = ZillitTheme.typography.labelSmall,
        color = if (error != null) ZillitTheme.colors.danger else ZillitTheme.colors.textMuted,
    )
    Spacer(Modifier.weight(1f))
    ZillitButton(text = "Cancel", onClick = onCancel, variant = ButtonVariant.Secondary, enabled = !sending)
    ZillitButton(
        text = if (sending) "Preparing…" else "Send",
        onClick = onSend,
        enabled = !sending && session.caption.length <= captionLimit,
        loading = sending,
    )
}

/** Discard edits, Done — the editor's own footer. */
@Composable
private fun RowScope.EditActions(session: MediaPreviewSession) {
    val edit = session.currentEdit
    Spacer(Modifier.weight(1f))
    ZillitButton(
        text = "Discard edits",
        onClick = { edit?.reset() },
        variant = ButtonVariant.Secondary,
        enabled = edit?.isEdited == true,
    )
    ZillitButton(text = "Done", onClick = { session.tool = null })
}

/**
 * Decodes every picture in the session once, off the UI thread, and hands
 * each to its editor. Pictures that will not decode are marked so the
 * preview shows them as files rather than "Loading…" forever.
 */
@Composable
private fun DecodePictures(session: MediaPreviewSession) {
    LaunchedEffect(session) {
        session.items.filter { it.kind == PreviewKind.Image }.forEach { item ->
            val edit = session.editFor(item)
            if (edit.original != null) return@forEach
            val bitmap = withContext(Dispatchers.Default) { decodeImageBitmap(item.bytes) }
            if (bitmap != null) edit.load(bitmap) else session.markUndecodable(item)
        }
    }
}

/** `Constants.TEXT_LIMIT` on Android — the board's caption ceiling; chat passes its own. */
const val DEFAULT_CAPTION_LIMIT = 2000
private const val ENCODE_FAILED = "Could not save the edited picture."
private const val TEXT_PLACE_X = 0.08f
private const val TEXT_PLACE_Y = 0.4f
private const val TEXT_LINE_GAP = 1.4f
private val DIALOG_WIDTH = 840.dp
private val DIALOG_MAX_HEIGHT = 820.dp
