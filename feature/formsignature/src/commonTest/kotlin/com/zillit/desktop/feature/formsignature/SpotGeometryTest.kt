package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one conversion in the module: screen pixels (top-left origin) to and
 * from PDF points (bottom-left origin).
 *
 * A vertical-flip bug here puts every signature at the mirrored height of
 * the page — the classic failure of this exact feature, and invisible until
 * a real document comes back stamped in the wrong half.
 */
class SpotGeometryTest {

    /** A4 at 595.28×841.89pt, rendered 800px wide → 1131px tall. */
    private val page = PdfPageImage(
        page = 1,
        imageBytes = ByteArray(1),
        widthPx = 800,
        heightPx = 1131,
        widthPt = 595.28,
        heightPt = 841.89,
    )

    @Test
    fun `a tap near the top of the image is a spot near the top of the page`() {
        val spot = page.spotFromTap(
            xPx = 400f,
            yPx = 50f,
            kind = SignSpotKind.Signature,
            spotWidth = 160.0,
            spotHeight = 56.0,
        )

        // Top of the image = high y in PDF points. The spot's y is its
        // bottom edge, so top-of-page minus roughly the tap offset and the
        // box height.
        assertTrue(spot.y > page.heightPt * 0.8, "y=${spot.y} should sit near the page top")
        assertEquals(400 * (595.28 / 800), spot.x, absoluteTolerance = 0.5)
    }

    @Test
    fun `a tap at the bottom clamps inside the page`() {
        val spot = page.spotFromTap(
            xPx = 790f,
            yPx = 1130f,
            kind = SignSpotKind.Signature,
            spotWidth = 160.0,
            spotHeight = 56.0,
        )

        assertTrue(spot.y >= 0.0)
        assertTrue(spot.x + spot.width <= page.widthPt + TOLERANCE)
    }

    @Test
    fun `tap and rectangle are inverses`() {
        val spot = page.spotFromTap(
            xPx = 200f,
            yPx = 300f,
            kind = SignSpotKind.Initials,
            spotWidth = 120.0,
            spotHeight = 40.0,
        )

        val rect = page.pixelRect(spot)

        // The rectangle's top-left is where the tap was.
        assertEquals(200f, rect[0], absoluteTolerance = 1f)
        assertEquals(300f, rect[1], absoluteTolerance = 1f)
    }

    @Test
    fun `a server spot renders where the server put it`() {
        // Bottom-left corner of the page, 72pt in from each edge.
        val spot = SignSpot(SignSpotKind.Signature, page = 1, x = 72.0, y = 72.0, width = 160.0, height = 56.0)

        val rect = page.pixelRect(spot)
        val scale = 800 / 595.28

        assertEquals((72.0 * scale).toFloat(), rect[0], absoluteTolerance = 0.5f)
        // Near the bottom of the image: page height minus y minus box height.
        assertEquals(
            ((841.89 - 72.0 - 56.0) * scale).toFloat(),
            rect[1],
            absoluteTolerance = 0.5f,
        )
    }

    private companion object {
        const val TOLERANCE = 0.01
    }
}
