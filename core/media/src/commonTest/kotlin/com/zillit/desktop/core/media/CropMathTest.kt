package com.zillit.desktop.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The crop band's arithmetic, off-screen: whichever way the drag went, the
 * cut is the rectangle between the corners, inside the picture, and never
 * a sliver. Wrong here and Apply cuts something other than what the band
 * showed — or throws on a negative size.
 */
class CropMathTest {

    private val image = IntSize(400, 300)

    @Test
    fun `a drag down-and-right is the rectangle between its corners`() {
        val rect = cropSelection(CropBand(Offset(10f, 20f), Offset(110f, 220f)), image)
        assertEquals(IntRect(10, 20, 110, 220), rect)
    }

    @Test
    fun `a drag up-and-left gives the same rectangle`() {
        // The hand does not always start top-left; the cut must not care.
        val rect = cropSelection(CropBand(Offset(110f, 220f), Offset(10f, 20f)), image)
        assertEquals(IntRect(10, 20, 110, 220), rect)
    }

    @Test
    fun `a band dragged past the edge is clamped to the picture`() {
        val rect = cropSelection(CropBand(Offset(-50f, -50f), Offset(900f, 900f)), image)
        assertEquals(IntRect(0, 0, 400, 300), rect)
    }

    @Test
    fun `fractions round to whole pixels`() {
        val rect = cropSelection(CropBand(Offset(10.4f, 20.6f), Offset(110.5f, 220.2f)), image)
        assertEquals(IntRect(10, 21, 111, 220), rect)
    }

    @Test
    fun `a slipped click is not a crop`() {
        assertNull(cropSelection(CropBand(Offset(10f, 10f), Offset(12f, 200f)), image), "too narrow")
        assertNull(cropSelection(CropBand(Offset(10f, 10f), Offset(200f, 14f)), image), "too short")
        assertNull(cropSelection(CropBand(Offset(10f, 10f), Offset(10f, 10f)), image), "a point")
    }

    @Test
    fun `a band entirely outside the picture is nothing`() {
        assertNull(cropSelection(CropBand(Offset(500f, 500f), Offset(600f, 600f)), image))
    }

    @Test
    fun `fit scale is the tighter of the two ratios, and fitted size follows it`() {
        // A landscape picture in a square box is limited by its width.
        assertEquals(0.5f, fitScale(image, IntSize(200, 200)))
        assertEquals(IntSize(200, 150), fittedSize(image, 0.5f))
        // A tall box: limited by height.
        assertEquals(1f, fitScale(image, IntSize(1000, 300)))
    }

    @Test
    fun `odd quarter turns swap the sides`() {
        assertEquals(IntSize(300, 400), rotatedSize(image, 1))
        assertEquals(image, rotatedSize(image, 2))
        assertEquals(IntSize(300, 400), rotatedSize(image, 3))
    }
}
