package com.zillit.desktop.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's raster side, off-screen: strokes and text burn into a
 * bitmap of the right size, crop and rotate produce the right sizes, and
 * the result encodes to bytes that decode back. If Skia's bitmap canvas,
 * the text layout or the encoder were unavailable on the JVM, Send would
 * silently do nothing.
 */
class ImageEditsTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    private fun white(width: Int, height: Int): ImageBitmap {
        val image = ImageBitmap(width, height)
        Canvas(image).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.White })
        return image
    }

    @Test
    fun `a stroke and a line of text composite onto the picture at its own size`() {
        val source = white(WIDTH, HEIGHT)
        val out = composite(
            source,
            strokes = listOf(PenStroke(listOf(Offset(4f, 4f), Offset(60f, 40f)), Color.Red, width = 6f)),
            texts = listOf(PlacedText("SET", Color.Blue, sizePx = 20f, position = Offset(2f, 2f))),
            measurer = measurer,
        )
        assertEquals(WIDTH, out.width)
        assertEquals(HEIGHT, out.height)

        // Something red landed where the stroke went, and the picture is no
        // longer white where the text sits.
        val pixels = out.toPixelMap()
        assertNotEquals(Color.White, pixels[32, 22], "the stroke left no mark")
        val textArea = (2 until 20).flatMap { x -> (2 until 20).map { y -> pixels[x, y] } }
        assertTrue(textArea.any { it != Color.White }, "the text left no mark")
    }

    @Test
    fun `crop cuts the rectangle and rotate swaps the sides`() {
        val source = white(WIDTH, HEIGHT)
        val cropped = cropBitmap(source, IntRect(10, 5, 40, 25))
        assertEquals(30, cropped.width)
        assertEquals(20, cropped.height)

        val turned = rotateQuarterTurn(source)
        assertEquals(HEIGHT, turned.width)
        assertEquals(WIDTH, turned.height)
    }

    @Test
    fun `rotate keeps the picture inside the new bounds`() {
        // A picture with one red pixel at its top-left: after a clockwise
        // quarter turn it sits at the top-right, and nothing is transparent —
        // the translate-then-rotate would otherwise leave the picture off-canvas.
        val source = white(WIDTH, HEIGHT)
        Canvas(source).drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.Red })
        val turned = rotateQuarterTurn(source).toPixelMap()
        assertEquals(Color.Red, turned[HEIGHT - 1, 0])
        assertEquals(Color.White, turned[0, 0])
        assertEquals(Color.White, turned[HEIGHT - 1, WIDTH - 1])
    }

    @Test
    fun `an edited picture encodes to a JPEG that decodes to the same size`() {
        val edit = ImageEditState(white(WIDTH, HEIGHT))
        edit.pen.strokes = listOf(PenStroke(listOf(Offset(10f, 10f)), Color.Red, width = 4f))
        val rendered = assertNotNull(edit.render(measurer))

        val bytes = assertNotNull(encodeImageJpeg(rendered, quality = 90))
        // JPEG's SOI marker.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val decoded = assertNotNull(decodeImageBitmap(bytes))
        assertEquals(WIDTH, decoded.width)
        assertEquals(HEIGHT, decoded.height)
    }

    @Test
    fun `apply crop bakes and shrinks the working picture, reset restores it`() {
        val edit = ImageEditState(white(WIDTH, HEIGHT))
        edit.pen.strokes = listOf(PenStroke(listOf(Offset(10f, 10f)), Color.Red, width = 4f))
        edit.cropBand = CropBand(Offset(0f, 0f), Offset(32f, 24f))

        assertTrue(edit.applyCrop(measurer))
        assertEquals(32, edit.working?.width)
        assertEquals(24, edit.working?.height)
        assertTrue(edit.pen.isEmpty, "strokes bake into the crop")
        assertNull(edit.cropBand)
        assertTrue(edit.isEdited)

        edit.reset()
        assertEquals(WIDTH, edit.working?.width)
        assertTrue(!edit.isEdited)
    }

    @Test
    fun `a band too small to crop is refused and changes nothing`() {
        val edit = ImageEditState(white(WIDTH, HEIGHT))
        edit.cropBand = CropBand(Offset(1f, 1f), Offset(3f, 3f))
        assertTrue(!edit.applyCrop(measurer))
        assertEquals(WIDTH, edit.working?.width)
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}
