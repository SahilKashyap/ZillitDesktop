package com.zillit.desktop.core.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntSize

/** The three tools Android's editor menu offers — `fragment_edit_image_main_menu.xml`: paint, crop, text. */
enum class EditTool { Draw, Crop, Text }

/**
 * One picture's edits between frames — the working bitmap, the pen, the
 * text tool, and the crop band being dragged.
 *
 * Android's editor bakes each tool's result into the main bitmap when it is
 * applied (`CropFragment.applyCropImage`, `PaintFragment.savePaintImage`),
 * and this follows the same shape: geometry — crop and rotate — bakes at
 * once, because strokes cannot be kept as vectors across a change of
 * coordinates; strokes and text stay as vectors, undoable, until a bake or
 * the send composites them. [reset] returns to the picture as picked.
 */
class ImageEditState(bitmap: ImageBitmap? = null) {
    /** The picture as it was picked — what [reset] returns to. */
    var original by mutableStateOf(bitmap)
        private set

    /** The picture with every baked edit; strokes and text draw over it. */
    var working by mutableStateOf(bitmap)
        private set

    val pen = PenState()
    val text = TextToolState()
    var cropBand by mutableStateOf<CropBand?>(null)

    /** Whether anything has been baked — the picture is no longer the one picked. */
    var baked by mutableStateOf(false)
        private set

    /** Whether sending needs a re-encode at all. */
    val isEdited: Boolean get() = baked || !pen.isEmpty || !text.isEmpty

    val workingSize: IntSize? get() = working?.let { IntSize(it.width, it.height) }

    /** The picture arrived (it may decode after the dialog opens); every tool starts clean. */
    fun load(bitmap: ImageBitmap) {
        original = bitmap
        reset()
    }

    /** Back to the picture as picked — the phones' "discard edits". */
    fun reset() {
        working = original
        pen.clear()
        text.clear()
        cropBand = null
        baked = false
    }

    /** A quarter turn clockwise; strokes and text bake first, as they must. */
    fun rotate(measurer: TextMeasurer) {
        working = rotateQuarterTurn(bake(measurer) ?: return)
        cropBand = null
        baked = true
    }

    /**
     * Cuts the picture down to the band; false when there is no band worth
     * cutting (see [cropSelection]) — the button stays put and nothing changes.
     */
    fun applyCrop(measurer: TextMeasurer): Boolean {
        val size = workingSize ?: return false
        val rect = cropBand?.let { cropSelection(it, size) } ?: return false
        working = cropBitmap(bake(measurer) ?: return false, rect)
        cropBand = null
        baked = true
        return true
    }

    /** The picture with everything on it, as it would send; null before it loads. */
    fun render(measurer: TextMeasurer): ImageBitmap? =
        working?.let { composite(it, pen.strokes, text.texts, measurer) }

    /** Composites the vectors into the working picture and clears them. */
    private fun bake(measurer: TextMeasurer): ImageBitmap? {
        val flat = render(measurer) ?: return null
        pen.clear()
        text.clear()
        working = flat
        return flat
    }
}
