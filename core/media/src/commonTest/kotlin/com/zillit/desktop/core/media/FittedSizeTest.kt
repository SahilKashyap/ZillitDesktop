package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** The avatar decode's arithmetic: photos shrink to fit, keep their shape, and small ones are left alone. */
class FittedSizeTest {

    @Test
    fun `a phone photo shrinks to the cap on its longer side, keeping its shape`() {
        assertEquals(288 to 384, fittedSize(width = 3024, height = 4032, maxSide = 384))
        assertEquals(384 to 216, fittedSize(width = 1920, height = 1080, maxSide = 384))
    }

    @Test
    fun `a picture that already fits is not touched`() {
        assertEquals(200 to 120, fittedSize(200, 120, maxSide = 384))
        assertEquals(384 to 384, fittedSize(384, 384, maxSide = 384))
    }

    @Test
    fun `a sliver never collapses to nothing`() {
        assertEquals(384 to 1, fittedSize(10_000, 3, maxSide = 384))
    }
}
