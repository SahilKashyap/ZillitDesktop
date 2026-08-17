package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.mailTimeLabel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The list's time column: a clock today, a date this year, month-and-year
 * beyond — and never a blank-date epoch artefact.
 */
class MailTimeTest {

    private val zone = TimeZone.of("Asia/Kolkata")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
    ): Long = LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private val now = at(2026, 8, 4, hour = 21)

    @Test
    fun `today is a clock, zero-padded`() {
        assertEquals("09:05", mailTimeLabel(at(2026, 8, 4, 9, 5), now, zone))
        assertEquals("21:00", mailTimeLabel(at(2026, 8, 4, 21, 0), now, zone))
    }

    @Test
    fun `this year is day and month`() {
        assertEquals("28 Jul", mailTimeLabel(at(2026, 7, 28), now, zone))
        assertEquals("1 Jan", mailTimeLabel(at(2026, 1, 1), now, zone))
    }

    @Test
    fun `older mail is month and year`() {
        assertEquals("Dec 2025", mailTimeLabel(at(2025, 12, 31), now, zone))
        assertEquals("Mar 2019", mailTimeLabel(at(2019, 3, 2), now, zone))
    }

    @Test
    fun `a missing date shows nothing rather than 1970`() {
        assertEquals("", mailTimeLabel(0, now, zone))
    }
}
