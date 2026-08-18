package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeDraft
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.home.domain.PickedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The phones' "Image Reply": someone's picture, drawn on, and posted back as
 * a new picture with a caption.
 *
 * On Android the menu item opens the gallery editor over a downloaded copy
 * and hands the result to the ordinary media send (`handleImageReply` →
 * `uploadFilesInDb`); iOS does the same through its photo editor. The
 * desktop's editor is the useful subset — a pen in a few colours and
 * widths, undo, clear — because a circled prop or an arrow to a door is
 * what people actually draw on a call-sheet photo; the phones' stickers
 * and crops are not the point of the feature.
 *
 * Kept composed with [target] null so the shell's exit can play; the last
 * target is remembered so the fading card still has content.
 */
@Composable
internal fun ImageReplyDialog(
    target: Notice?,
    media: NoticeMediaSource?,
    onPost: (PickedMedia, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    if (target != null) shown = target
    val notice = shown ?: return
    val editor = remember(notice.id) { ImageEditorState() }
    val scope = rememberCoroutineScope()

    // The full-size picture — not the thumbnail: the strokes are composited
    // onto what gets posted, and posting a marked-up thumbnail would send
    // the crew a postage stamp.
    val bitmap by produceState<ImageBitmap?>(null, notice.id) {
        val attachment = notice.attachment ?: return@produceState
        val fetched = media?.fetch(attachment, preview = false)
        value = (fetched as? ZillitResult.Success)?.data?.let(::decodeImageBitmap)
    }
    var posting by remember(notice.id) { mutableStateOf(false) }

    ZillitDialogShell(
        title = "Image Reply",
        subtitle = "Draw on the picture and post it back to the board.",
        icon = ZillitIcons.Photo,
        visible = target != null,
        onDismiss = onDismiss,
        width = EDITOR_WIDTH,
        maxHeight = EDITOR_MAX_HEIGHT,
        // The canvas takes whatever height the dialog gets and the caption
        // sits under it; a fixed-height canvas in a scrolling body pushed the
        // caption below the fold on a laptop screen (seen live).
        scrollable = false,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = if (posting) "Posting…" else "Post",
                enabled = bitmap != null && !posting && !editor.captionOverLimit,
                onClick = {
                    val source = bitmap ?: return@ZillitButton
                    posting = true
                    scope.launch {
                        // Compositing and encoding a full photo is real work;
                        // off the UI thread so the dialog does not freeze.
                        val bytes = withContext(Dispatchers.Default) {
                            encodeImageJpeg(composite(source, editor.strokes), JPEG_QUALITY)
                        }
                        posting = false
                        if (bytes != null) {
                            onPost(PickedMedia(IMAGE_REPLY_NAME, "image/jpeg", bytes), editor.caption.trim())
                        }
                    }
                },
            )
        },
    ) {
        EditorToolbar(editor)
        EditorCanvas(bitmap, editor, Modifier.weight(1f))
        ZillitTextField(
            value = editor.caption,
            onValueChange = { editor.caption = it },
            placeholder = "Add a caption…",
            singleLine = false,
            maxLength = NoticeDraft.MAX_LENGTH,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The strokes, the pen, the caption — what the editor holds between frames.
 * Strokes are in *image* pixels regardless of how the picture is scaled to
 * fit the dialog, so the composite draws them where the hand put them.
 */
private class ImageEditorState {
    var strokes by mutableStateOf<List<PenStroke>>(emptyList())
    var current by mutableStateOf<PenStroke?>(null)
    var color by mutableStateOf(PEN_COLORS.first())
    var widthIndex by mutableStateOf(1)
    var caption by mutableStateOf("")

    val captionOverLimit: Boolean get() = caption.length > NoticeDraft.MAX_LENGTH

    fun undo() {
        strokes = strokes.dropLast(1)
    }

    fun clear() {
        strokes = emptyList()
        current = null
    }
}

/** One pen stroke in image pixels; a single point is a dot. */
private data class PenStroke(val points: List<Offset>, val color: Color, val width: Float)

/** Colour swatches, width chips, undo and clear — one row over the canvas. */
@Composable
private fun EditorToolbar(editor: ImageEditorState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        PEN_COLORS.forEach { color ->
            val selected = editor.color == color
            Box(
                modifier = Modifier
                    .size(SWATCH)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selected) SWATCH_RING_SELECTED else SWATCH_RING,
                        color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.border,
                        shape = CircleShape,
                    )
                    .clickable { editor.color = color },
            )
        }
        Spacer(Modifier.width(ZillitTheme.spacing.sm))
        PEN_WIDTHS.forEachIndexed { index, _ ->
            val selected = editor.widthIndex == index
            // The chip's dot grows with the width it selects.
            Box(
                modifier = Modifier
                    .size(SWATCH)
                    .clip(CircleShape)
                    .background(
                        if (selected) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surfaceSunken,
                    )
                    .clickable { editor.widthIndex = index },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(WIDTH_DOT_BASE * (index + 1))
                        .clip(CircleShape)
                        .background(ZillitTheme.colors.textPrimary),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        ZillitText(
            text = "Undo",
            style = ZillitTheme.typography.labelSmall,
            color = if (editor.strokes.isEmpty()) ZillitTheme.colors.textMuted else ZillitTheme.colors.accentText,
            modifier = Modifier.clickable(enabled = editor.strokes.isNotEmpty()) { editor.undo() },
        )
        ZillitText(
            text = "Clear",
            style = ZillitTheme.typography.labelSmall,
            color = if (editor.strokes.isEmpty()) ZillitTheme.colors.textMuted else ZillitTheme.colors.danger,
            modifier = Modifier.clickable(enabled = editor.strokes.isNotEmpty()) { editor.clear() },
        )
    }
}

/**
 * The picture, fitted to the dialog, with the pen over it.
 *
 * Pointer positions arrive in fitted pixels and are divided back into image
 * pixels before they are kept, so a stroke drawn on a picture shown at a
 * third of its size lands on the right third of the real thing.
 */
@Composable
private fun EditorCanvas(bitmap: ImageBitmap?, editor: ImageEditorState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CANVAS_MIN_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            ZillitText(
                text = "Loading the picture…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@BoxWithConstraints
        }
        val scale = min(
            constraints.maxWidth.toFloat() / bitmap.width,
            constraints.maxHeight.toFloat() / bitmap.height,
        )
        val fitted = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
        val density = androidx.compose.ui.platform.LocalDensity.current

        Canvas(
            modifier = Modifier
                .size(
                    width = with(density) { fitted.width.toDp() },
                    height = with(density) { fitted.height.toDp() },
                )
                .penGestures(editor, scale, penWidthFor(bitmap, editor.widthIndex)),
        ) {
            drawImage(bitmap, dstSize = fitted)
            (editor.strokes + listOfNotNull(editor.current)).forEach { drawStroke(it, scale) }
        }
    }
}

