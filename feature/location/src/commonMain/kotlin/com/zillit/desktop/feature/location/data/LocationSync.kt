package com.zillit.desktop.feature.location.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The record WIRE events behind the web location page's handlers. On the
 * wire they are colon-delimited (`listenerSocket.js:1456-1464` subscribes
 * `location:created/updated/deleted` and re-emits them internally as
 * `location_created/updated/deleted`, which `LocationPage.jsx:1044,1108,
 * 1126` and `ShowLocationDataList.jsx:211-217` consume). Each refreshes
 * the shortlist the event's `status` names; this client re-lists the open
 * shortlist — an event for another shortlist is picked up when that tab
 * loads.
 *
 * The message family is not here either — it has its own list below, because
 * it reloads a different thing.
 */
val LOCATION_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("location:created"),
    SocketEventName("location:updated"),
    SocketEventName("location:deleted"),
)

/**
 * The web's guard, made lenient: every handler checks
 * `data?.project_id == projectDetails?.project_id`. A frame that names no
 * project passes — dropping it would eat a refresh — and only a frame that
 * names ANOTHER project is ignored.
 */
internal fun JsonElement?.matchesProject(here: String?): Boolean {
    if (here.isNullOrBlank()) return true
    val incoming = (this as? JsonObject)
        ?.let { frame -> frame["project_id"] ?: frame["projectId"] }
        ?.let { value -> (value as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
        ?: return true
    return incoming == here
}

/**
 * The record discussion's wire names.
 *
 * The web ignores these (`LocationPage.jsx:2198-2260` only refetches unread
 * badges for a chat rail this module does not carry), but **both phones drive
 * the thread from them** — iOS `location:message:added` →
 * `.updateMessageViaSocket` → refetch, Android `_isMessageAdded`. The desktop
 * has the thread the web lacks, so the phones are the right reference and this
 * was a real gap (audited 2026-09-07, wired 2026-09-09).
 *
 * `:message:deleted:multiple` and no singular: on this board the wire only
 * ever sends the plural.
 */
val LOCATION_DISCUSSION_EVENTS: List<SocketEventName> = listOf(
    "location:message:added",
    "location:message:edited",
    "location:message:deleted:multiple",
    "location:message:comment:added",
    "location:message:comment:edited",
    "location:message:comment:deleted",
    "location:message:readby:update",
).map(::SocketEventName)
