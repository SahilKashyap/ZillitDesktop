package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.MapScene
import com.zillit.desktop.feature.maps.domain.MapSyncEvent
import com.zillit.desktop.feature.maps.domain.SceneCircle
import com.zillit.desktop.feature.maps.domain.SceneCities
import com.zillit.desktop.feature.maps.domain.SceneMarker
import com.zillit.desktop.feature.maps.domain.SceneZone

/**
 * What the map draws for this state — pure, so the map and the lists can be
 * tested to agree without a browser.
 *
 * The type filter applies to the pins exactly as it does to the toolbar's
 * count; the search never hides pins (`filteredLocations` in `MapView`).
 */
internal fun MapUiState.toScene(): MapScene {
    val zoneFormOpen = topPanel == MapPanel.ZoneForm && zoneForm != null
    val locationFormOpen = topPanel == MapPanel.LocationForm && locationForm != null
    val editingActiveZone = zoneFormOpen && zoneForm.editId != null && zoneForm.editId == activeZoneId
    return MapScene(
        markers = sceneMarkers(),
        pinMode = pinMode,
        draggable = pinMode && viewer.mayPost,
        lockedId = movingId,
        pendingMove = pendingMove,
        // The zone being edited is drawn by the form's own circle instead.
        zone = sceneZone(editingActiveZone),
        draftZone = zoneForm?.takeIf { zoneFormOpen }?.let { form ->
            form.point?.let { SceneCircle(it, form.effectiveRadius) }
        },
        draftPin = locationForm?.takeIf { locationFormOpen }?.point,
        cities = sceneCities(),
        guide = guide,
        canPost = viewer.mayPost,
        nonce = snapNonce,
    )
}

/** One marker per filtered location that has a point, styled by its type. */
private fun MapUiState.sceneMarkers(): List<SceneMarker> = filteredLocations.mapNotNull { location ->
    val point = location.point ?: return@mapNotNull null
    val style = style(location.type)
    SceneMarker(
        id = location.id,
        name = location.displayName,
        type = if (location.hasType) location.type else "",
        icon = style.icon,
        color = style.colorHex,
        point = point,
        address = location.address,
        subTypes = location.subTypes,
        sceneNumber = location.sceneNumber,
        description = location.description,
    )
}

/** The drawn studio zone, unless the form is drawing its own circle instead. */
private fun MapUiState.sceneZone(editingActiveZone: Boolean): SceneZone? =
    activeZone?.takeUnless { editingActiveZone }?.let { zone ->
        zone.point?.let { centre ->
            SceneZone(
                id = zone.id,
                name = zone.displayName,
                circle = SceneCircle(centre, zone.zoneRadiusMiles),
                address = zone.address,
                type = zone.type,
                streets = zone.intersection?.takeIf { zone.centerPointType == CenterPointType.Intersection }?.label,
            )
        }
    }

/**
 * The floating control steps aside while the Cities panel or the search is
 * open, as the web's does (`!showCityPanel && !showSearchBar`).
 */
private fun MapUiState.sceneCities(): SceneCities? =
    if (citiesLoaded && topPanel != MapPanel.Cities && search == null) {
        SceneCities(
            count = cities.size,
            unread = totalUnread,
            selectedName = selectedCity?.displayName,
            selectedCount = filteredLocations.size,
        )
    } else {
        null
    }

/**
 * Applies another client's change in place — `useMapSocket` and
 * `useLocations`' splice, made idempotent so a frame this client caused
 * itself (when the device filter cannot tell) changes nothing.
 */
internal fun MapUiState.applySync(event: MapSyncEvent): MapUiState = when (event) {
    // `{ ...c, ...city }`: a city frame need not carry its counts, and a
    // merge keeps the ones already on screen.
    is MapSyncEvent.CityUpserted -> copy(
        cities = cities.upsert(
            cities.firstOrNull { it.id == event.city.id }?.let { old ->
                event.city.copy(
                    locationCount = event.city.locationCount.takeIf { it > 0 } ?: old.locationCount,
                    coordinates = event.city.coordinates ?: old.coordinates,
                    centerPoint = event.city.centerPoint ?: old.centerPoint,
                )
            } ?: event.city,
        ) { it.id },
    )
    is MapSyncEvent.CityDeleted -> copy(cities = cities.filterNot { it.id == event.id })
    is MapSyncEvent.TypeUpserted -> copy(types = types.upsert(event.type) { it.id })
    is MapSyncEvent.TypeDeleted -> copy(types = types.filterNot { it.id == event.id })
    is MapSyncEvent.LocationUpserted -> applyLocation(event)
    is MapSyncEvent.LocationDeleted ->
        if (event.isStudioZone) {
            copy(zones = zones.filterNot { it.id == event.id })
        } else {
            copy(locations = locations.filterNot { it.id == event.id })
        }
    MapSyncEvent.CitiesReordered, is MapSyncEvent.Refetch -> this
}

/** A location or zone belongs here only when it is in the city on screen. */
private fun MapUiState.applyLocation(event: MapSyncEvent.LocationUpserted): MapUiState {
    val location = event.location
    val inCity = selectedCityId == null || location.cityId.isBlank() || location.cityId == selectedCityId
    if (!inCity) return this
    return if (location.isStudioZone) {
        copy(zones = zones.upsert(location) { it.id })
    } else {
        copy(locations = locations.upsert(location) { it.id })
    }
}

/** Replaces the entry with [item]'s key, or appends it — never duplicates. */
private fun <T, K> List<T>.upsert(item: T, key: (T) -> K): List<T> {
    val id = key(item)
    return if (any { key(it) == id }) map { if (key(it) == id) item else it } else this + item
}
