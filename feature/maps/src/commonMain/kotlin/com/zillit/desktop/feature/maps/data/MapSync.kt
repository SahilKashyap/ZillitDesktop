package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The pins themselves: added, moved or removed elsewhere. */
val MAP_LOCATION_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("map:location:added"),
    SocketEventName("map:location:updated"),
    SocketEventName("map:location:deleted"),
)

/**
 * The city list and its order.
 *
 * Two spellings, both live: the bare names are the original map's and the
 * `map:`-prefixed ones are what Android files under "New Map module socket
 * events". Both phones subscribe to both sets, and iOS answers all eight with
 * the one notification, so both are here.
 */
val MAP_CITY_EVENTS: List<SocketEventName> = listOf(
    "city:added", "city:updated", "city:deleted", "city:reordered",
    "map:city:added", "map:city:updated", "map:city:deleted", "map:city:reordered",
).map(::SocketEventName)

/**
 * Everything the map tool listens on.
 *
 * The location three are the WIRE events behind the web map page's handlers.
 * On the wire
 * they are colon-delimited (`listenerSocket.js:2558-2566` subscribes
 * `map:location:added/updated/deleted` and re-emits them internally as
 * `map_location_*`, which is what `MapPage.jsx:73,100,126` consumes).
 * All splice the locations / studio-zones lists in place; this client's
 * equivalent is one targeted re-list, which is also what the web's own
 * edit path does — there is no `/map/{id}` read route.
 *
 * The location-type and pinned families (`map:locationType:*`,
 * `map:pinnedLocation:*`) are NOT here: only the recce picker's map-module
 * listens to those (`components/map-module/socket/useMapSocket.js`), not the
 * map tool.
 *
 * The city family was excluded on the same authority until 2026-09-09. That
 * was wrong for this client: the desktop map tool owns the city list and its
 * ordering ([MapEvent.MoveCity]), and iOS names its own handler for exactly
 * that surface — `.updateMapToolCityPriorityObserver`
 * (`ProjectObserver.swift:7154,7200`).
 */
val MAP_SYNC_EVENTS: List<SocketEventName> = MAP_LOCATION_EVENTS + MAP_CITY_EVENTS

/**
 * What a map frame asks the screen to re-read.
 *
 * A pin change re-lists the pins, which is cheap and already what the edit
 * path does. A city change has to re-read the city strip as well — its
 * ordering is the whole point of `:reordered`, and pins are filtered by the
 * selected city, so reloading only the pins would leave a renamed or removed
 * city on screen.
 */
enum class MapRefresh {
    Pins,
    Cities,
}

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
