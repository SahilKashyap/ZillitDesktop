package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventFieldError
import com.zillit.desktop.feature.home.calendar.toDraft
import com.zillit.desktop.feature.home.calendar.toLocalTimeOrNull
import com.zillit.desktop.feature.home.calendar.validate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writing an event.
 *
 * Validation is the substance: a unit call saved at the wrong hour is worse
 * than one that would not save, so the parsers refuse anything ambiguous rather
 * than guessing.
 */
class EventFormTest {

    private val zone = TimeZone.UTC

    private fun draft(
        title: String = "Unit call",
        date: String = "2026-08-04",
        start: String = "09:00",
        end: String = "17:30",
        allDay: Boolean = false,
    ) = EventDraft(
        title = title,
        dateText = date,
        startText = start,
        endText = end,
        isAllDay = allDay,
    )

    // -- valid -------------------------------------------------------------

    @Test
    fun `a complete event resolves to its times`() {
        val checked = draft().validate(zone)

        assertTrue(checked.isValid)
        assertTrue(checked.times!!.let { it.endMillis > it.startMillis })
    }

    @Test
    fun `an all-day event runs midnight to midnight`() {
        // So the server's day-overlap check includes it.
        val checked = draft(start = "", end = "", allDay = true).validate(zone)

        assertTrue(checked.isValid)
        val length = checked.times!!.let { it.endMillis - it.startMillis }
        assertEquals(24L * 60 * 60 * 1000, length)
    }

    @Test
    fun `an all-day event ignores whatever is in the time fields`() {
        // The boxes are hidden, but stale text must not leak into the payload.
        val checked = draft(start = "nonsense", end = "also nonsense", allDay = true).validate(zone)

        assertTrue(checked.isValid)
    }

    // -- invalid -----------------------------------------------------------

    @Test
    fun `a nameless event is refused`() {
        assertTrue(EventFieldError.TitleBlank in draft(title = "  ").validate(zone))
    }

    @Test
    fun `an unreadable date is refused`() {
        listOf("", "tomorrow", "04-08-2026", "2026-13-01").forEach { text ->
            assertTrue(EventFieldError.DateInvalid in draft(date = text).validate(zone), text)
        }
    }

    @Test
    fun `an end before the start is refused`() {
        val checked = draft(start = "17:00", end = "09:00").validate(zone)

        assertTrue(EventFieldError.EndBeforeStart in checked)
        assertNull(checked.times)
    }

    @Test
    fun `a zero-length event is refused`() {
        assertTrue(EventFieldError.EndBeforeStart in draft(start = "09:00", end = "09:00").validate(zone))
    }

    @Test
    fun `every failing field is reported at once`() {
        // A form that surfaces one problem per submit makes the user submit
        // four times to learn about four.
        val checked = draft(title = "", date = "no", start = "no", end = "no").validate(zone)

        assertTrue(EventFieldError.TitleBlank in checked)
        assertTrue(EventFieldError.DateInvalid in checked)
        assertTrue(EventFieldError.StartTimeInvalid in checked)
        assertTrue(EventFieldError.EndTimeInvalid in checked)
    }

    @Test
    fun `an unparsed time does not also complain about ordering`() {
        // Two complaints about one typo reads as two mistakes.
        val checked = draft(start = "nope").validate(zone)

        assertTrue(EventFieldError.StartTimeInvalid in checked)
        assertFalse(EventFieldError.EndBeforeStart in checked)
    }

    // -- parsing -----------------------------------------------------------

    @Test
    fun `times accept a missing leading zero, because people type that`() {
        assertEquals(LocalTime(9, 0), "9:00".toLocalTimeOrNull())
        assertEquals(LocalTime(9, 0), "09:00".toLocalTimeOrNull())
        assertEquals(LocalTime(17, 30), "17:30".toLocalTimeOrNull())
    }

    @Test
    fun `anything ambiguous is refused rather than guessed`() {
        // Guessing wrong puts a unit call at the wrong hour.
        listOf("0900", "9am", "9", "", "25:00", "09:60", "9:0:0").forEach { text ->
            assertNull(text.toLocalTimeOrNull(), text)
        }
    }

    @Test
    fun `midnight and one minute to midnight both parse`() {
        assertEquals(LocalTime(0, 0), "00:00".toLocalTimeOrNull())
        assertEquals(LocalTime(23, 59), "23:59".toLocalTimeOrNull())
    }

    // -- editing -----------------------------------------------------------

    @Test
    fun `an existing event fills the form in the formats the inputs expect`() {
        val event = CalendarEvent(
            id = "e1",
            title = "Unit call",
            // 2026-08-04 09:00 UTC
            startMillis = 1_785_920_400_000,
            endMillis = 1_785_920_400_000 + 3_600_000,
            location = "Studio 2",
            reminderMinutes = 15,
        )

        val draft = event.toDraft(zone)

        assertEquals("e1", draft.id)
        assertTrue(draft.isEdit)
        assertEquals("Unit call", draft.title)
        assertEquals("Studio 2", draft.location)
        assertEquals(15, draft.reminderMinutes)
        // Whatever it renders, it must read back cleanly.
        assertTrue(draft.validate(zone).isValid, "a round-tripped event should still be valid")
    }

    @Test
    fun `an all-day event reopens with its time fields empty`() {
        val event = CalendarEvent(
            id = "e1",
            title = "Travel day",
            startMillis = 1_785_920_400_000,
            endMillis = 1_785_920_400_000 + 86_400_000,
            isAllDay = true,
        )

        val draft = event.toDraft(zone)

        assertTrue(draft.isAllDay)
        assertEquals("", draft.startText)
        assertTrue(draft.validate(zone).isValid)
    }

    @Test
    fun `a new draft is not an edit`() {
        assertFalse(EventDraft().isEdit)
        assertEquals("New event", EventDraft().formTitle)
        assertEquals("Edit event", EventDraft(id = "e1").formTitle)
    }
}
