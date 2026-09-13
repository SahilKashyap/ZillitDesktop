package com.zillit.desktop.feature.maps.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The map service — `/api/v2/city`, `/api/v2/location-type`, `/api/v2/map`.
 *
 * Every write answers with the server's message (a key, translated where it is
 * shown), because the web's success toasts say what the server said and only
 * fall back to their own wording when it said nothing.
 */
@Suppress("TooManyFunctions") // One suspend fun per server route.
interface MapRepository {

    /**
     * What other clients changed, as the web's `useMapSocket` hears it —
     * already filtered to this production and away from this device. Empty
     * by default: tests, and hosts without a socket.
     */
    val sync: Flow<MapSyncEvent> get() = emptyFlow()

    suspend fun cities(): ZillitResult<List<MapCity>>

    /** Creates a city; the created record rides back when the server sends one. */
    suspend fun createCity(draft: CityDraft): ZillitResult<CityWrite>

    suspend fun deleteCity(id: String): ZillitResult<String?>

    /** `PUT /city/reorder-cities` with `{newOrder}` — the whole arrangement, not a move. */
    suspend fun reorderCities(cityIds: List<String>): ZillitResult<String?>

    suspend fun types(): ZillitResult<List<LocationType>>
    suspend fun createType(draft: TypeDraft): ZillitResult<String?>
    suspend fun updateType(id: String, draft: TypeDraft): ZillitResult<String?>
    suspend fun deleteType(id: String): ZillitResult<String?>

    /** `GET /map?cityId=` — the city's locations (studio zones come separately). */
    suspend fun locations(cityId: String): ZillitResult<List<MapLocation>>

    /** `GET /map/studio-zones?cityId=` — a city's zones, or every zone with no city. */
    suspend fun studioZones(cityId: String?): ZillitResult<List<MapLocation>>

    suspend fun createLocation(draft: LocationDraft): ZillitResult<String?>
    suspend fun updateLocation(id: String, draft: LocationDraft): ZillitResult<String?>
    suspend fun createZone(draft: ZoneDraft): ZillitResult<String?>
    suspend fun updateZone(id: String, draft: ZoneDraft): ZillitResult<String?>

    /** Locations and zones share the route. */
    suspend fun deleteLocation(id: String): ZillitResult<String?>
}

/** A city write's answer: the server's message and, when it sent one, the city. */
data class CityWrite(val message: String?, val city: MapCity?)

/** What another client changed. */
sealed interface MapSyncEvent {
    data class CityUpserted(val city: MapCity, val isNew: Boolean) : MapSyncEvent
    data class CityDeleted(val id: String) : MapSyncEvent

    /** `map:city:reordered` carries no list; the web refetches. */
    data object CitiesReordered : MapSyncEvent

    data class TypeUpserted(val type: LocationType, val isNew: Boolean) : MapSyncEvent
    data class TypeDeleted(val id: String) : MapSyncEvent

    /** A location or a zone — [location.isStudioZone] says which list it belongs in. */
    data class LocationUpserted(val location: MapLocation, val isNew: Boolean) : MapSyncEvent
    data class LocationDeleted(val id: String, val cityId: String, val isStudioZone: Boolean) : MapSyncEvent

    /** A frame that named what changed but carried no record — refetch that list. */
    data class Refetch(val what: MapList) : MapSyncEvent
}

enum class MapList { Cities, Types, Locations }
