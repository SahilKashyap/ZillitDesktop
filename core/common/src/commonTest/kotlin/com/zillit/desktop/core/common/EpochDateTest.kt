package com.zillit.desktop.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stamp readers. Rendered in the machine's own zone, so the assertions
 * are about shape rather than a fixed clock time.
 */
class EpochDateTest {

    // 2026-08-04T16:35:09Z
    private val stamp = 1_785_947_709_000L

    @Test
    fun `the seconds form is the date-time form plus seconds`() {
        val short = EpochDate.dateTime(stamp)
        val long = EpochDate.dateTimeSeconds(stamp)

        assertTrue(long.startsWith(short.substringBefore(" | ")), long)
        assertTrue(Regex("""\d{1,2}:\d{2}:\d{2} [AP]M$""").containsMatchIn(long), long)
        // Same minute, one more field.
        assertEquals(short.count { it == ':' } + 1, long.count { it == ':' })
    }

    @Test
    fun `a missing or impossible stamp is blank, not an epoch date`() {
        assertEquals("", EpochDate.dateTimeSeconds(null))
        assertEquals("", EpochDate.dateTimeSeconds(0))
        assertEquals("", EpochDate.dateTimeSeconds(-1))
    }
}
