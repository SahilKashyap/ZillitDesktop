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
 * The message families (`location_message_*`, `locationUnit_message_*`,
 * LocationPage.jsx:2198-2260) are NOT here: their handlers only refetch
 * unread badges for the page's chat rail, which this module does not carry.
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
