package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CallType
import com.zillit.desktop.feature.home.calendar.EventAudience
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
import kotlin.time.Instant

/**
 * Writing an event.
 *
 * Validation is the substance: a unit call saved at the wrong hour is worse
 * than one that would not save, so the parsers refuse anything ambiguous rather
 * than guessing. The rules are the web's `calendarV3` `validateEventForm` plus
 * its per-field ones, which is what the desktop form has to agree with.
 *
 * [now] is fixed. Two of these rules ask whether something has already
 * happened, and a test that reads the real clock passes until the afternoon.
 */
class EventFormTest {

    private val zone = TimeZone.UTC
    private val now = Instant.parse("2026-08-04T08:00:00Z")

    /** The simplest event that ought to save: a note to yourself. */
    private fun draft(
        title: String = "Unit call",
        date: String = "2026-08-04",
        start: String = "09:00",
        end: String = "17:30",
        allDay: Boolean = false,
        reminder: Int = 0,
        id: String? = null,
    ) = EventDraft(
        id = id,
        title = title,
        dateText = date,
        startText = start,
        endText = end,
        isAllDay = allDay,
        reminderMinutes = reminder,
        audience = EventAudience.Personal,
    )

    /** One circulated to people, which owes them a call type and an invitee. */
    private fun members(
        callType: CallType? = CallType.Video,
        invitees: Set<String> = setOf("u1"),
        external: List<String> = emptyList(),
        location: String = "",
    ) = draft().copy(
        audience = EventAudience.Members,
        callType = callType,
        inviteeIds = invitees,
        externalEmails = external,
        location = location,
    )

    // -- valid -------------------------------------------------------------

    @Test
    fun `a complete event resolves to its times`() {
        val checked = draft().validate(zone, now)

        assertTrue(checked.isValid)
        assertTrue(checked.times!!.let { it.endMillis > it.startMillis })
    }

    @Test
    fun `a complete members event resolves too`() {
        assertTrue(members().validate(zone, now).isValid)
    }

    @Test
    fun `an all-day event runs to one second before the next midnight`() {
        // Not midnight to midnight: an event ending on the next day's midnight
        // overlaps that day too, and draws a second time in every grid that
        // asks which events touch a date. The web sends 23:59:59.
        val checked = draft(start = "", end = "", allDay = true).validate(zone, now)

        assertTrue(checked.isValid)
        val length = checked.times!!.let { it.endMillis - it.startMillis }
        assertEquals(24L * 60 * 60 * 1000 - 1000, length)
    }

    @Test
    fun `an all-day event ignores whatever is in the time fields`() {
        // The boxes are hidden, but stale text must not leak into the payload.
        val checked = draft(start = "nonsense", end = "also nonsense", allDay = true)
            .validate(zone, now)

        assertTrue(checked.isValid)
    }

    // -- title -------------------------------------------------------------

    @Test
    fun `a nameless event is refused`() {
        assertTrue(EventFieldError.TitleBlank in draft(title = "  ").validate(zone, now))
    }

    @Test
    fun `a two-character title is refused`() {
        // The web's `{ min: 3 }`.
        val checked = draft(title = "AD").validate(zone, now)

        assertTrue(EventFieldError.TitleTooShort in checked)
        assertFalse(EventFieldError.TitleBlank in checked, "one complaint per field")
    }

    @Test
    fun `three characters is enough`() {
        assertTrue(draft(title = "Rec").validate(zone, now).isValid)
    }

    // -- date --------------------------------------------------------------

    @Test
    fun `an unreadable date is refused`() {
        listOf("", "tomorrow", "04-08-2026", "2026-13-01").forEach { text ->
            assertTrue(EventFieldError.DateInvalid in draft(date = text).validate(zone, now), text)
        }
    }

    @Test
    fun `a new event cannot be put in the past`() {
        assertTrue(EventFieldError.DateInPast in draft(date = "2026-08-03").validate(zone, now))
    }

    @Test
    fun `today is not the past`() {
        assertFalse(EventFieldError.DateInPast in draft(date = "2026-08-04").validate(zone, now))
    }

    @Test
    fun `an existing event in the past can still be edited`() {
        // The web disables past days in its picker, which stops one being
        // chosen — but applied to an edit it also stops last week's call sheet
        // having its typo fixed.
        val checked = draft(date = "2026-07-01", id = "e1").validate(zone, now)

        assertTrue(checked.isValid)
    }

    // -- times -------------------------------------------------------------

    @Test
    fun `an end before the start runs overnight rather than failing`() {
        // 22:00–04:00 is a night shoot, not a typo. The web rolls the end into
        // the next day; refusing it would make night work unbookable.
        val checked = draft(start = "22:00", end = "04:00").validate(zone, now)

        assertTrue(checked.isValid)
        assertEquals(6L * 60 * 60 * 1000, checked.times!!.let { it.endMillis - it.startMillis })
    }

    @Test
    fun `a zero-length event is refused`() {
        // Equal times roll to the next day, giving 24 hours — but the rule that
        // catches the mistake is the fifteen-minute floor, not the ordering.
        val checked = draft(start = "09:00", end = "09:00").validate(zone, now)

        assertTrue(checked.isValid, "equal times mean a full day, as on the web")
        assertEquals(24L * 60 * 60 * 1000, checked.times!!.let { it.endMillis - it.startMillis })
    }

    @Test
    fun `an event shorter than a quarter of an hour is refused`() {
        val checked = draft(start = "09:00", end = "09:10").validate(zone, now)

        assertTrue(EventFieldError.TooShort in checked)
        assertNull(checked.times)
    }

