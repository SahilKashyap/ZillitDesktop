package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.Thumbnails
import com.zillit.desktop.feature.documentdistribution.domain.isItsOwnThumbnail
import com.zillit.desktop.feature.documentdistribution.domain.needsGeneratedThumbnail
import com.zillit.desktop.feature.documentdistribution.domain.thumbnailFileName
import com.zillit.desktop.feature.documentdistribution.domain.thumbnailScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which files get a generated cover, and how big it is — the web's `thumbnail.js` rules. */
class ThumbnailRulesTest {

    @Test
    fun `only a pdf needs a cover made for it`() {
        assertTrue(needsGeneratedThumbnail("application/pdf", "a.pdf"))
        assertFalse(needsGeneratedThumbnail("image/jpeg", "a.jpg"))
        assertFalse(needsGeneratedThumbnail("application/msword", "a.doc"))
    }

    @Test
    fun `a pdf typed as generic binary is still a pdf by its name`() {
        assertTrue(needsGeneratedThumbnail("application/octet-stream", "Call Sheet.PDF"))
        assertTrue(needsGeneratedThumbnail(null, "scan.pdf"))
    }

    @Test
    fun `an image is its own cover, a pdf or a spreadsheet is not`() {
        assertTrue(isItsOwnThumbnail("image/png", "a.png"))
        assertTrue(isItsOwnThumbnail("application/octet-stream", "a.jpg"))
        assertFalse(isItsOwnThumbnail("application/pdf", "a.pdf"))
        // HEIC and TIFF cannot be drawn, so they would only be a broken card.
        assertFalse(isItsOwnThumbnail("image/heic", "a.heic"))
    }

    @Test
    fun `a normal page scales to the target width`() {
        // A4 in points.
        assertEquals(Thumbnails.WIDTH / 595.0, thumbnailScale(595.0, 842.0), 1e-9)
    }

    @Test
    fun `a very tall page is held to the height ceiling`() {
        val scale = thumbnailScale(600.0, 6000.0)
        assertEquals(Thumbnails.MAX_HEIGHT / 6000.0, scale, 1e-9)
        assertTrue(6000.0 * scale <= Thumbnails.MAX_HEIGHT + 1e-6)
    }

    @Test
    fun `a page with no size does not divide by zero`() {
        assertEquals(1.0, thumbnailScale(0.0, 0.0))
        assertEquals(1.0, thumbnailScale(-5.0, 100.0))
    }

    @Test
    fun `the cover is named after its document`() {
        assertEquals("Call Sheet_thumb.jpg", thumbnailFileName("Call Sheet.pdf"))
        assertEquals("a.b_thumb.jpg", thumbnailFileName("a.b.pdf"))
        assertEquals("noext_thumb.jpg", thumbnailFileName("noext"))
    }
}
