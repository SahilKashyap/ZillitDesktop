package com.zillit.desktop.core.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmojiPicker
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screen between picking files and sending them, laid out as WhatsApp
 * lays its media editor out: a dark layer over the conversation; close, the
 * picture's tools (rotate, crop, draw, text) and remove along the top; the
 * item large in the middle — a picture with its edits, a PDF's first page
 * under its name and page count, a clip's frame; the caption pill; then the
 * strip of everything selected with a "+" for more, and the round send with
 * its count.
 *
 * Every composer that attaches a file hosts this the same way: map its
 * picked files onto [PreviewItem]s, keep each item's identity stable across
 * recompositions (`remember` them — the session follows items by identity),
 * and take [onSend]'s [PreviewResult]s — the edited pictures re-encoded, the
 * rest as they came — plus the caption. Nothing here uploads or posts.
 *
 * A host that lists more items while the screen is open — its [onAddMore]
 * answered — keeps the session: edits, captions and removals stand, and the
 * first new item comes on screen. A new first item starts a fresh session.
 *
 * Laid over its parent like the dialog shell it replaces: the host composes
 * it last inside a full-size box. Kept composed with an empty [items] so the
 * fade out can play; the last non-empty list is remembered so the fading
 * screen still has content.
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
    /** The strip's "+": the host picks more of a kind and lists them. Null hides it. */
    onAddMore: ((PreviewKind) -> Unit)? = null,
    /** The kinds "+" offers. */
    addKinds: List<PreviewKind> = ALL_ATTACHMENT_KINDS,
    /**
     * One caption per item, each on its own [PreviewResult] — chat, where each
     * file is its own message. False keeps one caption for all, passed beside.
     */
    captionPerItem: Boolean = false,
) {
    var shown by remember { mutableStateOf(items) }
    if (items.isNotEmpty()) shown = items
    if (shown.isEmpty()) return
    val session = remember(shown.first()) { MediaPreviewSession(shown, initialCaption, captionPerItem) }
    LaunchedEffect(session, shown) { session.sync(shown) }
    DecodePictures(session)
    LoadPosters(session)

    // The on-screen measurer draws the canvas; a second one, at density 1
    // and with its own cache, composites on a background thread — so no
    // Skia paragraph is ever painted from two threads at once.
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val rasterMeasurer = remember(resolver) { TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr) }
    var sending by remember(session) { mutableStateOf(false) }
    var sendError by remember(session) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // One send, shared by the disc and the caption field's Enter — the
    // phones and the web both send on Enter from the preview's caption
    // (web `CNC_FIXES_CHANGELOG.md` Fix 7 added it there), and a screen
    // where Enter does nothing reads as one that ignored you.
    val send: () -> Unit = {
        sending = true
        scope.launch {
            // Compositing and encoding a full photo is real work;
            // off the UI thread so the screen does not freeze.
            val results = withContext(Dispatchers.Default) { session.results(rasterMeasurer) }
            sending = false
            if (results != null) {
                onSend(results, results.firstOrNull()?.caption?.takeIf { captionPerItem } ?: session.caption.trim())
            } else {
                sendError = str(S.desktop_media_encode_failed)
            }
        }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        // WhatsApp's editor is dark whatever the theme: the picture is what
        // is lit, and a white frame around a photo fights it.
        ZillitTheme(darkTheme = true, animateThemeChange = false) {
            EditorScreen(
                session = session,
                measurer = measurer,
                sending = sending,
                error = sendError,
                captionLimit = captionLimit,
                onSend = send,
                onCancel = onCancel,
                onAddMore = onAddMore,
                addKinds = addKinds,
            )
        }
    }
}

/** The layer itself: top bar, stage, and — out of the editor — caption and strip. */
@Composable
@Suppress("LongParameterList") // The screen's state and its verbs, passed once.
private fun EditorScreen(
    session: MediaPreviewSession,
    measurer: TextMeasurer,
    sending: Boolean,
    error: String?,
    captionLimit: Int,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onAddMore: ((PreviewKind) -> Unit)?,
    addKinds: List<PreviewKind>,
) {
    val editing = session.tool != null
    Column(
        Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            // Swallows clicks so nothing under the layer answers them.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        PreviewTopBar(session, measurer, onClose = { if (editing) session.tool = null else onCancel() })
        if (editing) ToolRow(session, measurer)
        ItemStage(
            session,
            measurer,
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
        )
        if (!editing) {
            CaptionBar(session, captionLimit, sending, onSend)
            Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
            BottomBar(session, sending, error, captionLimit, onSend, onAddMore, addKinds)
        }
    }
}

