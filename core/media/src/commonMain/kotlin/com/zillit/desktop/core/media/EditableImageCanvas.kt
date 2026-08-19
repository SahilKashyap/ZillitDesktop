package com.zillit.desktop.core.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText

/**
 * The picture, fitted to the space it is given, with the active tool over
 * it — the one canvas behind the image reply and the picked-media editor.
 *
 * Pointer positions arrive in fitted pixels and every tool divides them
 * back into image pixels before it keeps them; drawing does the reverse
 * with a single `scale` transform, so the same routines that draw the
 * fitted picture draw the full-size composite. [tool] null shows the edits
 * without taking input — the preview mode.
 */
@Composable
fun EditableImageCanvas(
    edit: ImageEditState,
    tool: EditTool?,
    modifier: Modifier = Modifier,
    measurer: TextMeasurer = rememberTextMeasurer(),
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CANVAS_MIN_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = edit.working
        if (bitmap == null) {
            ZillitText(
                text = "Loading the picture…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@BoxWithConstraints
        }
        val imageSize = IntSize(bitmap.width, bitmap.height)
        val scale = fitScale(imageSize, IntSize(constraints.maxWidth, constraints.maxHeight))
        val fitted = fittedSize(imageSize, scale)
        val density = LocalDensity.current

        Canvas(
            modifier = Modifier
                .size(
                    width = with(density) { fitted.width.toDp() },
                    height = with(density) { fitted.height.toDp() },
                )
                .toolGestures(edit, tool, scale, measurer),
        ) {
            drawImage(bitmap, dstSize = fitted)
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                drawStrokes(edit.pen.visible)
                drawPlacedTexts(edit.text.texts, measurer)
            }
            edit.cropBand?.let { drawCropBand(it, scale, imageSize) }
        }
    }
}

/** The active tool's input; none in preview. */
private fun Modifier.toolGestures(
    edit: ImageEditState,
    tool: EditTool?,
    scale: Float,
    measurer: TextMeasurer,
): Modifier = when (tool) {
    EditTool.Draw -> {
        val bitmap = edit.working
        if (bitmap == null) this else penGestures(edit.pen, scale, penWidthFor(bitmap, edit.pen.widthIndex))
    }
    EditTool.Crop -> cropGestures(edit, scale)
    EditTool.Text -> textDragGestures(edit.text, scale, bounds = { measurer.boundsOf(it) })
    null -> this
}

/**
 * The rubber band: press to start a corner, drag the opposite one out. No
 * handles — a fresh drag replaces the band, which on a desktop is quicker
 * than nudging eight grips (Android's `CropImageView` has the grips; its
 * ratio buttons are skipped too).
 */
private fun Modifier.cropGestures(edit: ImageEditState, scale: Float): Modifier =
    pointerInput(edit, scale) {
        detectDragGestures(
            onDragStart = { at -> edit.cropBand = CropBand(at / scale, at / scale) },
            onDrag = { change, _ ->
                change.consume()
                edit.cropBand = edit.cropBand?.copy(end = change.position / scale)
            },
        )
    }

/**
 * The band over the fitted picture: the outside dimmed, the cut edged in
 * white over black so it reads on any photo. Drawn in fitted pixels so the
 * edge stays hairline whatever the picture's size.
 */
private fun DrawScope.drawCropBand(band: CropBand, scale: Float, image: IntSize) {
    val rect = cropSelection(band, image)?.let {
        Rect(
            Offset(it.left * scale, it.top * scale),
            Size(it.width * scale, it.height * scale),
        )
    } ?: return
    val scrim = Color.Black.copy(alpha = CROP_SCRIM_ALPHA)
    drawRect(scrim, Offset.Zero, Size(size.width, rect.top))
    drawRect(scrim, Offset(0f, rect.bottom), Size(size.width, size.height - rect.bottom))
    drawRect(scrim, Offset(0f, rect.top), Size(rect.left, rect.height))
    drawRect(scrim, Offset(rect.right, rect.top), Size(size.width - rect.right, rect.height))
    drawRect(Color.Black, rect.topLeft, rect.size, style = Stroke(CROP_EDGE_OUTER.toPx()))
    drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(CROP_EDGE_INNER.toPx()))
}

private val CANVAS_MIN_HEIGHT = 200.dp
private val CROP_EDGE_OUTER = 3.dp
private val CROP_EDGE_INNER = 1.5.dp
private const val CROP_SCRIM_ALPHA = 0.45f
