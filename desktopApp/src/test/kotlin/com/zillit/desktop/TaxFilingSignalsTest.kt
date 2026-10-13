package com.zillit.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** The screen signal HMRC is told: one screen, the real scale, the web's defaults. */
class TaxFilingSignalsTest {

    @Test
    fun `a fractional scale is sent as it is`() {
        assertEquals("width=1536&height=864&scaling-factor=1.25&colour-depth=24", screenSignal(1536, 864, 1.25, 24))
        assertEquals("width=1280&height=720&scaling-factor=1.5&colour-depth=30", screenSignal(1280, 720, 1.5, 30))
    }

    @Test
    fun `a whole scale drops its point and an unknown depth reads 24`() {
        assertEquals("width=1512&height=982&scaling-factor=2&colour-depth=24", screenSignal(1512, 982, 2.0, -1))
        assertEquals("width=800&height=600&scaling-factor=1&colour-depth=24", screenSignal(800, 600, 0.0, 0))
    }
}
