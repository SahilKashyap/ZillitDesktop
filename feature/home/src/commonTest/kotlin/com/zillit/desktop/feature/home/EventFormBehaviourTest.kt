package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.GuestAddition
import com.zillit.desktop.feature.home.calendar.addingGuest
import com.zillit.desktop.feature.home.calendar.Recurrence
import com.zillit.desktop.feature.home.calendar.RecurrenceFrequency
import com.zillit.desktop.feature.home.calendar.endDateText
import com.zillit.desktop.feature.home.calendar.inTimezone
import com.zillit.desktop.feature.home.calendar.isOvernight
import com.zillit.desktop.feature.home.calendar.looksLikeGuestEmail
import com.zillit.desktop.feature.home.calendar.newEventDraft
import com.zillit.desktop.feature.home.calendar.withDate
import com.zillit.desktop.feature.home.calendar.withStartTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the form does as it is filled in.
 *
 * These are the web's small conveniences — the ones that decide whether the
 * commonest event takes two fields or six. Each is a pure function on the
 * draft, so the dialog stays a rendering of state and these stay testable.
 */
class EventFormBehaviourTest {

    private val zone = TimeZone.UTC

    // -- opening -----------------------------------------------------------

    @Test
    fun `a new event starts at the next quarter hour and runs an hour`() {
        val draft = newEventDraft(
            date = LocalDate(2026, 8, 4),
            now = Instant.parse("2026-08-04T10:31:00Z"),
            zone = zone,
        )

        assertEquals("2026-08-04", draft.dateText)
        assertEquals("10:45", draft.startText)
        assertEquals("11:45", draft.endText)
    }

    @Test
    fun `a time already on the quarter is left where it is`() {
        val draft = newEventDraft(
            date = LocalDate(2026, 8, 4),
            now = Instant.parse("2026-08-04T10:30:20Z"),
            zone = zone,
        )

        assertEquals("10:30", draft.startText)
    }

    @Test
    fun `late in the evening the suggested times wrap rather than overflow`() {
        val draft = newEventDraft(
            date = LocalDate(2026, 8, 4),
            now = Instant.parse("2026-08-04T23:50:00Z"),
            zone = zone,
        )

        assertEquals("00:00", draft.startText)
        assertEquals("01:00", draft.endText)
    }

    // -- typing ------------------------------------------------------------

    @Test
    fun `setting a start time drags the end an hour after it`() {
        val moved = EventDraft(startText = "09:00", endText = "10:00").withStartTime("14:30")

        assertEquals("14:30", moved.startText)
        assertEquals("15:30", moved.endText)
    }

    @Test
    fun `a half-typed start time leaves the end alone`() {
        // Rewriting the end while the start is still being typed would move it
        // once per keystroke.
        val moved = EventDraft(startText = "09:00", endText = "17:30").withStartTime("1")

        assertEquals("1", moved.startText)
        assertEquals("17:30", moved.endText)
    }

    @Test
    fun `an hour after half eleven at night is half past midnight`() {
        assertEquals("00:30", EventDraft().withStartTime("23:30").endText)
    }

    // -- moving the event --------------------------------------------------

    @Test
    fun `moving a repeating event moves where the repeat stops`() {
        val weekly = EventDraft(
            dateText = "2026-08-04",
            recurrence = Recurrence(RecurrenceFrequency.Weekly, endDateText = "2026-08-11"),
        )

        val moved = weekly.withDate("2026-09-01")

        assertEquals("2026-09-01", moved.dateText)
        assertEquals("2026-09-08", moved.recurrence.endDateText)
    }

    @Test
    fun `moving a one-off leaves its blank repeat alone`() {
        val moved = EventDraft(dateText = "2026-08-04").withDate("2026-09-01")

        assertEquals("2026-09-01", moved.dateText)
        assertEquals("", moved.recurrence.endDateText)
    }

    @Test
    fun `a half-typed date does not disturb the repeat`() {
        val weekly = EventDraft(
            dateText = "2026-08-04",
            recurrence = Recurrence(RecurrenceFrequency.Weekly, endDateText = "2026-08-11"),
        )

        assertEquals("2026-08-11", weekly.withDate("2026-09").recurrence.endDateText)
    }

