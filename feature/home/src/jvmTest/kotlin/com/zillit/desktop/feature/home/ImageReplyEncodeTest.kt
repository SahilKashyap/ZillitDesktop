package com.zillit.desktop.feature.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import com.zillit.desktop.feature.home.ui.decodeImageBitmap
import com.zillit.desktop.feature.home.ui.encodeImageJpeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The image reply's last step, off-screen: draw onto a bitmap-backed canvas
 * the way the editor composites, encode it, and get a JPEG that decodes back
 * to the same size. If Skia's bitmap canvas or the encoder were unavailable
 * on the JVM the dialog's Post would silently do nothing.
 */
class ImageReplyEncodeTest {

    @Test
    fun `a stroked bitmap encodes to a JPEG that decodes to the same size`() {
        val image = ImageBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(image)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = Color.White })
        canvas.drawPath(
            Path().apply {
                moveTo(4f, 4f)
                lineTo(WIDTH - 4f, HEIGHT - 4f)
            },
            Paint().apply {
                color = Color.Red
                style = PaintingStyle.Stroke
                strokeWidth = 3f
            },
        )
        canvas.drawCircle(Offset(10f, 10f), 4f, Paint().apply { color = Color.Blue })

        val bytes = assertNotNull(encodeImageJpeg(image, quality = 90))

        // JPEG's SOI marker.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertTrue(bytes.size > 100)

        val decoded = assertNotNull(decodeImageBitmap(bytes))
        assertEquals(WIDTH, decoded.width)
        assertEquals(HEIGHT, decoded.height)
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}
