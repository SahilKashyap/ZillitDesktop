package com.zillit.desktop.feature.boxschedule.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every diary wire event the web box-schedule page refreshes on. The
 * `box_schedule:*` names ARE the wire names — `listenerSocket.js:2163-2181`
 * subscribes each and re-emits it under the same string, which
 * `useBoxScheduleSocketEvents.jsx:37-95` groups and `boxScheduleV2/
 * index.jsx:1538-1554` wires to `refreshAll` / `fetchTypes` /
 * `loadStandaloneEvents`. The wire's asymmetries, verbatim from that
 * registration block: events only emit `:updated`; notes CREATE as
 * `:update` (no "d"), UPDATE as `:updated`, DELETE as `:delete`; schedule
 * types travel as `suggestion`; `box_schedule:update` is a generic
 * refresh ping.
 *
 * The five Main Calendar events ride along (ZL-18748,
 * `listenerSocket.js:888-910`, same-name re-emits): the diary merges the
 * calendar feed, so a calendar CRUD elsewhere must re-pull it here.
 *
 * One flow, not three: the web splits refreshAll / fetchTypes /
 * loadStandaloneEvents, but this client's one `refresh()` already refetches
 * types, blocks, events, and the calendar merge together, so every event
 * lands on the same reload.
 *
 * NOT here: `box_schedule:message:update/delete` (the diary chat surface
 * this module does not carry) and the hyphenated `box-schedule:*` family
 * (`listenerSocket.js:2527-2537`) — that is the LEGACY
 * `pages/box_schedule` page riding the production/pre-production API,
 * a different tool from this `/v2/box-schedule` diary.
 */
val BOX_SCHEDULE_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("box_schedule:day:added"),
    SocketEventName("box_schedule:day:updated"),
    SocketEventName("box_schedule:day:deleted"),
    SocketEventName("box_schedule:event:updated"),
    SocketEventName("box_schedule:note:update"),
    SocketEventName("box_schedule:note:updated"),
    SocketEventName("box_schedule:note:delete"),
    SocketEventName("box_schedule:suggestion:added"),
    SocketEventName("box_schedule:suggestion:updated"),
    SocketEventName("box_schedule:suggestion:deleted"),
    SocketEventName("box_schedule:update"),
    // Main Calendar (ZL-18748) — the diary's merged feed.
    SocketEventName("create:event"),
    SocketEventName("edit:event"),
    SocketEventName("delete:event"),
    SocketEventName("accept:event"),
    SocketEventName("reject:event"),
)

/**
 * The web's `sameProject` guard (`useBoxScheduleSocketEvents.jsx:99-107`),
 * kept lenient exactly as there: a frame that names no project passes, and
 * this service spells the key camelCase (`projectId`) where the older
 * tools use `project_id` — both are read.
 */
internal fun JsonElement?.matchesProject(here: String?): Boolean {
    if (here.isNullOrBlank()) return true
    val incoming = (this as? JsonObject)
        ?.let { frame -> frame["projectId"] ?: frame["project_id"] }
        ?.let { value -> (value as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
        ?: return true
    return incoming == here
}
