@file:Suppress("MatchingDeclarationName") // The file holds the whole sync wire, not just this table.

package com.zillit.desktop.feature.maps.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.maps.domain.MapList
import com.zillit.desktop.feature.maps.domain.MapSyncEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The wire events behind the web map module's `useMapSocket` — the names in
 * `socket/listenerSocket.js`, not the `map_city_added`-style aliases the web
 * re-emits them under.
 */
internal object MapWireEvents {
    const val CITY_ADDED = "map:city:added"
    const val CITY_UPDATED = "map:city:updated"
    const val CITY_DELETED = "map:city:deleted"
    const val CITY_REORDERED = "map:city:reordered"
    const val TYPE_ADDED = "map:locationType:added"
    const val TYPE_UPDATED = "map:locationType:updated"
    const val TYPE_DELETED = "map:locationType:deleted"
    const val PINNED_ADDED = "map:pinnedLocation:added"
    const val PINNED_UPDATED = "map:pinnedLocation:updated"
    const val PINNED_DELETED = "map:pinnedLocation:deleted"
    const val LOCATION_ADDED = "map:location:added"
    const val LOCATION_UPDATED = "map:location:updated"
    const val LOCATION_DELETED = "map:location:deleted"

    /**
     * The original map's bare city names. The web module no longer listens to
     * them, but both phones still do and a server that sends them should not
     * leave the strip stale — they carry no record, so they refetch.
     */
    val LEGACY_CITY = listOf("city:added", "city:updated", "city:deleted", "city:reordered")
}

/** Everything the map tool listens on. */
val MAP_SYNC_EVENTS: List<SocketEventName> = listOf(
    MapWireEvents.CITY_ADDED, MapWireEvents.CITY_UPDATED, MapWireEvents.CITY_DELETED, MapWireEvents.CITY_REORDERED,
    MapWireEvents.TYPE_ADDED, MapWireEvents.TYPE_UPDATED, MapWireEvents.TYPE_DELETED,
    MapWireEvents.PINNED_ADDED, MapWireEvents.PINNED_UPDATED, MapWireEvents.PINNED_DELETED,
    MapWireEvents.LOCATION_ADDED, MapWireEvents.LOCATION_UPDATED, MapWireEvents.LOCATION_DELETED,
).map(::SocketEventName) + MapWireEvents.LEGACY_CITY.map(::SocketEventName)

/**
 * One frame, as the change it describes — or null for a frame this client
 * should not act on.
 *
 * The web's `shouldProcessEvent`: a frame must name THIS production, and one
 * from this very device is skipped (its own write already updated the
 * screen). With no production known locally the project test cannot be made
 * and the frame passes; with no device id, the splice is idempotent anyway.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount") // One branch per wire event; each guard drops the frame.
internal fun mapSyncEvent(
    event: String,
    payload: JsonElement?,
    projectId: String?,
    deviceId: String?,
): MapSyncEvent? {
    if (event in MapWireEvents.LEGACY_CITY) return MapSyncEvent.Refetch(MapList.Cities)
    val frame = payload as? JsonObject ?: return null
    if (!projectId.isNullOrBlank() && frame.text("project_id", "projectId") != projectId) return null
    val sender = frame.text("device_id", "deviceId")
    if (!deviceId.isNullOrBlank() && sender.isNotBlank() && sender == deviceId) return null

    val entity = frame.firstOf("entity_data", "map_location", "location_data") as? JsonObject
    return when (event) {
        MapWireEvents.CITY_ADDED, MapWireEvents.CITY_UPDATED -> parseCity(entity)
            ?.let { MapSyncEvent.CityUpserted(it, isNew = event == MapWireEvents.CITY_ADDED) }
            ?: MapSyncEvent.Refetch(MapList.Cities)
        MapWireEvents.CITY_DELETED -> entity?.text("_id", "id")?.takeIf { it.isNotBlank() }
            ?.let { MapSyncEvent.CityDeleted(it) }
            ?: MapSyncEvent.Refetch(MapList.Cities)
        MapWireEvents.CITY_REORDERED -> MapSyncEvent.CitiesReordered

        MapWireEvents.TYPE_ADDED, MapWireEvents.TYPE_UPDATED -> parseType(entity)
            ?.let { MapSyncEvent.TypeUpserted(it, isNew = event == MapWireEvents.TYPE_ADDED) }
            ?: MapSyncEvent.Refetch(MapList.Types)
        MapWireEvents.TYPE_DELETED -> entity?.text("_id", "id")?.takeIf { it.isNotBlank() }
            ?.let { MapSyncEvent.TypeDeleted(it) }
            ?: MapSyncEvent.Refetch(MapList.Types)

        MapWireEvents.PINNED_ADDED, MapWireEvents.PINNED_UPDATED, MapWireEvents.PINNED_DELETED ->
            locationChange(event, entity, zoneByDefault = true)
        MapWireEvents.LOCATION_ADDED, MapWireEvents.LOCATION_UPDATED, MapWireEvents.LOCATION_DELETED ->
            locationChange(event, entity, zoneByDefault = false)
        else -> null
    }
}

/**
 * A location or zone frame. `is_studio_zone` decides the list; a pinned-
 * location frame that does not say is a zone (`?? true`), a location frame a
 * location (`?? false`) — the web's defaults.
 */
@Suppress("ReturnCount") // Each missing field falls back to a refetch.
private fun locationChange(event: String, entity: JsonObject?, zoneByDefault: Boolean): MapSyncEvent {
    entity ?: return MapSyncEvent.Refetch(MapList.Locations)
    val isZone = (entity["is_studio_zone"] as? JsonPrimitive)?.contentOrNull
        ?.equals("true", ignoreCase = true)
        ?: zoneByDefault
    if (event.endsWith(":deleted")) {
        val id = entity.text("_id", "id").takeIf { it.isNotBlank() } ?: return MapSyncEvent.Refetch(MapList.Locations)
        return MapSyncEvent.LocationDeleted(id = id, cityId = entity.text("city_id", "cityId"), isStudioZone = isZone)
    }
    val location = parseLocation(entity)?.copy(isStudioZone = isZone) ?: return MapSyncEvent.Refetch(MapList.Locations)
    return MapSyncEvent.LocationUpserted(location, isNew = event.endsWith(":added"))
}
