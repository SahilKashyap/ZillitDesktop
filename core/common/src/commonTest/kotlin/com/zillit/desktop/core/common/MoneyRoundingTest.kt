package com.zillit.desktop.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

/** Penny ties round the way the web's `toLocaleString` does: half away from zero on the shortest decimal. */
class MoneyRoundingTest {

    @Test
    fun `a tie rounds up even when the double sits just below it`() {
        assertEquals("1.01", Money.group(1.005, 2))
        assertEquals("2.68", Money.group(2.675, 2))
        assertEquals("0.13", Money.group(0.125, 2))
    }

    @Test
    fun `everyday amounts are untouched`() {
        assertEquals("1,234.50", Money.group(1234.5, 2))
        assertEquals("0.00", Money.group(0.0, 2))
        assertEquals("1,234", Money.group(1234.4, 0))
        assertEquals("1,235", Money.group(1234.5, 0))
    }

    @Test
    fun `large figures survive the exponent form`() {
        assertEquals("12,345,678.91", Money.group(12_345_678.905, 2))
        assertEquals(1_234_567_890_123L, Money.scaledHalfAway(12_345_678_901.23, 2))
    }

    @Test
    fun `sign is kept and not rounded away`() {
        assertEquals(-101L, Money.scaledHalfAway(-1.005, 2))
        assertEquals("-£1.01", Money.format(-1.005, "GBP"))
    }
}
