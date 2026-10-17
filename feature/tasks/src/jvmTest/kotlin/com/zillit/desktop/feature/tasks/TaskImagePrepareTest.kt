package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.domain.AttachmentKind
import com.zillit.desktop.feature.tasks.domain.AttachmentRefusal
import com.zillit.desktop.feature.tasks.domain.PrepareOutcome
import com.zillit.desktop.feature.tasks.domain.prepareAttachment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shrinking, against real images — the half of
 * [TaskAttachmentsTest] that needs a decoder.
 *
 * The rule is the web's: at most 2000 px on the longest side, as JPEG; a
 * small image (and a GIF up to 3 MB) goes exactly as it came.
 */
class TaskImagePrepareTest {

    /** A PNG of [width] × [height], with enough detail that it does not compress to nothing. */
    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                image.setRGB(x, y, (x * 37 + y * 17) and 0xFFFFFF)
            }
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    private fun prepare(name: String, mime: String, bytes: ByteArray) =
        prepareAttachment(name = name, mimeType = mime, bytes = bytes, key = "k")

    @Test
    fun `a big photo is shrunk to 2000px and sent as JPEG`() {
        val outcome = prepare("Set photo.png", "image/png", png(3000, 2000))

        val item = (outcome as PrepareOutcome.Ready).item
        assertEquals(AttachmentKind.Image, item.kind)
        assertEquals("image/jpeg", item.mimeType)
        // The name keeps its own words and loses the old extension.
        assertEquals("Set photo.jpg", item.name)
        assertEquals(2000, item.width)
        assertEquals(1333, item.height)
        assertTrue(item.sizeBytes < 4L * 1024 * 1024, "shrunk below the 4 MB limit")
    }

    @Test
    fun `a small image goes exactly as it came`() {
        val original = png(80, 60)

        val item = (prepare("thumb.png", "image/png", original) as PrepareOutcome.Ready).item

        assertEquals("image/png", item.mimeType)
        assertEquals("thumb.png", item.name)
        assertEquals(original.size.toLong(), item.sizeBytes)
        assertEquals(80, item.width)
        assertEquals(60, item.height)
    }

    /** A screenshot from the clipboard has the browser's own name; it is given one. */
    @Test
    fun `a pasted image is named after when it was pasted`() {
        val outcome = prepareAttachment(
            name = "image.png",
            mimeType = "image/png",
            bytes = png(40, 40),
            key = "k",
            pastedName = "Pasted image 17 Oct, 09:30",
        )

        assertEquals("Pasted image 17 Oct, 09:30.png", (outcome as PrepareOutcome.Ready).item.name)
    }

    @Test
    fun `a file that says it is an image but is not reads as unreadable`() {
        val outcome = prepare("fake.png", "image/png", ByteArray(512) { 3 })

        assertTrue((outcome as PrepareOutcome.Refused).why is AttachmentRefusal.Unreadable)
    }
}
