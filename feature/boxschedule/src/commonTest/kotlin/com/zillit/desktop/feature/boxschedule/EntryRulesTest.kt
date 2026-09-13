package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.GuestEmail
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.PERSONAL_NOTE_TYPE
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.ui.AudiencePicker
import com.zillit.desktop.feature.boxschedule.ui.EntryField
import com.zillit.desktop.feature.boxschedule.ui.EntryForm
import com.zillit.desktop.feature.boxschedule.ui.EntryRules
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `CreateEventModal`'s validation and payload rules, and the Select Invitees picker's. */
class EntryRulesTest {

    private val zone = TimeZone.of("Asia/Kolkata")
    private val today = LocalDate(2026, 9, 13)
    private val users = DiaryAudience(AudienceMode.Users, userIds = listOf("u1"))

    private fun event(
        title: String = "Camera test",
        date: LocalDate? = today,
        start: LocalTime? = LocalTime(9, 0),
        end: LocalTime? = LocalTime(10, 0),
        callType: String = "video",
        audience: DiaryAudience = users,
    ) = EntryForm(
        kind = DiaryKind.Event, title = title, startDate = date, endDate = date, startTime = start, endTime = end,
        callType = callType, audience = audience, timezone = "Asia/Kolkata",
    )

    @Test
    fun `an event is checked in the web's order and the first problem is the toast`() {
        val empty = EntryRules.check(
            event(title = "", callType = "", audience = DiaryAudience()),
            today,
            NoteType.DEFAULTS,
        )
        assertEquals("Title is required", empty.message)
        assertEquals(setOf(EntryField.Title, EntryField.CallType, EntryField.Audience), empty.errors.keys)

        assertEquals(
            "Title must be at least 3 characters",
            EntryRules.check(event(title = "Hi"), today, NoteType.DEFAULTS).message,
        )
        assertEquals(
            "Events can't be created for a past date",
            EntryRules.check(event(date = LocalDate(2026, 9, 12)), today, NoteType.DEFAULTS).message,
        )
        assertTrue(EntryRules.check(event(), today, NoteType.DEFAULTS).passes)
    }

    @Test
    fun `duration, repeat end and meet-in-person rules`() {
        val short = EntryRules.check(event(start = LocalTime(9, 0), end = LocalTime(9, 10)), today, NoteType.DEFAULTS)
        assertEquals("Event must be at least 15 minutes long", short.message)
        assertTrue(short.errors.isEmpty(), "a semantic refusal flags no field")

        val overnight = EntryRules.check(
            event(start = LocalTime(23, 30), end = LocalTime(0, 30)),
            today,
            NoteType.DEFAULTS,
        )
        assertTrue(overnight.passes, "an end before the start crosses midnight")

        val repeating = EntryRules.check(event().copy(repeat = "weekly"), today, NoteType.DEFAULTS)
        assertEquals("Repeat end date is required for recurring events", repeating.message)
        assertTrue(EntryRules.check(
            event().copy(repeat = "weekly", scope = RecurrenceScope.Single),
            today,
            NoteType.DEFAULTS,
        ).passes)

        val inPerson = EntryRules.check(event(callType = "meet_in_person_call"), today, NoteType.DEFAULTS)
        assertEquals("Location is required for Meet in Person & Call", inPerson.errors[EntryField.Location])
    }

    @Test
    fun `external guests stand in for an audience, but a half-chosen audience does not pass`() {
        assertTrue(EntryRules.check(
            event(audience = DiaryAudience()).copy(guests = listOf("a@b.co")),
            today,
            NoteType.DEFAULTS,
        ).passes)
        val noUsers = EntryRules.check(
            event(audience = DiaryAudience(AudienceMode.Users)).copy(guests = listOf("a@b.co")),
            today,
            NoteType.DEFAULTS,
        )
        assertEquals("Pick at least one user", noUsers.message)
    }

    @Test
    fun `a personal note needs no audience, a general one does`() {
        val note = EntryForm(kind = DiaryKind.Note, noteTitle = "Wrap", noteDate = today)
        assertEquals(
            "Please choose who to distribute this note to",
            EntryRules.check(note, today, NoteType.DEFAULTS).message,
        )
        assertTrue(EntryRules.check(note.copy(noteType = PERSONAL_NOTE_TYPE), today, NoteType.DEFAULTS).passes)
        assertEquals(
            "Please enter a title",
            EntryRules.check(note.copy(noteTitle = " "), today, NoteType.DEFAULTS).message,
        )
    }

