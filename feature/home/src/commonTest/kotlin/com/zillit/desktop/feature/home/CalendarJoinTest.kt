package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CallType
import com.zillit.desktop.feature.home.calendar.canJoinCall
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which calendar events offer a call to join.
 *
 * The room is the event's own `cnc_group_id`: everyone invited dials the same
 * one rather than ringing each other, so an event without one has nothing to
 * join however it was configured.
 */
class CalendarJoinTest {

    private val now = 1_000_000L

    private fun event(
        callType: CallType? = CallType.Audio,
        room: String = "group-7",
        end: Long = now + 60_000,
        status: String = "",
    ) = CalendarEvent(
        id = "e1",
        title = "Standup",
        startMillis = now - 60_000,
        endMillis = end,
        callType = callType,
        cncGroupId = room,
        status = status,
    )

    @Test
    fun `an event with a call and a room can be joined`() {
        assertTrue(event().canJoinCall(now))
        assertTrue(event(callType = CallType.Video).canJoinCall(now))
        // Both a place to be AND a room to dial.
        assertTrue(event(callType = CallType.InPersonAndCall).canJoinCall(now))
    }

    @Test
    fun `a plain in-person meeting is somewhere to be, not something to join`() {
        assertFalse(event(callType = CallType.InPerson).canJoinCall(now))
        assertFalse(event(callType = null).canJoinCall(now))
    }

    @Test
    fun `an event with no room has nothing to dial`() {
        // The server mints the group only for events created with a call, so
        // this is the shape an older or call-less event arrives in.
        assertFalse(event(room = "").canJoinCall(now))
    }

    @Test
    fun `a finished event is not joinable, and one with no end still is`() {
        assertFalse(event(end = now - 1).canJoinCall(now))
        // A missing end must NOT fall back to the start, or an event becomes
        // unjoinable the moment it begins — which is when people join it.
        assertTrue(event(end = 0).canJoinCall(now))
    }
}
