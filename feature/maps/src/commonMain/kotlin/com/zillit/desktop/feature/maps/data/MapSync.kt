package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The three WIRE events behind the web map page's handlers. On the wire
 * they are colon-delimited (`listenerSocket.js:2558-2566` subscribes
 * `map:location:added/updated/deleted` and re-emits them internally as
 * `map_location_*`, which is what `MapPage.jsx:73,100,126` consumes).
 * All splice the locations / studio-zones lists in place; this client's
 * equivalent is one targeted re-list, which is also what the web's own
 * edit path does — there is no `/map/{id}` read route.
 *
 * The city / location-type / pinned families (`map:city:*`,
 * `map:locationType:*`, `map:pinnedLocation:*`) are NOT here: only the
 * recce picker's map-module listens to those
 * (`components/map-module/socket/useMapSocket.js`), not the map tool.
 */
val MAP_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("map:location:added"),
    SocketEventName("map:location:updated"),
    SocketEventName("map:location:deleted"),
)

/**
 * The web's guard, made lenient: every handler checks
 * `data?.project_id == projectDetails?.project_id` before touching state.
 * A frame that names no project passes — dropping it would eat a refresh —
 * and only a frame that names ANOTHER project is ignored.
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
