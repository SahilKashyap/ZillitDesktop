package com.zillit.desktop.feature.maps.domain

import kotlinx.coroutines.flow.Flow

/**
 * One marker or zone circle as the map canvas draws it.
 *
 * The web draws non-zone pins as labelled markers and studio zones as circles
 * on the same map (`map_components/GoogleMapComponent.jsx:1026-1067` markers,
 * `:1007-1023` zone circles); this is the slice of [MapLocation] that drawing
 * needs and nothing more.
 */
data class MapPinMarker(
    val id: String,
    val name: String,
    /** The text shown over the marker — the web labels with the type name. */
    val label: String,
    val lat: Double,
    val lng: Double,
    val isZone: Boolean = false,
    /** Circle radius when [isZone]; the page multiplies to metres. */
    val radiusMiles: Double = 0.0,
)

/** What the canvas reports back to the tool. */
sealed interface MapCanvasEvent {

    /** The map is up and drawable — pins pushed now are drawn now. */
    data object Ready : MapCanvasEvent

    /** A pin's marker was clicked (`GoogleMapComponent.jsx:1049`). */
    data class MarkerClicked(val id: String) : MapCanvasEvent

    /** Empty map clicked, proposing a pin there (`GoogleMapComponent.jsx:532`). */
    data class MapClicked(val lat: Double, val lng: Double) : MapCanvasEvent

    /**
     * The canvas cannot show a map — a rejected key (`gm_authFailure`), a
     * missing key, or no embedded browser. Human-readable; shown as-is.
     */
    data class Failed(val message: String) : MapCanvasEvent
}

/**
 * The map surface the tool draws on, kept abstract so this module never
 * touches the embedded browser: the desktop app implements it with CEF and
 * hands it in, tests hand in a fake, and a host with neither shows the
 * list-only layout.
 */
interface MapCanvasHost {

    /** Clicks and lifecycle from the canvas. */
    val events: Flow<MapCanvasEvent>

    /** Replaces every drawn marker and zone — idempotent, clear-then-set. */
    fun setPins(pins: List<MapPinMarker>)

    /** Pans and zooms, as the web does on selection (`GoogleMapComponent.jsx:587-594`). */
    fun center(lat: Double, lng: Double, zoom: Int)
}