/**
 * The pen: a drag is a stroke, a tap is a dot — the quickest way to mark
 * "this one". Positions are divided by [scale] into image pixels as they
 * arrive. Keyed on scale so a resized dialog re-arms with the right ratio.
 */
private fun Modifier.penGestures(editor: ImageEditorState, scale: Float, penWidth: Float): Modifier = this
    .pointerInput(scale, penWidth) {
        detectDragGestures(
            onDragStart = { at ->
                editor.current = PenStroke(listOf(at / scale), editor.color, penWidth)
            },
            onDrag = { change, _ ->
                change.consume()
                editor.current = editor.current?.let { it.copy(points = it.points + change.position / scale) }
            },
            onDragEnd = {
                editor.current?.let { editor.strokes = editor.strokes + it }
                editor.current = null
            },
            onDragCancel = { editor.current = null },
        )
    }
    .pointerInput(scale, penWidth) {
        detectTapGestures { at ->
            editor.strokes = editor.strokes + PenStroke(listOf(at / scale), editor.color, penWidth)
        }
    }

/** One stroke on the fitted canvas — a dot for a single point, a round-capped path otherwise. */
private fun DrawScope.drawStroke(stroke: PenStroke, scale: Float) {
    if (stroke.points.size == 1) {
        drawCircle(stroke.color, radius = stroke.width * scale / 2, center = stroke.points[0] * scale)
    } else {
        drawPath(
            path = stroke.toPath(scale),
            color = stroke.color,
            style = Stroke(width = stroke.width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The picture with the strokes burned in, at full size — what gets posted.
 * Drawn with the same paint the canvas showed, so what was seen is what
 * is sent.
 */
private fun composite(source: ImageBitmap, strokes: List<PenStroke>): ImageBitmap {
    val out = ImageBitmap(source.width, source.height)
    val canvas = androidx.compose.ui.graphics.Canvas(out)
    canvas.drawImage(source, Offset.Zero, Paint())
    val paint = Paint().apply {
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        isAntiAlias = true
    }
    strokes.forEach { stroke ->
        paint.color = stroke.color
        paint.strokeWidth = stroke.width
        if (stroke.points.size == 1) {
            paint.style = PaintingStyle.Fill
            canvas.drawCircle(stroke.points[0], stroke.width / 2, paint)
            paint.style = PaintingStyle.Stroke
        } else {
            canvas.drawPath(stroke.toPath(1f), paint)
        }
    }
    return out
}

/** The stroke as a path, scaled from image pixels by [scale]. */
private fun PenStroke.toPath(scale: Float): Path = Path().apply {
    points.forEachIndexed { index, point ->
        val at = point * scale
        if (index == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
    }
}

/**
 * Pen width in image pixels — relative to the picture, so a stroke reads
 * the same on a phone snap and a 24-megapixel still.
 */
private fun penWidthFor(bitmap: ImageBitmap, widthIndex: Int): Float =
    maxOf(bitmap.width, bitmap.height) / PEN_UNIT_DIVISOR * PEN_WIDTHS[widthIndex]

private val PEN_COLORS = listOf(
    Color(0xFFE53935), // red
    Color(0xFFFDD835), // yellow
    Color(0xFF43A047), // green
    Color(0xFF1E88E5), // blue
    Color.White,
    Color.Black,
)

/** Multipliers of the picture-relative unit — thin, regular, thick. */
private val PEN_WIDTHS = listOf(0.6f, 1.0f, 1.8f)
private const val PEN_UNIT_DIVISOR = 160f
private const val JPEG_QUALITY = 90
private const val IMAGE_REPLY_NAME = "image-reply.jpg"

private val EDITOR_WIDTH = 760.dp
private val EDITOR_MAX_HEIGHT = 760.dp
private val CANVAS_MIN_HEIGHT = 200.dp
private val SWATCH = 24.dp
private val SWATCH_RING = 1.dp
private val SWATCH_RING_SELECTED = 2.dp
private val WIDTH_DOT_BASE = 4.dp
