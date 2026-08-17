package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.data.eventWire
import com.zillit.desktop.feature.boxschedule.domain.DiaryClock
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The diary's pure rules, transcribed from the web and pinned here. */
class DiaryMathTest {

    private val day = DiaryMath.DAY_MS
    private fun block(id: String, type: String, vararg days: Long, title: String = "") = ScheduleBlock(
        id = id, title = title, typeId = "t-$type", typeName = type, color = "#000000",
        calendarDays = days.toList(), startDate = days.min(), endDate = days.max(),
        numberOfDays = days.size, dateRangeType = "by_days",
    )

    @Test
    fun `explode numbers per type in date order and marks type changes`() {
        val rows = DiaryMath.explode(
            listOf(
                block("b", "Prep", 2 * day, 3 * day),
                block("a", "Shoot Day", 0L, 1 * day, 4 * day),
            ),
        )

        assertEquals(listOf(0L, day, 2 * day, 3 * day, 4 * day), rows.map { it.date })
        assertEquals(listOf(1, 2, 1, 2, 3), rows.map { it.dayNumber })
        assertEquals(listOf(true, false, true, false, true), rows.map { it.isNewBlock })
        assertEquals("Shoot Day", rows[4].block.typeName)
    }

    @Test
    fun `gap detection allows one hour of DST slack`() {
        assertFalse(DiaryMath.hasGaps(listOf(0L, day, 2 * day)))
        assertFalse(DiaryMath.hasGaps(listOf(0L, day + 3_600_000)))
        assertTrue(DiaryMath.hasGaps(listOf(0L, 2 * day)))
    }

    @Test
    fun `range days are inclusive and ordered`() {
        assertEquals(listOf(0L, day, 2 * day), DiaryMath.rangeDays(0L, 2 * day))
        assertTrue(DiaryMath.rangeDays(day, 0L).isEmpty())
    }

    @Test
    fun `calendar copies of mirrored events are dropped`() {
        val diary = listOf(event("e1", calendarEventId = "cal-1"), event("e2"))
        val calendar = listOf(
            event("cal:cal-1", calendarSourced = true),
            event("cal:other", calendarSourced = true),
        )

        val merged = DiaryMath.mergeWithCalendar(diary, calendar)

        assertEquals(listOf("e1", "e2", "cal:other"), merged.map { it.id })
    }

    @Test
    fun `list key and occurrence date follow the recurring contract`() {
        val occurrence = event("m1", occurrenceId = "m1_1755133200000", masterEventId = "m1", isInstance = true)
        assertEquals("m1_1755133200000", occurrence.listKey)
        assertEquals("m1", occurrence.masterId)
        assertEquals(1_755_133_200_000L, occurrence.occurrenceDate)
        assertTrue(occurrence.isRecurring)

        val plain = event("p1", start = 5L)
        assertEquals("p1", plain.listKey)
        assertEquals(5L, plain.occurrenceDate)
        assertFalse(plain.isRecurring)
    }

    @Test
    fun `splice update honours the three server messages`() {
        val a1 = event("m", occurrenceId = "m_100", masterEventId = "m", isInstance = true, start = 100)
        val a2 = event("m", occurrenceId = "m_200", masterEventId = "m", isInstance = true, start = 200)
        val a3 = event("m", occurrenceId = "m_300", masterEventId = "m", isInstance = true, start = 300)
        val other = event("x", start = 250)
        val current = listOf(a1, a2, a3, other)
        val fresh = event("n")

        val forked = DiaryMath.spliceUpdate(current, "occurrences_forked", null, fresh, 200, "m")
        assertEquals(listOf("m_100", "x", "n"), forked.map { it.listKey })

        val single = DiaryMath.spliceUpdate(current, "occurrence_modified", null, fresh, 200, "m")
        assertEquals(listOf("m_100", "m_300", "x", "n"), single.map { it.listKey })

        val whole = DiaryMath.spliceUpdate(current, "event_updated", event("x", start = 999), null, 0, "x")
        assertEquals(999L, whole.first { it.id == "x" }.startDateTime)
    }

    @Test
    fun `event body omits scheduleDayId when unlinked and sends zero repeat end`() {
        val body = eventWire(
            DiaryDraft(
                kind = DiaryKind.Event, title = " Crew call ", body = "d", date = 10, startDateTime = 11,
                endDateTime = 12, fullDay = false, scheduleDayId = "",
            ),
            create = true,
        )
        assertNull(body["scheduleDayId"])
        assertEquals("Crew call", (body["title"] as JsonPrimitive).content)
        assertEquals("0", (body["repeatEndDate"] as JsonPrimitive).content)
        assertEquals("false", (body["createEventInCalendar"] as JsonPrimitive).content)
        assertEquals("event", (body["eventType"] as JsonPrimitive).content)

        val note = eventWire(
            DiaryDraft(
                kind = DiaryKind.Note, title = "n", body = "text", date = 10, startDateTime = 10,
                endDateTime = 10, fullDay = true, scheduleDayId = "day-1", noteType = "crew_start",
            ),
            create = false,
        )
        assertEquals("day-1", (note["scheduleDayId"] as JsonPrimitive).content)
        assertEquals("text", (note["notes"] as JsonPrimitive).content)
        assertNull(note["description"])
        assertNull(note["createEventInCalendar"], "only create sends the calendar flag")
    }

    @Test
    fun `clock parses and formats round trip in a fixed zone`() {
        val zone = TimeZone.of("Asia/Kolkata")
        val midnight = DiaryClock.midnightOf("2026-08-14", zone)!!
        assertEquals("2026-08-14", DiaryClock.ymd(midnight, zone))
        val at = DiaryClock.instantOf("2026-08-14", "06:30", zone)!!
        assertEquals("06:30", DiaryClock.hm(at, zone))
        assertEquals("Fri 14 Aug", DiaryClock.dayLabel(midnight, zone))
        assertNull(DiaryClock.midnightOf("14/08/2026", zone))
        assertNull(DiaryClock.instantOf("2026-08-14", "25:00", zone))
    }

    private fun event(
        id: String,
        start: Long = 0,
        occurrenceId: String = "",
        masterEventId: String = "",
        isInstance: Boolean = false,
        calendarEventId: String = "",
        calendarSourced: Boolean = false,
    ) = DiaryEvent(
        id = id, kind = DiaryKind.Event, title = id, body = "", date = 0, startDateTime = start,
        endDateTime = start, fullDay = false, location = "", color = "", scheduleDayId = "",
        noteType = "", repeatStatus = "", occurrenceId = occurrenceId, masterEventId = masterEventId,
        isRecurringInstance = isInstance, calendarEventId = calendarEventId, calendarSourced = calendarSourced,
    )
}