    @Test
    fun `a quarter of an hour exactly is allowed`() {
        assertTrue(draft(start = "09:00", end = "09:15").validate(zone, now).isValid)
    }

    @Test
    fun `an all-day event is never too short`() {
        assertFalse(
            EventFieldError.TooShort in draft(start = "", end = "", allDay = true).validate(zone, now),
        )
    }

    // -- reminder ----------------------------------------------------------

    @Test
    fun `a reminder that would have to fire in the past is refused`() {
        // Start 08:30 with now at 08:00 and "60 minutes before": the reminder
        // was due at 07:30 and will never arrive. Saving it looks like it
        // worked and then quietly does nothing.
        val checked = draft(start = "08:30", end = "09:30", reminder = 60).validate(zone, now)

        assertTrue(EventFieldError.ReminderPassed in checked)
    }

    @Test
    fun `a reminder with time left is fine`() {
        assertTrue(draft(start = "09:00", end = "10:00", reminder = 30).validate(zone, now).isValid)
    }

    @Test
    fun `no reminder is never late`() {
        assertTrue(draft(start = "08:10", end = "09:00", reminder = 0).validate(zone, now).isValid)
    }

    @Test
    fun `editing an old event does not re-litigate its reminder`() {
        val checked = draft(date = "2026-07-01", reminder = 60, id = "e1").validate(zone, now)

        assertTrue(checked.isValid)
    }

    // -- members events ----------------------------------------------------

    @Test
    fun `a members event must say how people are meeting`() {
        assertTrue(EventFieldError.CallTypeMissing in members(callType = null).validate(zone, now))
    }

    @Test
    fun `a members event must have somebody on it`() {
        val checked = members(invitees = emptySet()).validate(zone, now)

        assertTrue(EventFieldError.NoInvitees in checked)
    }

    @Test
    fun `an outside guest is somebody`() {
        val checked = members(invitees = emptySet(), external = listOf("guest@example.com"))
            .validate(zone, now)

        assertTrue(checked.isValid)
    }

    @Test
    fun `a personal event needs neither a call type nor guests`() {
        val checked = draft().validate(zone, now)

        assertFalse(EventFieldError.CallTypeMissing in checked)
        assertFalse(EventFieldError.NoInvitees in checked)
    }

    @Test
    fun `meeting in person and calling needs somewhere to meet`() {
        val checked = members(callType = CallType.InPersonAndCall).validate(zone, now)

        assertTrue(EventFieldError.LocationMissing in checked)
        assertTrue(members(callType = CallType.InPersonAndCall, location = "Studio 2")
            .validate(zone, now).isValid)
    }

    @Test
    fun `the other call types do not ask for a location`() {
        listOf(CallType.Audio, CallType.Video, CallType.InPerson).forEach { callType ->
            assertFalse(
                EventFieldError.LocationMissing in members(callType = callType).validate(zone, now),
                callType.name,
            )
        }
    }

    // -- reporting ---------------------------------------------------------

    @Test
    fun `every failing field is reported at once`() {
        // A form that surfaces one problem per submit makes the user submit
        // four times to learn about four.
        val checked = EventDraft(title = "", dateText = "no", startText = "no", endText = "no")
            .validate(zone, now)

        assertTrue(EventFieldError.TitleBlank in checked)
        assertTrue(EventFieldError.DateInvalid in checked)
        assertTrue(EventFieldError.StartTimeInvalid in checked)
        assertTrue(EventFieldError.EndTimeInvalid in checked)
        assertTrue(EventFieldError.CallTypeMissing in checked)
        assertTrue(EventFieldError.NoInvitees in checked)
    }

    @Test
    fun `an unparsed time does not also complain about the length`() {
        // Two complaints about one typo reads as two mistakes.
        val checked = draft(start = "nope").validate(zone, now)

        assertTrue(EventFieldError.StartTimeInvalid in checked)
        assertFalse(EventFieldError.TooShort in checked)
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
            audience = EventAudience.Members,
            callType = CallType.Video,
            inviteeIds = setOf("u1", "u2"),
            externalEmails = listOf("guest@example.com"),
        )

        val draft = event.toDraft(zone)

        assertEquals("e1", draft.id)
        assertTrue(draft.isEdit)
        assertEquals("Unit call", draft.title)
        assertEquals("Studio 2", draft.location)
        assertEquals(15, draft.reminderMinutes)
        // Reopening an event must not quietly uninvite everybody on it.
        assertEquals(setOf("u1", "u2"), draft.inviteeIds)
        assertEquals(listOf("guest@example.com"), draft.externalEmails)
        assertEquals(EventAudience.Members, draft.audience)
        assertEquals(CallType.Video, draft.callType)
        // Whatever it renders, it must read back cleanly.
        assertTrue(draft.validate(zone, now).isValid, "a round-tripped event should still be valid")
    }

    @Test
    fun `an all-day event reopens with its time fields empty`() {
        val event = CalendarEvent(
            id = "e1",
            title = "Travel day",
            startMillis = 1_785_920_400_000,
            endMillis = 1_785_920_400_000 + 86_400_000,
            isAllDay = true,
            audience = EventAudience.Personal,
        )

        val draft = event.toDraft(zone)

        assertTrue(draft.isAllDay)
        assertEquals("", draft.startText)
        assertTrue(draft.validate(zone, now).isValid)
    }

    @Test
    fun `a new draft is not an edit`() {
        assertFalse(EventDraft().isEdit)
        assertEquals("New Event", EventDraft().formTitle)
        assertEquals("Edit Event", EventDraft(id = "e1").formTitle)
    }
}
