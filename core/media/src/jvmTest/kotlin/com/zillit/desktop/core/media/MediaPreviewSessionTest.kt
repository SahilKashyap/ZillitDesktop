package com.zillit.desktop.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What Send hands back: untouched items exactly as they came (bytes
 * included — a video must not be copied), edited pictures re-encoded in
 * the format their type asks for, with a name that says so.
 */
class MediaPreviewSessionTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    private fun pngBytes(): ByteArray {
        val image = ImageBitmap(16, 12)
        Canvas(image).drawRect(0f, 0f, 16f, 12f, Paint().apply { color = Color.White })
        return assertNotNull(encodeImage(image, ImageEncoding.Png, quality = 100))
    }

    private fun mark(edit: ImageEditState) {
        edit.pen.strokes = listOf(PenStroke(listOf(Offset(2f, 2f)), Color.Red, width = 2f))
    }

    @Test
    fun `untouched items pass through as they came`() {
        val video = PreviewItem("take.mp4", "video/mp4", ByteArray(32), thumbnailBytes = ByteArray(4))
        val photo = PreviewItem("door.jpg", "image/jpeg", pngBytes())
        val session = MediaPreviewSession(listOf(video, photo))

        val results = assertNotNull(session.results(measurer))
        assertEquals(2, results.size)
        assertSame(video.bytes, results[0].bytes)
        assertSame(video.thumbnailBytes, results[0].thumbnailBytes)
        assertSame(photo.bytes, results[1].bytes, "an unedited picture is not re-encoded")
    }

    @Test
    fun `an edited JPEG re-encodes as JPEG, an edited PNG stays PNG`() {
        val jpeg = PreviewItem("door.heic", "image/heic", pngBytes())
        val png = PreviewItem("plan.png", "image/png", pngBytes())
        val session = MediaPreviewSession(listOf(jpeg, png))
        listOf(jpeg, png).forEach { item ->
            val edit = session.editFor(item)
            edit.load(assertNotNull(decodeImageBitmap(item.bytes)))
            mark(edit)
        }

        val results = assertNotNull(session.results(measurer))
        assertEquals("image/jpeg", results[0].contentType)
        assertEquals("door.jpg", results[0].name, "the name follows the bytes' new format")
        assertEquals(0xFF, results[0].bytes[0].toInt() and 0xFF)
        assertEquals("image/png", results[1].contentType)
        assertEquals("plan.png", results[1].name)
        assertEquals(0x89, results[1].bytes[0].toInt() and 0xFF)
        assertTrue(results.all { decodeImageBitmap(it.bytes) != null })
    }

    @Test
    fun `remove drops the current item while more than one remains, never the last`() {
        val a = PreviewItem("a.pdf", "application/pdf", ByteArray(1))
        val b = PreviewItem("b.pdf", "application/pdf", ByteArray(1))
        val session = MediaPreviewSession(listOf(a, b))
        session.select(1)

        session.removeCurrent()
        assertEquals(listOf(a), session.items)
        assertEquals(0, session.index)

        session.removeCurrent()
        assertEquals(listOf(a), session.items, "the last item stays; Cancel is how it goes")
    }
}
