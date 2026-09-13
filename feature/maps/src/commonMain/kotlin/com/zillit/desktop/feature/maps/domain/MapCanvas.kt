package com.zillit.desktop.feature.maps.domain

import kotlinx.coroutines.flow.Flow

/**
 * The map surface — Google's Maps JavaScript API in embedded Chromium.
 *
 * Kept to a transport so this module never touches the browser: the desktop
 * app implements it with CEF and hands it in, tests hand in a fake, and a
 * host with neither shows the tool without a map. Everything that knows what
 * the page says — scripts, replies, events — lives in `data/MapCanvasClient`
 * and `data/MapCanvasWire`, where it can be tested.
 *
 * The one thing the host keeps to itself is the Maps key: it answers the
 * page's own `{"type":"ready"}` by booting it, so the key never passes through
 * here.
 */
interface MapCanvasHost {

    /**
     * One-line JSON messages from the page, in arrival order. The host's own
     * failures (no embedded browser, no key) arrive here too, as
     * `{"type":"error","message":…}`.
     */
    val messages: Flow<String>

    /** Runs [script] in the page; dropped when the page does not exist yet. */
    fun execute(script: String)
}

/**
 * Everything the map draws, stated whole on every change.
 *
 * Declarative on purpose: the page diffs markers by id, so a pushed scene that
 * did not change a pin does not re-drop it, and a scene pushed to a page that
 * reloaded restores everything without a replay log.
 */
data class MapScene(
    val markers: List<SceneMarker> = emptyList(),
    /** Pin Location is on: taps drop the preview pin, saved pins can be dragged. */
    val pinMode: Boolean = false,
    /** Saved pins can be dragged — pin mode AND posting rights (req O). */
    val draggable: Boolean = false,
    /** The pin whose move is being saved; it stays put until the save answers. */
    val lockedId: String? = null,
    /** Where a dragged pin was dropped, held until the move is answered. */
    val pendingMove: ScenePin? = null,
    /** The active studio zone — at most one is drawn (req F). */
    val zone: SceneZone? = null,
    /** The zone form's circle, drawn as it is edited; its centre drags. */
    val draftZone: SceneCircle? = null,
    /** The location form's point, drawn as it is edited; it drags. */
    val draftPin: LatLng? = null,
    /** The floating Cities control; null hides it (no production loaded). */
    val cities: SceneCities? = null,
    val guide: SceneGuide = SceneGuide(),
    /** Whether the page offers Edit on its cards as an action or a request. */
    val canPost: Boolean = true,
    /** Changes to put every saved pin back where the scene says — a cancelled move. */
    val nonce: Int = 0,
)

data class SceneMarker(
    val id: String,
    val name: String,
    val type: String,
    val icon: String,
    val color: String,
    val point: LatLng,
    val address: String = "",
    val subTypes: List<String> = emptyList(),
    val sceneNumber: String = "",
    val description: String = "",
)

data class ScenePin(val id: String, val point: LatLng)

data class SceneZone(
    val id: String,
    val name: String,
    val circle: SceneCircle,
    val address: String = "",
    val type: String = "",
    /** "MG Road & Ring Road" when the centre came from an intersection. */
    val streets: String? = null,
)

data class SceneCircle(val centre: LatLng, val radiusMiles: Double)

data class SceneCities(
    val count: Int,
    val unread: Int,
    val selectedName: String?,
    val selectedCount: Int,
)

/** "How to Use the Map Module" — shown unless dismissed this session (req A). */
data class SceneGuide(val visible: Boolean = true, val collapsed: Boolean = false)

/** The app's colours, for the page's own cards and controls. Hex strings. */
data class CanvasTheme(
    val surface: String,
    val text: String,
    val textSecondary: String,
    val textMuted: String,
    val border: String,
    val accent: String,
    val isDark: Boolean,
)

/** What the page reports back. */
sealed interface MapCanvasEvent {

    /** Google's map exists; what was said before now has been restated. */
    data object Ready : MapCanvasEvent

    /** The map cannot be shown — a refused key, no key, no browser. Readable. */
    data class Failed(val message: String) : MapCanvasEvent

    /** A button on a saved pin's card. */
    data class MarkerAction(val action: MarkerActionKind, val id: String) : MapCanvasEvent

    /** "Edit Zone" on the zone circle's card. */
    data class ZoneEdit(val id: String) : MapCanvasEvent

    /** "Add Location" on the pin-mode preview card. */
    data class PreviewAdd(val point: LatLng, val name: String, val address: String) : MapCanvasEvent

    /** "Pin Location" on a Places search result. */
    data class PlacePin(val point: LatLng, val name: String, val address: String) : MapCanvasEvent

    /** "Directions" on a Places search result. */
    data class PlaceDirections(val point: LatLng, val name: String, val address: String) : MapCanvasEvent

    /** A saved pin was dropped somewhere new (req O). */
    data class MarkerDragged(val id: String, val point: LatLng) : MapCanvasEvent

    data class DraftZoneMoved(val point: LatLng) : MapCanvasEvent
    data class DraftPinMoved(val point: LatLng) : MapCanvasEvent

    /** The floating Cities control. */
    data object CitiesOpen : MapCanvasEvent

    /** The guide was collapsed, expanded, or closed on the map. */
    data class Guide(val collapsed: Boolean, val dismissed: Boolean) : MapCanvasEvent
}

enum class MarkerActionKind(val wire: String) {
    Edit("edit"),
    Share("share"),
    View("view"),
    Directions("directions"),
}

/** One Places autocomplete suggestion. */
data class PlacePrediction(
    val placeId: String,
    val description: String,
    val mainText: String,
    val secondaryText: String = "",
)

/** A chosen suggestion, resolved. */
data class PlaceDetails(val name: String, val address: String, val point: LatLng)

/** What kind of place a suggestion list offers — the web's `types` per field. */
enum class PlaceKind(val wireTypes: List<String>) {
    /** Addresses and establishments — the location form, the pickers. */
    Any(emptyList()),

    /** Add City's search (`geocode` + `establishment`). */
    CityOrPlace(listOf("geocode", "establishment")),

    /** The Cities panel search (`(cities)`). */
    Cities(listOf("(cities)")),

    /** A street for an intersection (`route`). */
    Route(listOf("route")),
}

/** A driving route's summary — `DirectionsService` leg 0. */
data class RouteInfo(
    val distanceText: String,
    val distanceMeters: Double,
    val durationText: String,
    val startAddress: String = "",
    val endAddress: String = "",
)

/** An address search's answer: where, or why not. */
sealed interface GeocodeOutcome {
    data class Found(val point: LatLng, val address: String) : GeocodeOutcome
    data class NotFound(val status: String) : GeocodeOutcome
}
