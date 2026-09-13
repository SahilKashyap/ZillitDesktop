package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.CalendarMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.DiaryPerson
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import com.zillit.desktop.feature.boxschedule.domain.GuestEmail
import com.zillit.desktop.feature.boxschedule.domain.HistoryEntry
import com.zillit.desktop.feature.boxschedule.domain.ListMode
import com.zillit.desktop.feature.boxschedule.domain.PERSONAL_NOTE_TYPE
import com.zillit.desktop.feature.boxschedule.domain.PresetMember
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DiaryOverlays
import com.zillit.desktop.feature.boxschedule.ui.PageState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** A September on a production — two shoot runs, prep, travel, a day off, meetings and notes. */
internal object DiarySamples {

    val zone: TimeZone = TimeZone.of("Europe/London")
    val today = LocalDate(2026, 9, 13)

    fun day(d: Int): Long = DiaryCalendar.startOf(LocalDate(2026, 9, d), zone)

    fun at(d: Int, h: Int, min: Int = 0): Long = LocalDateTime(2026, 9, d, h, min).toInstant(zone).toEpochMilliseconds()

    val shoot = ScheduleType("t-shoot", "Shoot Day", "#E74C3C", systemDefined = true)
    val prep = ScheduleType("t-prep", "Prep", "#3498DB", systemDefined = true)
    val travel = ScheduleType("t-travel", "Travel", "#F39C12", systemDefined = false)
    val off = ScheduleType("t-off", "Day Off", "#95A5A6", systemDefined = true)
    val types = listOf(shoot, prep, travel, off)

    private fun block(id: String, type: ScheduleType, days: List<Int>, title: String = "", version: Int = 1) =
        ScheduleBlock(
            id = id,
            title = title,
            typeId = type.id,
            typeName = type.title,
            color = type.color,
            calendarDays = days.map(::day),
            startDate = day(days.min()),
            endDate = day(days.max()),
            numberOfDays = days.size,
            dateRangeType = "by_days",
            createdAt = at(1, 10),
            version = version,
        )

    val blocks = listOf(
        block("b-prep", prep, (8..11).toList(), title = "Stage build"),
        block("b-shoot", shoot, (14..18).toList(), title = "Stage 4 — Interior"),
        block("b-off", off, listOf(19, 20)),
        block("b-travel", travel, listOf(21), title = "Unit move to Pinewood"),
        block("b-shoot2", shoot, (22..25).toList(), title = "Exterior — backlot", version = 3),
    )

    val people = listOf(
        DiaryPerson("u1", "Asha Rao", "Camera", "Director of Photography"),
        DiaryPerson("u2", "Dev Patel", "Art", "Production Designer"),
        DiaryPerson("u3", "Mia Chen", "Costume", "Costume Supervisor"),
        DiaryPerson("u4", "Sam Okafor", "Production", "1st AD", isAdmin = true),
    )

    /** One timed event; the rest of a card's fields take a production's usual values. */
    private data class Timed(
        val id: String,
        val title: String,
        val d: Int,
        val h: Int,
        val minutes: Int = 60,
        val callType: String = "video",
        val location: String = "",
        val audience: DiaryAudience = DiaryAudience(AudienceMode.AllDepartments),
        val scheduleDayId: String = "",
        val color: String = "#3498DB",
    )

    private fun Timed.toEvent() = DiaryEvent(
        id = id,
        kind = DiaryKind.Event,
        title = title,
        body = "",
        date = day(d),
        startDateTime = at(d, h),
        endDateTime = at(d, h) + minutes * MS_PER_MINUTE,
        fullDay = false,
        location = location,
        color = color,
        scheduleDayId = scheduleDayId,
        noteType = "",
        repeatStatus = "none",
        occurrenceId = "",
        masterEventId = "",
        isRecurringInstance = false,
        calendarEventId = "",
        callType = callType,
        cncCallGroupId = "room-$id",
        reminder = "15min",
        timezone = zone.id,
        audience = audience,
        externalEmails = if (id == "e1") listOf(GuestEmail("g1", "producer@studio.co")) else emptyList(),
        createdByName = "Sam Okafor",
        createdAt = at(1, 9),
    )

