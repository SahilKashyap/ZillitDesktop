package com.zillit.desktop.feature.home

import com.zillit.desktop.core.config.EventCallLine
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CallType
import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.eventBody
import com.zillit.desktop.feature.home.calendar.newEventDraft
import com.zillit.desktop.feature.home.calendar.readEvent
import com.zillit.desktop.feature.home.calendar.toDraft
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The calendar event's `line`: read as optional with only "line_1" meaning
 * Line 1, sent only where the call type can have a meeting, preselected from
 * the event on edit and defaulted on create — and never promoting an old
 * event by omission.
 */
class CalendarCallLineTest {

    private val zone = TimeZone.of("Asia/Kolkata")
    private val times = EventTimes(1_791_174_600_000, 1_791_178_200_000)

    private fun row(extra: String = "") = Json.parseToJsonElement(
        """{"_id":"e1","title":"Sprint Review","start_datetime":1791174600000,"end_datetime":1791178200000,
           "call_type":"video","cnc_group_id":"sprint-review-9rhy"$extra}""",
    )

    private fun draft(callType: CallType?, line: EventCallLine, audience: EventAudience = EventAudience.Members) =
        EventDraft(title = "Sprint Review", callType = callType, line = line, audience = audience)

    @Test
    fun `an event saved before the field reads as Line 2`() {
        assertEquals(EventCallLine.Line2, readEvent(row())!!.line)
        assertEquals(EventCallLine.Line2, readEvent(row(""","line":null"""))!!.line)
        assertEquals(EventCallLine.Line2, readEvent(row(""","line":"LINE_1""""))!!.line)
        assertEquals(EventCallLine.Line1, readEvent(row(""","line":"line_1""""))!!.line)
    }

    @Test
    fun `the room id stays the meet id on a Line 1 event`() {
        val event = readEvent(row(""","line":"line_1""""))!!
        assertEquals("sprint-review-9rhy", event.cncGroupId)
    }

    @Test
    fun `editing an old event preselects Line 2 and an untouched save sends line_2`() {
        val old = CalendarEvent(
            id = "e1",
            title = "Standup",
            startMillis = times.startMillis,
            endMillis = times.endMillis,
            callType = CallType.Audio,
            cncGroupId = "room",
        )
        val draft = old.toDraft(zone)
        assertEquals(EventCallLine.Line2, draft.line)
        assertEquals("line_2", eventBody(draft, times, zone)["line"]?.jsonPrimitive?.content)
    }

    @Test
    fun `editing a Line 1 event keeps it on Line 1`() {
        val meet = readEvent(row(""","line":"line_1""""))!!
        assertEquals(EventCallLine.Line1, meet.toDraft(zone).line)
    }

    @Test
    fun `a new event's form opens on Line 1`() {
        val fresh = newEventDraft(LocalDate(2026, 10, 12), Instant.fromEpochMilliseconds(1_791_000_000_000), zone)
        assertEquals(EventCallLine.Line1, fresh.line)
    }

    @Test
    fun `the three meeting types send their line`() {
        listOf(CallType.Audio, CallType.Video, CallType.InPersonAndCall).forEach { type ->
            val body = eventBody(draft(type, EventCallLine.Line1), times, zone)
            assertEquals("line_1", body["line"]?.jsonPrimitive?.content, type.name)
            val second = eventBody(draft(type, EventCallLine.Line2), times, zone)
            assertEquals("line_2", second["line"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `no meeting, or a personal event, sends no line key at all`() {
        assertFalse(eventBody(draft(CallType.InPerson, EventCallLine.Line1), times, zone).containsKey("line"))
        assertFalse(eventBody(draft(null, EventCallLine.Line1), times, zone).containsKey("line"))
        val personal = draft(CallType.Video, EventCallLine.Line1, audience = EventAudience.Personal)
        assertFalse(eventBody(personal, times, zone).containsKey("line"))
        assertFalse(personal.carriesLine, "the picker must hide where the key is not sent")
    }

    @Test
    fun `the picker shows exactly where the key is sent`() {
        assertTrue(draft(CallType.InPersonAndCall, EventCallLine.Line2).carriesLine)
        assertFalse(draft(CallType.InPerson, EventCallLine.Line2).carriesLine)
    }
}
