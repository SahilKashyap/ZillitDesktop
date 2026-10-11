package com.zillit.desktop.core.media

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A picture's thumbnail: shrunk to fit, never enlarged, flattened, and JPEG. */
class ImageThumbnailTest {

    private fun png(width: Int, height: Int, argb: Int = 0x00000000): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, argb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `a large picture shrinks to the long edge and keeps its shape`() {
        val thumb = assertNotNull(imageThumbnailJpeg(png(3000, 2000)))
        assertEquals(IMAGE_THUMBNAIL_EDGE, thumb.widthPx)
        assertEquals(480, thumb.heightPx)
        // JPEG's start-of-image marker.
        assertEquals(0xFF.toByte(), thumb.jpegBytes[0])
        assertEquals(0xD8.toByte(), thumb.jpegBytes[1])
    }

    @Test
    fun `a small picture is not enlarged`() {
        val thumb = assertNotNull(imageThumbnailJpeg(png(200, 300)))
        assertEquals(200, thumb.widthPx)
        assertEquals(300, thumb.heightPx)
    }

    @Test
    fun `transparency comes out white, not black`() {
        val thumb = assertNotNull(imageThumbnailJpeg(png(40, 40)))
        val pixel = ImageIO.read(thumb.jpegBytes.inputStream()).getRGB(20, 20)
        assertTrue((pixel and 0xFF) > 240, "clear pixels flattened onto white, got ${Integer.toHexString(pixel)}")
    }

    @Test
    fun `bytes that are not a picture give no thumbnail`() {
        assertNull(imageThumbnailJpeg(ByteArray(64) { it.toByte() }))
    }
}
