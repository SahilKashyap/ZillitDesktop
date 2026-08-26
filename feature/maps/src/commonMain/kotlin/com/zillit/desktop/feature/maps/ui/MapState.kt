package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapViewer

/** A location or zone being created or edited. Text fields; parsed on save. */
data class PinEditor(
    val locationId: String? = null,
    val isZone: Boolean = false,
    val cityId: String = "",
    val name: String = "",
    val type: String = "",
    val subTypes: Set<String> = emptySet(),
    val description: String = "",
    val address: String = "",
    val sceneNumber: String = "",
    val latText: String = "",
    val lngText: String = "",
    val radiusText: String = "30",
    val saving: Boolean = false,
)

data class CityEditor(
    val name: String = "",
    val description: String = "",
    val latText: String = "",
    val lngText: String = "",
    val radiusText: String = "0",
    val saving: Boolean = false,
)

data class MapUiState(
    val viewer: MapViewer = MapViewer(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /**
     * Why the map canvas cannot draw — a rejected or missing Google key, or
     * no embedded browser. Shown over the canvas pane instead of a grey map;
     * null when the canvas is fine (or absent).
     */
    val canvasError: String? = null,
    val cities: List<MapCity> = emptyList(),
    val selectedCityId: String? = null,
    val types: List<LocationType> = emptyList(),
    val pins: List<MapLocation> = emptyList(),
    val typeFilter: String? = null,
    val pinEditor: PinEditor? = null,
    val cityEditor: CityEditor? = null,
) {
    val locations: List<MapLocation>
        get() = pins.filter { !it.isStudioZone && (typeFilter == null || it.type == typeFilter) }
    val zones: List<MapLocation> get() = pins.filter { it.isStudioZone }
    val selectedCity: MapCity? get() = cities.firstOrNull { it.id == selectedCityId }
}

sealed interface MapEvent {
    data object Refresh : MapEvent
    data class SelectCity(val cityId: String?) : MapEvent

    /**
     * Moves a city one place up or down the list.
     *
     * Up/down rather than drag: the list is a short sidebar, and a keyboard
     * and mouse both reach a button. The service takes the whole arrangement
     * either way.
     */
    data class MoveCity(val cityId: String, val up: Boolean) : MapEvent
    data class FilterType(val type: String?) : MapEvent

    data class NewPin(val isZone: Boolean) : MapEvent
    data class EditPin(val id: String) : MapEvent
    data class PinChanged(
        val cityId: String? = null,
        val name: String? = null,
        val type: String? = null,
        val toggleSubType: String? = null,
        val description: String? = null,
        val address: String? = null,
        val sceneNumber: String? = null,
        val latText: String? = null,
        val lngText: String? = null,
        val radiusText: String? = null,
    ) : MapEvent
    data object SavePin : MapEvent
    data object ClosePin : MapEvent
    data class DeletePin(val id: String) : MapEvent
    data class OpenInMaps(val id: String) : MapEvent

    data object NewCity : MapEvent
    data class CityChanged(
        val name: String? = null,
        val description: String? = null,
        val latText: String? = null,
        val lngText: String? = null,
        val radiusText: String? = null,
    ) : MapEvent
    data object SaveCity : MapEvent
    data object CloseCity : MapEvent
    data class DeleteCity(val id: String) : MapEvent

    data object DismissError : MapEvent
}

sealed interface MapEffect {
    data class Notice(val message: String) : MapEffect
    data class OpenUrl(val url: String) : MapEffect
}