    @Test
    fun `the event draft is epoch milliseconds, and a full day ends on its last millisecond`() {
        val day = DiaryCalendar.startOf(today, zone)
        val full = EntryRules.draft(event().copy(fullDay = true), zone, NoteType.DEFAULTS, createInCalendar = true) {
            "id"
        }
        assertEquals(day, full.startDateTime)
        assertEquals(day + DiaryMath.DAY_MS - 1, full.endDateTime)
        assertEquals(true, full.createInCalendar)

        val overnight = EntryRules.draft(
            event(start = LocalTime(23, 30), end = LocalTime(0, 30)),
            zone,
            NoteType.DEFAULTS,
            null,
        ) { "id" }
        assertEquals(LocalDateTime(2026, 9, 13, 23, 30).toInstant(zone).toEpochMilliseconds(), overnight.startDateTime)
        assertEquals(LocalDateTime(2026, 9, 14, 0, 30).toInstant(zone).toEpochMilliseconds(), overnight.endDateTime)

        val repeating = EntryRules.draft(
            event().copy(repeat = "daily", repeatEnd = LocalDate(2026, 9, 20)),
            zone,
            NoteType.DEFAULTS,
            null,
        ) { "id" }
        assertEquals(DiaryCalendar.startOf(LocalDate(2026, 9, 21), zone) - 1, repeating.repeatEndDate)

        val single = EntryRules.draft(
            event().copy(
                masterId = "m",
                repeat = "daily",
                repeatEnd = LocalDate(2026, 9, 20),
                scope = RecurrenceScope.Single,
            ),
            zone, NoteType.DEFAULTS, createInCalendar = true,
        ) { "id" }
        assertEquals("none", single.repeatStatus)
        assertEquals(0, single.repeatEndDate)
        assertNull(single.createInCalendar, "an edit never carries the calendar flag")
    }

    @Test
    fun `guests keep the ids they had, and a create files on its pinned day`() {
        val form = event().copy(
            guests = listOf("old@b.co", "new@b.co"),
            originalGuests = listOf(GuestEmail("g1", "OLD@b.co")),
        )
        val draft = EntryRules.draft(
            form.copy(pinnedDayId = "day-1", linkKey = "day-2|0"),
            zone,
            NoteType.DEFAULTS,
            false,
        ) { "fresh" }
        assertEquals(listOf(GuestEmail("g1", "old@b.co"), GuestEmail("fresh", "new@b.co")), draft.externalEmails)
        assertEquals("day-1", draft.scheduleDayId)

        val edited = EntryRules.draft(
            form.copy(masterId = "m", pinnedDayId = "day-1", linkKey = null),
            zone,
            NoteType.DEFAULTS,
            null,
        ) { "x" }
        assertEquals("", edited.scheduleDayId, "an edit's link is only ever the picker's")

        val personal = EntryRules.draft(
            EntryForm(
                kind = DiaryKind.Note,
                noteTitle = "Mine",
                noteDate = today,
                noteType = PERSONAL_NOTE_TYPE,
                audience = users,
            ),
            zone, NoteType.DEFAULTS, null,
        ) { "x" }
        assertFalse(personal.audience.isSet, "a personal note distributes to nobody")
    }

    @Test
    fun `a new entry links forward only, an edit keeps its own past day`() {
        val past = DiaryCalendar.startOf(LocalDate(2026, 9, 10), zone)
        val future = DiaryCalendar.startOf(LocalDate(2026, 9, 20), zone)
        val block = ScheduleBlock(
            "b",
            "Stage",
            "t",
            "Shoot Day",
            "#000000",
            listOf(past, future),
            past,
            future,
            2,
            "by_days",
        )
        val todayKey = DiaryCalendar.startOf(today, zone)

        val create = EntryRules.linkOptions(EntryForm(kind = DiaryKind.Event), listOf(block), todayKey, zone)
        assertEquals(listOf("b|$future"), create.map { it.key })
        assertEquals("Sun, Sep 20 — Shoot Day (Stage)", create.single().label)

        val edit = EntryRules.linkOptions(
            EntryForm(kind = DiaryKind.Event, masterId = "m", linkKey = "gone|$past"),
            listOf(block),
            todayKey,
            zone,
        )
        assertEquals(listOf("gone|$past", "b|$past", "b|$future"), edit.map { it.key })
        assertEquals("Thu, Sep 10 — Schedule day", edit.first().label)
    }

    @Test
    fun `the quarter hour rounds up and the end wraps at midnight`() {
        val at = LocalDateTime(2026, 9, 13, 23, 50).toInstant(zone).toEpochMilliseconds()
        assertEquals(LocalTime(0, 0), EntryRules.nextQuarter(at, zone))
        assertEquals(
            LocalTime(10, 15),
            EntryRules.nextQuarter(LocalDateTime(2026, 9, 13, 10, 0).toInstant(zone).toEpochMilliseconds(), zone),
        )
        assertEquals(LocalTime(0, 30), EntryRules.hourAfter(LocalTime(23, 30)))
    }

    @Test
    fun `picking on one invitee tab clears the others, and Done hands back the active tab`() {
        val picker = AudiencePicker.from(DiaryAudience(AudienceMode.Departments, departmentIds = listOf("d1")))
        assertEquals(AudienceMode.Departments, picker.tab)
        val users = picker.copy(tab = AudienceMode.Users, userIds = listOf("u1")).keepingOnly(AudienceMode.Users)
        assertTrue(users.departmentIds.isEmpty())
        assertEquals(DiaryAudience(AudienceMode.Users, userIds = listOf("u1")), users.result())
        assertEquals(12, users.copy(tab = AudienceMode.AllDepartments, allDepartments = true).doneCount(crewSize = 12))
        assertEquals(0, users.copy(tab = AudienceMode.Presets).doneCount(crewSize = 12))
        assertEquals("1 user selected — click to edit", users.result().summary(null))
    }
}
