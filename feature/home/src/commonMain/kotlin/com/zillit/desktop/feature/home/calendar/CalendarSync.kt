package com.zillit.desktop.feature.home.calendar

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Live updates for the calendar.
 *
 * The five names are the whole calendar contract, and all three other clients
 * carry them: Android sets `FragmentCalendar.isApiCallNeededForEvents`, iOS
 * reconciles the change into EventKit, and the web invalidates its `Events`
 * and `Invitations` queries (`useCalendarSocketV3.js`).
 *
 * The desktop already *subscribed* to these — but only
 * `BoxScheduleRepositoryImpl` consumed them, for the diary's merged feed, so
 * the Calendar screen and the invitations panel never moved until something
 * else reloaded them (found 2026-09-07 auditing realtime against the phones).
 *
 * They are un-namespaced, unlike almost everything else on this wire. That is
 * the server's spelling, not a mistake here.
 */
val CALENDAR_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("create:event"),
    SocketEventName("edit:event"),
    SocketEventName("delete:event"),
    SocketEventName("accept:event"),
    SocketEventName("reject:event"),
)

/**
 * What a calendar socket frame means to this screen.
 *
 * Coarse on purpose. The payloads carry a single event or an invitation row,
 * but the board fetches a three-month window and the invitation panel is
 * paged — patching either in place from one frame would be a second, subtly
 * different merge path beside the one the loaders already do correctly.
 */
enum class CalendarRealtimeKind {
    /** An event was created, changed or removed by somebody else. */
    Events,

    /** An invitation was answered — the panel and the badge both move. */
    Invitations,
}

/** The calendar's socket traffic, as something the view model can act on. */
fun calendarRealtime(events: SocketEventBus): Flow<CalendarRealtimeKind> =
    events.onAny(CALENDAR_SYNC_EVENTS).map { message ->
        if (message.event in ANSWER_EVENTS) {
            CalendarRealtimeKind.Invitations
        } else {
            CalendarRealtimeKind.Events
        }
    }

/**
 * Answering an invitation changes the answerer's calendar too, so these
 * reload both — they are only separated so an open invitations panel refetches
 * the tab the user is actually looking at.
 */
private val ANSWER_EVENTS = setOf(
    SocketEventName("accept:event"),
    SocketEventName("reject:event"),
)
