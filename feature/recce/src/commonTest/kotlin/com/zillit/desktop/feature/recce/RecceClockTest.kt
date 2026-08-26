package com.zillit.desktop.feature.recce

import com.zillit.desktop.feature.recce.domain.RecceClock
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dates and clocks on a recce.
 *
 * Fixed to UTC so the assertions mean the same thing wherever this runs — the
 * production code takes the machine's zone, which is right for a person
 * reading a call time and wrong for a test that has to be true in Mumbai and
 * London at once.
 */
class RecceClockTest {

    private val utc = TimeZone.UTC

    /** Midnight of the day named, not of the day the machine thinks it is. */
    @Test
    fun `a day is its own midnight`() {
        val ms = RecceClock.dayMillis("2026-08-26", utc)

        assertEquals("2026-08-26", RecceClock.ymd(ms, utc))
        assertEquals("00:00", RecceClock.hm(ms, utc))
    }

    /** Single-digit months and days are written back padded. */
    @Test
    fun `short dates are read and padded`() {
        val ms = RecceClock.dayMillis("2026-1-5", utc)

        assertEquals("2026-01-05", RecceClock.ymd(ms, utc))
    }

    /** A day and a clock make an instant. */
    @Test
    fun `a clock lands on its day`() {
        val ms = RecceClock.clockMillis("2026-08-26", "06:30", utc)

        assertEquals("2026-08-26", RecceClock.ymd(ms, utc))
        assertEquals("06:30", RecceClock.hm(ms, utc))
    }

    /**
     * A clock typed before the day is kept, on the epoch.
     *
     * The web does the same for a draft filled in out of order, and the
     * fallback is what lets such a draft round-trip unchanged instead of
     * losing the time somebody typed.
     */
    @Test
    fun `a clock without a day falls back to the epoch`() {
        val ms = RecceClock.clockMillis("", "06:30", utc)

        assertEquals("1970-01-01", RecceClock.ymd(ms, utc))
        assertEquals("06:30", RecceClock.hm(ms, utc))
    }

    /** Nonsense is zero, not a wrong answer. */
    @Test
    fun `unparseable input is zero`() {
        assertEquals(0L, RecceClock.dayMillis("not a date", utc))
        assertEquals(0L, RecceClock.clockMillis("2026-08-26", "25:99", utc))
        assertEquals(0L, RecceClock.clockMillis("2026-08-26", "", utc))
    }

    /** Zero reads back as nothing rather than as 1970. */
    @Test
    fun `zero is not a date`() {
        assertEquals("", RecceClock.ymd(0, utc))
        assertEquals("", RecceClock.hm(0, utc))
    }

    /** The human labels carry the day, and an ordinal a person would say. */
    @Test
    fun `labels read like a person wrote them`() {
        val ms = RecceClock.dayMillis("2026-08-01", utc)
        val long = RecceClock.longDateLabel(ms, utc)

        assertTrue(long.contains("1st"), "an ordinal, not a bare number: was '$long'")
        assertTrue(RecceClock.dateLabel(ms, utc).isNotBlank())
    }
}
