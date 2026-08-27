package com.zillit.desktop.feature.pagedistribution

import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stamps under every distributed document, in the web's format.
 *
 * A twelve-hour clock has two hours that break naive arithmetic — midnight
 * and noon both reduce to 0 — and a revision stamped "00:00 AM" or a noon
 * one reading "AM" is how a crew ends up shooting yesterday's pages.
 */
class DistributionDatesTest {

    private val utc = TimeZone.UTC

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
    ): Long = LocalDateTime(year, month, day, hour, minute).toInstant(utc).toEpochMilliseconds()

    @Test
    fun `midnight reads twelve AM, not zero`() {
        assertEquals("Mar 05, 2026 at 12:00 AM", DistributionDates.dateTime(at(2026, 3, 5), utc))
    }

    @Test
    fun `noon reads twelve PM`() {
        assertEquals(
            "Mar 05, 2026 at 12:00 PM",
            DistributionDates.dateTime(at(2026, 3, 5, hour = 12), utc),
        )
    }

    @Test
    fun `one minute before noon is still morning`() {
        assertEquals(
            "Mar 05, 2026 at 11:59 AM",
            DistributionDates.dateTime(at(2026, 3, 5, 11, 59), utc),
        )
    }

    @Test
    fun `the last minute of the day is eleven fifty-nine PM`() {
        assertEquals(
            "Mar 05, 2026 at 11:59 PM",
            DistributionDates.dateTime(at(2026, 3, 5, 23, 59), utc),
        )
    }

    @Test
    fun `afternoon hours wrap`() {
        assertEquals(
            "Mar 05, 2026 at 01:05 PM",
            DistributionDates.dateTime(at(2026, 3, 5, 13, 5), utc),
        )
    }

    @Test
    fun `hours and minutes are both padded`() {
        assertEquals(
            "Mar 05, 2026 at 09:07 AM",
            DistributionDates.dateTime(at(2026, 3, 5, 9, 7), utc),
        )
    }

    @Test
    fun `the day is padded and the month abbreviated to three letters`() {
        assertEquals("Jan 01, 2026", DistributionDates.date(at(2026, 1, 1), utc))
        assertEquals("Sep 30, 2026", DistributionDates.date(at(2026, 9, 30), utc))
        assertEquals("Dec 25, 2026", DistributionDates.date(at(2026, 12, 25), utc))
    }

    /**
     * The two helpers disagree deliberately on "no date": the full stamp
     * shows a dash because it labels a row, while the bare date returns
     * blank because it is concatenated into names and headings.
     */
    @Test
    fun `an absent date shows a dash in the stamp and nothing on its own`() {
        assertEquals("—", DistributionDates.dateTime(0, utc))
        assertEquals("", DistributionDates.date(0, utc))
    }

    @Test
    fun `a negative epoch is absent too, not nineteen sixty-nine`() {
        assertEquals("—", DistributionDates.dateTime(-1, utc))
        assertEquals("", DistributionDates.date(-86_400_000, utc))
    }

    /** One millisecond past the epoch is a real date, not an absent one. */
    @Test
    fun `the first millisecond counts as present`() {
        assertEquals("Jan 01, 1970", DistributionDates.date(1, utc))
    }

    @Test
    fun `the stamp is the date plus the clock, not a separate format`() {
        val ms = at(2026, 7, 4, 16, 30)

        assertEquals(
            "${DistributionDates.date(ms, utc)} at 04:30 PM",
            DistributionDates.dateTime(ms, utc),
        )
    }
}
