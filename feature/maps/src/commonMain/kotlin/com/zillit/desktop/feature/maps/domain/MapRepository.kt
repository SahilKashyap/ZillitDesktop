package com.zillit.desktop.feature.maps.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The map service (`mapapi`), routes under `/api/v2`. */
interface MapRepository {

    /**
     * A pulse per socket frame saying another client changed a location or
     * zone — the web's `map_location_added/updated/deleted` handlers
     * (`MapPage.jsx:73,100,126`). The ViewModel answers with a re-list.
     * Empty by default: tests, and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    suspend fun cities(): ZillitResult<List<MapCity>>
    suspend fun createCity(
        name: String,
        description: String,
        lat: Double,
        lng: Double,
        radiusMiles: Double,
    ): ZillitResult<Unit>
    suspend fun deleteCity(id: String): ZillitResult<Unit>

    suspend fun types(): ZillitResult<List<LocationType>>
    suspend fun createType(name: String, icon: String, subTypes: List<String>): ZillitResult<Unit>
    suspend fun deleteType(id: String): ZillitResult<Unit>

    /**
     * Locations AND studio zones for a city, mixed on the wire and split by
     * `is_studio_zone`. Null city means everything.
     */
    suspend fun locations(cityId: String?): ZillitResult<List<MapLocation>>
    suspend fun createLocation(draft: LocationDraft): ZillitResult<Unit>
    suspend fun updateLocation(id: String, draft: LocationDraft): ZillitResult<Unit>
    suspend fun createZone(draft: ZoneDraft): ZillitResult<Unit>
    suspend fun updateZone(id: String, draft: ZoneDraft): ZillitResult<Unit>
    suspend fun delete(id: String): ZillitResult<Unit>
}