    // -- overnight ---------------------------------------------------------

    @Test
    fun `an end at or before the start means the next day`() {
        assertTrue(EventDraft(startText = "22:00", endText = "04:00").isOvernight)
        assertTrue(EventDraft(startText = "09:00", endText = "09:00").isOvernight)
        assertFalse(EventDraft(startText = "09:00", endText = "17:30").isOvernight)
    }

    @Test
    fun `an all-day event never runs overnight`() {
        assertFalse(EventDraft(startText = "22:00", endText = "04:00", isAllDay = true).isOvernight)
    }

    @Test
    fun `the end date shown is the next day for a night shoot`() {
        val night = EventDraft(dateText = "2026-08-04", startText = "22:00", endText = "04:00")
        val day = EventDraft(dateText = "2026-08-04", startText = "09:00", endText = "17:30")

        assertEquals("2026-08-05", night.endDateText)
        assertEquals("2026-08-04", day.endDateText)
    }

    // -- timezone ----------------------------------------------------------

    @Test
    fun `changing zone keeps the moment and moves the clock`() {
        // A 09:00 call in London is 01:00 in Los Angeles. Keeping the digits
        // instead would move the call eight hours with nobody noticing.
        val london = EventDraft(
            dateText = "2026-08-04",
            startText = "09:00",
            endText = "17:00",
            timezoneId = "Europe/London",
        )

        val la = london.inTimezone("America/Los_Angeles", zone)

        assertEquals("America/Los_Angeles", la.timezoneId)
        assertEquals("2026-08-04", la.dateText)
        assertEquals("01:00", la.startText)
        assertEquals("09:00", la.endText)
    }

    @Test
    fun `changing zone can move the event onto the previous day`() {
        val london = EventDraft(
            dateText = "2026-08-04",
            startText = "01:00",
            endText = "02:00",
            timezoneId = "Europe/London",
        )

        assertEquals("2026-08-03", london.inTimezone("America/Los_Angeles", zone).dateText)
    }

    @Test
    fun `picking the same zone changes nothing`() {
        val draft = EventDraft(
            dateText = "2026-08-04",
            startText = "09:00",
            endText = "17:00",
            timezoneId = "Europe/London",
        )

        assertEquals(draft, draft.inTimezone("Europe/London", zone))
    }

    @Test
    fun `an all-day event only moves its date`() {
        val draft = EventDraft(
            dateText = "2026-08-04",
            isAllDay = true,
            timezoneId = "Europe/London",
        )

        val moved = draft.inTimezone("America/Los_Angeles", zone)

        assertEquals("2026-08-03", moved.dateText)
        assertEquals("", moved.startText)
    }

    // -- outside guests ----------------------------------------------------

    @Test
    fun `an address needs an at, a domain and a dot`() {
        listOf("crew@example.com", "a.b+tag@sub.example.co.uk").forEach {
            assertTrue(it.looksLikeGuestEmail(), it)
        }
    }

    @Test
    fun `anything else is refused before it reaches the server`() {
        listOf("", "crew", "crew@", "@example.com", "crew@example", "a b@example.com", "a@@b.com")
            .forEach { assertFalse(it.looksLikeGuestEmail(), it) }
    }

    @Test
    fun `a good address joins the guest list`() {
        val added = EventDraft().addingGuest("  guest@example.com  ")

        assertEquals(
            GuestAddition.Added(EventDraft(externalEmails = listOf("guest@example.com"))),
            added,
        )
    }

    @Test
    fun `a malformed address is refused with a reason`() {
        val refused = EventDraft().addingGuest("not-an-address")

        assertEquals(GuestAddition.Refused("That is not an email address."), refused)
    }

    @Test
    fun `the same guest is not added twice, whatever the casing`() {
        val draft = EventDraft(externalEmails = listOf("guest@example.com"))

        assertEquals(
            GuestAddition.Refused("That guest is already on the list."),
            draft.addingGuest("GUEST@example.com"),
        )
    }

    @Test
    fun `an empty box is not an error`() {
        // Pressing Add with nothing typed is a slip, not a mistake worth
        // colouring the field red for.
        assertEquals(GuestAddition.Empty, EventDraft().addingGuest("   "))
    }
}