/** The open tool's row — the pen's colours, the crop's Apply, the text line — and Discard / Done. */
@Composable
private fun ToolRow(session: MediaPreviewSession, measurer: TextMeasurer) {
    val edit = session.currentEdit ?: return
    val tool = session.tool ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(Modifier.weight(1f)) {
            when (tool) {
                EditTool.Draw -> PenToolbar(edit.pen)
                EditTool.Crop -> CropToolbar(
                    canApply = edit.cropBand != null,
                    onApply = { edit.applyCrop(measurer) },
                    onReset = { edit.cropBand = null },
                )
                EditTool.Text -> TextToolbar(edit.text, onPlace = { placeText(edit) })
            }
        }
        ZillitButton(
            text = str(S.desktop_media_discard_edits),
            onClick = { edit.reset() },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = edit.isEdited,
        )
        ZillitButton(text = str(S.ah_done), onClick = { session.tool = null }, size = ButtonSize.Small)
    }
}

/** The caption pill with its emoji palette — the current item's, or the one for all. */
@Composable
private fun CaptionBar(session: MediaPreviewSession, captionLimit: Int, sending: Boolean, onSend: () -> Unit) {
    var emojiOpen by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        ZillitTextField(
            value = session.caption,
            onValueChange = { session.caption = it },
            placeholder = str(S.desktop_media_add_caption),
            singleLine = false,
            // The counter only once it matters: an always-on "0/2000" is
            // clutter WhatsApp's pill does not carry. Send refuses past it.
            maxLength = captionLimit.takeIf { session.caption.length > captionLimit - COUNTER_HEADROOM },
            containerColor = ZillitTheme.colors.surfaceRaised,
            shape = RoundedCornerShape(CAPTION_CORNER),
            trailingContent = {
                Box {
                    ZillitIconButton(
                        icon = ZillitIcons.Smiley,
                        contentDescription = str(S.desktop_insert_an_emoji),
                        onClick = { emojiOpen = true },
                        tint = ZillitTheme.colors.textSecondary,
                    )
                    ZillitMenuSurface(expanded = emojiOpen, onDismissRequest = { emojiOpen = false }) {
                        ZillitEmojiPicker(
                            onPick = { emoji -> session.caption = session.caption + emoji },
                            modifier = Modifier.padding(ZillitTheme.spacing.sm),
                        )
                    }
                }
            },
            modifier = Modifier.widthIn(max = CAPTION_MAX_WIDTH).fillMaxWidth().onPreviewKeyEvent { event ->
                // Enter sends, Shift+Enter starts a line — the composer's own
                // bargain, so the two fields behave alike. A send already in
                // flight swallows the key rather than firing twice.
                val enter = event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed
                if (enter && !sending) onSend()
                enter
            },
        )
    }
}

/** The strip, centred, with the send disc at the right and any refusal at the left. */
@Composable
@Suppress("LongParameterList") // The bar's state and its two verbs.
private fun BottomBar(
    session: MediaPreviewSession,
    sending: Boolean,
    error: String?,
    captionLimit: Int,
    onSend: () -> Unit,
    onAddMore: ((PreviewKind) -> Unit)?,
    addKinds: List<PreviewKind>,
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
    ) {
        error?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier.align(Alignment.CenterStart).widthIn(max = ERROR_WIDTH),
            )
        }
        ThumbnailStrip(
            session,
            onAddMore,
            addKinds,
            Modifier.align(Alignment.Center).padding(horizontal = STRIP_INSET),
        )
        SendDisc(
            count = session.items.size,
            enabled = session.longestCaption <= captionLimit,
            sending = sending,
            onSend = onSend,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
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

/**
 * Decodes every picture in the session once, off the UI thread, and hands
 * each to its editor — again whenever "+" brings more. Pictures that will not
 * decode are marked so the preview shows them as files, not "Loading…".
 */
@Composable
private fun DecodePictures(session: MediaPreviewSession) {
    LaunchedEffect(session, session.items) {
        session.items.filter { it.kind == PreviewKind.Image }.forEach { item ->
            val edit = session.editFor(item)
            if (edit.original != null || item in session.undecodable) return@forEach
            val bitmap = withContext(Dispatchers.Default) { decodeImageBitmap(item.bytes) }
            if (bitmap != null) edit.load(bitmap) else session.markUndecodable(item)
        }
    }
}

/** Asks the app's poster maker, once per file, for what the stage and strip show. */
@Composable
private fun LoadPosters(session: MediaPreviewSession) {
    val maker = LocalPreviewPosterMaker.current ?: return
    LaunchedEffect(session, session.items, maker) {
        session.items.filter { !session.isEditable(it) && it !in session.posters }.forEach { item ->
            session.posters[item] = withContext(Dispatchers.Default) { maker.poster(item) }
        }
    }
}

/** `Constants.TEXT_LIMIT` on Android — the board's caption ceiling; chat passes its own. */
const val DEFAULT_CAPTION_LIMIT = 2000
private const val TEXT_PLACE_X = 0.08f
private const val TEXT_PLACE_Y = 0.4f
private const val TEXT_LINE_GAP = 1.4f
private val HAIRLINE = 1.dp

/** How close to the caption limit the counter appears. */
private const val COUNTER_HEADROOM = 200
private val CAPTION_CORNER = 10.dp
private val CAPTION_MAX_WIDTH = 900.dp
private val ERROR_WIDTH = 220.dp
private val STRIP_INSET = 96.dp