    private fun note(id: String, title: String, d: Int, body: String, personal: Boolean = false, dayId: String = "") =
        DiaryEvent(
            id = id,
            kind = DiaryKind.Note,
            title = title,
            body = body,
            date = day(d),
            startDateTime = 0,
            endDateTime = 0,
            fullDay = true,
            location = "",
            color = "#F39C12",
            scheduleDayId = dayId,
            noteType = if (personal) PERSONAL_NOTE_TYPE else "general",
            repeatStatus = "none",
            occurrenceId = "",
            masterEventId = "",
            isRecurringInstance = false,
            calendarEventId = "",
            audience = if (personal) DiaryAudience() else DiaryAudience(AudienceMode.Users, listOf("u1", "u2")),
            createdByName = "Sam Okafor",
            createdAt = at(2, 9),
        )

    /** A daily stand-up served as occurrences, the way the server expands a series. */
    private fun standup(d: Int) = Timed("m1", "Morning stand-up", d, 7, 30, "audio").toEvent().copy(
        repeatStatus = "daily",
        occurrenceId = "m1_${at(d, 7)}",
        masterEventId = "m1",
        isRecurringInstance = true,
        repeatEndDate = at(18, 7),
    )

    private val heads = listOf("u1", "u2", "u3")

    val events = listOf(
        Timed("e1", "Production meeting", 14, 8, audience = DiaryAudience(AudienceMode.Users, heads)),
        Timed("e2", "Costume fitting", 15, 11, 90, "meet_in_person", "Wardrobe, Stage 2", color = "#8E44AD"),
        Timed("e3", "Tech recce", 21, 9, 180, "meet_in_person_call", "Pinewood Studios, Iver Heath"),
        Timed("e4", "Daily rushes", 15, 18, 45, scheduleDayId = "b-shoot", color = "#27AE60"),
        Timed("e5", "Camera tests", 10, 14, 120, "meet_in_person", "Stage 4"),
    ).map { it.toEvent() } + listOf(
        note("n1", "Rain cover", 15, "Tarps and a covered set on Stage 2", dayId = "b-shoot"),
        note("n2", "Call my agent", 17, "", personal = true),
        note("n3", "Generator on standby", 22, "Backlot power is unreliable after 6pm"),
    ) + (14..18).map(::standup)

    private fun member(person: DiaryPerson) = PresetMember(person.id, person.fullName, person.designation)

    val presets = listOf(
        UserPreset("p1", "Heads of department", people.filter { it.id in heads }.map(::member)),
        UserPreset("p2", "Camera team", listOf(PresetMember("u1", "Asha Rao", "Director of Photography"))),
    )

    val history = listOf(
        HistoryEntry("l1", "created", "note", "n1", "Rain cover", "", "u4", "Sam Okafor", at(13, 8, 40)),
        HistoryEntry(
            "l2", "updated", "schedule_day", "b-shoot", "Stage 4 — Interior", "", "u1", "Asha Rao", at(12, 16, 5),
        ),
        HistoryEntry("l3", "deleted", "event", "e9", "Location scout", "", "u2", "Dev Patel", at(11, 11, 30)),
    )

    fun state(
        view: DiaryView = DiaryView.Calendar,
        calendarMode: CalendarMode = CalendarMode.Month,
        listMode: ListMode = ListMode.ByDate,
        overlays: DiaryOverlays = DiaryOverlays(),
        canEdit: Boolean = true,
        focus: LocalDate = today,
    ) = BoxScheduleUiState(
        viewer = BoxScheduleViewer(userId = "u4", displayName = "Sam Okafor", canEdit = canEdit, ready = true),
        loadedOnce = true,
        types = types,
        blocks = blocks,
        rows = DiaryMath.explode(blocks, zone),
        events = events,
        people = people,
        today = today,
        nowMillis = at(13, 9),
        zone = zone,
        canPrint = true,
        historyBadge = 2,
        page = PageState(view = view, calendarMode = calendarMode, listMode = listMode).focusedOn(focus),
        overlays = overlays,
    )

    private const val MS_PER_MINUTE = 60_000L
}
