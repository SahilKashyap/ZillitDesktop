package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.layOutDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Placing events on a day's time grid.
 *
 * The web uses FullCalendar's `timeGrid`; this is the same job done by hand.
 * The overlap rule is what makes it worth having: a morning of back-to-back
 * meetings has to be readable rather than a stack of covered blocks.
 */
class TimeGridLayoutTest {

    private val dayStart = 0L
    private val dayEnd = HOUR * 24

    private fun event(id: String, fromHour: Double, toHour: Double, allDay: Boolean = false) =
        CalendarEvent(
            id = id,
            title = id,
            startMillis = (fromHour * HOUR).toLong(),
            endMillis = (toHour * HOUR).toLong(),
            isAllDay = allDay,
        )

    private fun layout(vararg events: CalendarEvent) = layOutDay(events.toList(), dayStart, dayEnd)

    // -- position ----------------------------------------------------------

    @Test
    fun `an event sits at its start time and is as tall as it is long`() {
        val placed = layout(event("a", 9.0, 12.0)).single()

        assertEquals(9f / 24f, placed.top, TOLERANCE)
        assertEquals(3f / 24f, placed.height, TOLERANCE)
    }

    @Test
    fun `a zero-length event is still tall enough to click`() {
        // Otherwise it renders as an invisible line.
        val placed = layout(event("a", 9.0, 9.0)).single()

        assertTrue(placed.height > 0f)
    }

    @Test
    fun `an event with no end is given a sensible duration`() {
        val open = CalendarEvent(id = "a", title = "a", startMillis = 9 * HOUR, endMillis = 0)

        val placed = layOutDay(listOf(open), dayStart, dayEnd).single()

        assertTrue(placed.height > 0f)
        assertEquals(9f / 24f, placed.top, TOLERANCE)
    }

    @Test
    fun `an event running past midnight is clipped to the day`() {
        // A multi-day event shows as a full block on each day it covers, rather
        // than running off the bottom of the first one.
        val placed = layout(event("a", 22.0, 30.0)).single()

        assertEquals(22f / 24f, placed.top, TOLERANCE)
        assertEquals(2f / 24f, placed.height, TOLERANCE)
    }

    @Test
    fun `an event starting before the day is clipped at the top`() {
        val placed = layout(event("a", -3.0, 2.0)).single()

        assertEquals(0f, placed.top, TOLERANCE)
        assertEquals(2f / 24f, placed.height, TOLERANCE)
    }

    // -- overlap -----------------------------------------------------------

    @Test
    fun `events that do not overlap each take the full width`() {
        val placed = layout(event("a", 9.0, 10.0), event("b", 11.0, 12.0))

        assertTrue(placed.all { it.lanes == 1 }, placed.map { it.lanes }.toString())
        assertTrue(placed.all { it.widthFraction == 1f })
    }

    @Test
    fun `two overlapping events split the width`() {
        val placed = layout(event("a", 9.0, 11.0), event("b", 10.0, 12.0))

        assertTrue(placed.all { it.lanes == 2 })
        assertEquals(setOf(0, 1), placed.map { it.lane }.toSet())
        assertEquals(0f, placed.first { it.event.id == "a" }.leftFraction, TOLERANCE)
        assertEquals(0.5f, placed.first { it.event.id == "b" }.leftFraction, TOLERANCE)
    }

    @Test
    fun `three at once split three ways`() {
        val placed = layout(event("a", 9.0, 12.0), event("b", 9.5, 11.0), event("c", 10.0, 10.5))

        assertTrue(placed.all { it.lanes == 3 })
        assertEquals(setOf(0, 1, 2), placed.map { it.lane }.toSet())
    }

    @Test
    fun `a lane is reused once its event has finished`() {
        // 9-10 and 10-11 do not overlap, so the second reuses the first's lane
        // rather than starting a third column.
        val placed = layout(event("a", 9.0, 10.0), event("b", 9.5, 11.0), event("c", 10.0, 10.5))

        assertEquals(2, placed.maxOf { it.lanes })
    }

    @Test
    fun `an unrelated afternoon event is not squeezed by a busy morning`() {
        // The bug this guards: counting lanes per day rather than per cluster
        // would make a 4pm meeting a third of the width because 9am was busy.
        val placed = layout(
            event("a", 9.0, 12.0),
            event("b", 9.0, 12.0),
            event("c", 9.0, 12.0),
            event("afternoon", 16.0, 17.0),
        )

        assertEquals(1, placed.first { it.event.id == "afternoon" }.lanes)
        assertEquals(3, placed.first { it.event.id == "a" }.lanes)
    }

    @Test
    fun `a long event chains its cluster together`() {
        // b overlaps a, c overlaps b but not a — all three are one run, because
        // placing c in a's lane would put it under b.
        val placed = layout(event("a", 9.0, 11.0), event("b", 10.0, 13.0), event("c", 12.0, 14.0))

        assertTrue(placed.all { it.lanes >= 2 })
    }

    // -- what is excluded ---------------------------------------------------

    @Test
    fun `all-day events are not on the time grid`() {
        // They have no position on a time axis and belong in the strip above.
        val placed = layout(event("a", 0.0, 24.0, allDay = true), event("b", 9.0, 10.0))

        assertEquals(listOf("b"), placed.map { it.event.id })
    }

    @Test
    fun `an event entirely outside the day is dropped`() {
        assertTrue(layout(event("a", 26.0, 28.0)).isEmpty())
    }

    @Test
    fun `an empty day lays out nothing`() {
        assertTrue(layOutDay(emptyList(), dayStart, dayEnd).isEmpty())
    }

    @Test
    fun `a zero-length day does not divide by zero`() {
        assertTrue(layOutDay(listOf(event("a", 9.0, 10.0)), dayStart, dayStart).isEmpty())
    }

    private companion object {
        const val HOUR = 60L * 60L * 1000L
        const val TOLERANCE = 0.0001f
    }
}
