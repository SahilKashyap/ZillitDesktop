package com.zillit.desktop.feature.crewdood.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The deal events that move a bar on this grid.
 *
 * The web has no Crew DOOD socket of its own: the app-wide `deal:*` listeners
 * dispatch one `ah:deal_memo:list`, and the page refetches its deals in the
 * background. These are the same events Deal Memo listens to, minus the ones
 * that cannot change a booking window — templates and approval tiers.
 *
 * A Production Calendar edit still has no event name (handoff §5.5), so
 * another viewer sees new shoot days on reload; the calendar and the crew list
 * do have them — see [DOOD_CALENDAR_EVENT] and [DOOD_CREW_EVENTS].
 */
internal val DOOD_DEAL_EVENTS: Set<SocketEventName> = setOf(
    "deal:created",
    "deal:submitted",
    "deal:tier-approved",
    "deal:approved",
    "deal:rejected",
    "deal:activated",
    "deal:completed",
    "deal:cancelled",
    "deal:deactivate_scheduled",
    "deal:updated",
    "deal:approval_reset",
    "deal:deleted",
).map(::SocketEventName).toSet()

/**
 * The calendar someone else saved — `PUT crew-dood/calendar`, receivers being
 * every accepted project user (BE 2026-10-14).
 *
 * The frame **is** the saved calendar, so it is applied in place with no read
 * (`CrewDoodWire.calendarPush`), and it is not debounced: each save is one
 * frame and the newest must land.
 */
internal val DOOD_CALENDAR_EVENT = SocketEventName("crew_dood_calendar:updated")

/**
 * Who is in the crew, their department or designation, or their order changed.
 *
 * Any of these means the crew list is stale; the rows and their order come
 * from it, so it alone is reread — once per burst of them (five approvals in a
 * row are one read, as on the web).
 */
internal val DOOD_CREW_EVENTS: Set<SocketEventName> = setOf(
    "project:user:join:request:accepted",
    "project:user:accepted",
    "project:pre-approved:user:joined",
    "project:user:left",
    "project:user:removed",
    "project:user:profile:update",
    "project:user:reordered",
).map(::SocketEventName).toSet()

/**
 * The web's `sameProject` guard, kept as lenient as every other tool's: a
 * frame that names no project passes, and every spelling of the key is read —
 * at the top level and inside `data`, which is where the project-user frames
 * carry it.
 */
internal fun JsonElement?.matchesProject(here: String?): Boolean {
    if (here.isNullOrBlank()) return true
    val frame = this as? JsonObject ?: return true
    val incoming = frame.projectId() ?: (frame["data"] as? JsonObject)?.projectId() ?: return true
    return incoming == here
}

private fun JsonObject.projectId(): String? =
    (this["projectId"] ?: this["project_id"])
        ?.let { value -> (value as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
