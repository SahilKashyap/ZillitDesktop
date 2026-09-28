package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.data.ReportTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The production report's times, which arrive as three different things.
 *
 * The service used to send `crew_call` as `"06:00 AM"` and now sends epoch
 * milliseconds with the report's own zone beside them; both spellings are
 * still on the wire, and a row that states no time must answer nothing rather
 * than a time nobody recorded.
 */
/** Monday 2026-05-04 07:30 UTC. */
private const val MORNING = 1_777_879_800_000L

class ReportTimeTest {

    @Test
    fun `an epoch is read in the report's own zone`() {
        assertEquals("07:30", ReportTime.epoch(MORNING, "UTC"))
        assertEquals("08:30", ReportTime.epoch(MORNING, "Europe/London"))
        assertEquals("13:00", ReportTime.epoch(MORNING, "Asia/Kolkata"))
        // A zone the platform does not know falls back to UTC rather than to
        // the reader's own, which would put a different time on every desk.
        assertEquals("07:30", ReportTime.epoch(MORNING, "Middle/Earth"))
        assertEquals("07:30", ReportTime.epoch(MORNING, null))
    }

    @Test
    fun `an epoch as a string is only an epoch when it could be one`() {
        assertEquals("07:30", ReportTime.hhmm(MORNING.toString(), "UTC"))
        // Too short to be milliseconds — an id or a code, not a time.
        assertNull(ReportTime.hhmm("0730", "UTC"))
    }

    @Test
    fun `a clock string reads in either notation`() {
        assertEquals("06:00", ReportTime.hhmm("06:00 AM", null))
        assertEquals("18:00", ReportTime.hhmm("6:00 PM", null))
        assertEquals("00:30", ReportTime.hhmm("12:30 AM", null))
        assertEquals("12:30", ReportTime.hhmm("12:30 PM", null))
        assertEquals("19:45", ReportTime.hhmm("19:45", null))
        assertEquals("07:30", ReportTime.hhmm("7 : 30", null))
    }

    /** No zone on an ISO stamp means an authored wall clock; never shift it. */
    @Test
    fun `an ISO stamp without a zone is read verbatim`() {
        assertEquals("07:30", ReportTime.hhmm("2026-05-04T07:30:00", "Asia/Kolkata"))
        assertEquals("07:30", ReportTime.hhmm("2026-05-04T07:30:00Z", "UTC"))
        assertEquals("13:00", ReportTime.hhmm("2026-05-04T07:30:00Z", "Asia/Kolkata"))
    }

    @Test
    fun `anything that is not a time answers nothing`() {
        listOf(null, "", "—", "--:--", "TBD", "n/a", "null", "2026-05-04", "26:00", "07:99").forEach { value ->
            assertNull(ReportTime.hhmm(value, "UTC"), "expected no time from '$value'")
        }
    }
}
