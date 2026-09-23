package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.feature.maps.domain.BoundaryPrompt
import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.GeoBounds
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationRules
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.PickedPhoto
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.RouteInfo
import com.zillit.desktop.feature.maps.domain.SceneGuide
import com.zillit.desktop.feature.maps.domain.ScenePin
import com.zillit.desktop.feature.maps.domain.SharePerson
import com.zillit.desktop.feature.maps.domain.ZoneRules
import com.zillit.desktop.feature.maps.domain.typeStyle

/**
 * Everything the map tool renders.
 *
 * The web keeps this across a context (cities, types, the selected city), a
 * hook per list and some forty `useState`s in `MapView`; here it is one value,
 * so a screenshot test can put the tool in any state the web can reach.
 */
data class MapUiState(
    val viewer: MapViewer = MapViewer(),

    // Data ------------------------------------------------------------------
    val cities: List<MapCity> = emptyList(),
    val citiesLoading: Boolean = false,
    /** False until the first answer — "no cities yet" must not flash while loading. */
    val citiesLoaded: Boolean = false,
    val types: List<LocationType> = emptyList(),
    val selectedCityId: String? = null,
    val locations: List<MapLocation> = emptyList(),
    val locationsLoading: Boolean = false,
    val zones: List<MapLocation> = emptyList(),
    val zonesLoading: Boolean = false,
    /** Unread map badges by city id. */
    val cityUnread: Map<String, Int> = emptyMap(),
    /** The selected city's locality box, once the map has resolved it. */
    val cityBounds: GeoBounds? = null,

    // The map ---------------------------------------------------------------
    /** Why the map cannot draw; null when it can (or there is no map). */
    val canvasError: String? = null,
    /** The zone drawn on the map and bounding pins (req F). */
    val activeZoneId: String? = null,
    val pinMode: Boolean = false,
    /** The pin whose move is saving — one at a time (req O). */
    val movingId: String? = null,
    /** Where a dragged pin was dropped, until its move is answered. */
    val pendingMove: ScenePin? = null,
    /**
     * Bumped to send a dropped pin home. Google leaves a dragged marker where
     * the pointer let go, and a scene that did not otherwise change would not
     * be restated — the web remounts its markers with a nonce for the same
     * reason.
     */
    val snapNonce: Int = 0,
    val guide: SceneGuide = SceneGuide(),

    // Toolbar panels ---------------------------------------------------------
    val search: SearchState? = null,
    val filterOpen: Boolean = false,
    /** Types shown on the map; empty shows every type. */
    val typeFilters: Set<String> = emptySet(),
    val directions: DirectionsState? = null,

    // Side panels -------------------------------------------------------------
    /** The open panel stack, top last — a zone's form returns to its list. */
    val panels: List<MapPanel> = emptyList(),
    val citiesPanel: CitiesPanelState = CitiesPanelState(),
    val zoneFilter: String = "",
    val typesPanel: TypesPanelState = TypesPanelState(),
    val locationForm: LocationFormState? = null,
    val zoneForm: ZoneFormState? = null,
    /** The full-width locations list (the web's List View drawer). */
    val listView: ListViewState? = null,

    // Dialogs ------------------------------------------------------------------
    val dialog: MapDialog? = null,
    /** The frame is asking an admin for rights on this person's behalf, over this window. */
    val frameDialogOpen: Boolean = false,
) {
    val selectedCity: MapCity? get() = cities.firstOrNull { it.id == selectedCityId }
    val activeZone: MapLocation? get() = zones.firstOrNull { it.id == activeZoneId }
    val topPanel: MapPanel? get() = panels.lastOrNull()

    /** The locations the map draws — type filters only, never the search. */
    val filteredLocations: List<MapLocation>
        get() = if (typeFilters.isEmpty()) locations else locations.filter { it.type in typeFilters }

    /** The types present in this city, sorted — the filter chips. */
    val presentTypes: List<String> get() = locations.map { it.type }.filter { it.isNotBlank() }.distinct().sorted()

    val typeCounts: Map<String, Int>
        get() = locations.filter { it.type.isNotBlank() }.groupingBy { it.type }.eachCount()

    /** Every unread map badge — the Cities control's red count. */
    val totalUnread: Int get() = cityUnread.values.sum()

    /**
     * Whether the map surface must step aside. It is a heavyweight browser
     * view that paints over every Compose pixel — a dialog under it would
     * simply not be there. The frame's own rights dialog counts too: it floats
     * over the whole window, map included.
     */
    val canvasCovered: Boolean get() = dialog != null || listView != null || frameDialogOpen

    fun location(id: String): MapLocation? = locations.firstOrNull { it.id == id }
    fun zone(id: String): MapLocation? = zones.firstOrNull { it.id == id }
    fun style(typeName: String) = typeStyle(typeName, types)
}

/** A panel beside the map. */
sealed interface MapPanel {
    /** The web's left-hand Cities drawer. */
    data object Cities : MapPanel
    data object ZoneList : MapPanel
    data object Types : MapPanel
    data object LocationForm : MapPanel
    data object ZoneForm : MapPanel
    data class LocationDetail(val locationId: String) : MapPanel
    data class ZoneDetail(val zoneId: String) : MapPanel
}

data class SearchState(val query: String = "") {
    fun results(locations: List<MapLocation>): List<MapLocation> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return locations.filter { location ->
            location.name.lowercase().contains(q) ||
                location.address.lowercase().contains(q) ||
                location.type.lowercase().contains(q)
        }
    }
}

/** One end of a route: where, and what to call it. */
data class RouteEnd(val point: LatLng, val address: String, val name: String = "")

data class DirectionsState(
    val pickup: RouteEnd? = null,
    val drop: RouteEnd? = null,
    val pickupText: String = "",
    val dropText: String = "",
    val pickupSuggestions: List<PlacePrediction> = emptyList(),
    val dropSuggestions: List<PlacePrediction> = emptyList(),
    val usingCurrentLocation: Boolean = false,
    val loading: Boolean = false,
    val route: RouteInfo? = null,
)

data class CitiesPanelState(
    val search: String = "",
    val suggestions: List<PlacePrediction> = emptyList(),
    /** Where this machine is, offered as a city to add — the web's Current Location card. */
    val currentPlace: CurrentPlace? = null,
)

/** A place resolved from this machine's position: the city's name, its address, its point. */
data class CurrentPlace(val name: String, val description: String, val point: LatLng)

data class TypesPanelState(
    val filter: String = "",
    /** The inline create/edit card at the top of the panel. */
    val form: TypeFormState? = null,
)

data class TypeFormState(
    val editId: String? = null,
    val name: String = "",
    val icon: String = "",
    val subTypes: List<String> = emptyList(),
    val newSubType: String = "",
    val iconPickerOpen: Boolean = false,
    val saving: Boolean = false,
)

/** Where the location form was opened from, which decides what it returns to. */
enum class FormOrigin { Map, List }

/** A photo chosen for a location, not yet stored. [key] identifies it on screen. */
data class NewPhoto(val key: String, val photo: PickedPhoto)

data class LocationFormState(
    val editId: String? = null,
    val origin: FormOrigin = FormOrigin.Map,
    val cityId: String = "",
    val name: String = "",
    val type: String = "",
    val customType: String = "",
    /** Insertion-ordered, as the web's array is. */
    val subTypes: List<String> = emptyList(),
    val sceneNumber: String = "",
    val description: String = "",
    val address: String = "",
    val point: LatLng? = null,
    val existing: List<MapAttachment> = emptyList(),
    val added: List<NewPhoto> = emptyList(),
    val typeSearch: String = "",
    val typeMenuOpen: Boolean = false,
    val addressSuggestions: List<PlacePrediction> = emptyList(),
    /** The address search's centre — the pin, else the city; stable while typing. */
    val anchor: LatLng? = null,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editId != null
    val isOtherType: Boolean get() = LocationRules.isOtherType(type)
    val isShootingType: Boolean get() = LocationRules.isShootingType(type)
    val mediaCount: Int get() = existing.size + added.size
}

data class ZoneFormState(
    val editId: String? = null,
    val cityId: String = "",
    val name: String = "",
    val point: LatLng? = null,
    val preset: Int = ZoneRules.PRESETS.first(),
    val useCustom: Boolean = false,
    val customRadius: String = "",
    /** Errors stay quiet until the field has been left or the form submitted. */
    val customTouched: Boolean = false,
    val mode: CenterPointType = CenterPointType.Point,
    val centerQuery: String = "",
    val centerSuggestions: List<PlacePrediction> = emptyList(),
    val street1: String = "",
    val street1Picked: Boolean = false,
    val street1Suggestions: List<PlacePrediction> = emptyList(),
    val street2: String = "",
    val street2Picked: Boolean = false,
    val street2Suggestions: List<PlacePrediction> = emptyList(),
    /** The found intersection's address; null until "Select Intersection" succeeds. */
    val intersection: String? = null,
    val finding: Boolean = false,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editId != null
    val customError: String? get() = ZoneRules.customRadiusError(useCustom, customRadius)
    val showCustomError: Boolean get() = customTouched && customError != null
    val effectiveRadius: Double get() = ZoneRules.effectiveRadius(useCustom, preset, customRadius)
}

data class ListViewState(val filter: String = ALL_TYPES) {
    companion object {
        const val ALL_TYPES = "All"
    }
}

/** At most one modal at a time. */
sealed interface MapDialog {
    data class Boundary(val prompt: BoundaryPrompt) : MapDialog

    data class Confirm(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val danger: Boolean,
        val action: ConfirmAction,
        val busy: Boolean = false,
    ) : MapDialog

    data class AddCity(val state: AddCityState) : MapDialog

    /** "New Type" from the location form's Add button. */
    data class NewType(val form: TypeFormState) : MapDialog

    /** The location form's address landed outside the city (boundary spec §5.6). */
    data class AddressOutside(val areaName: String, val pick: AddressPick) : MapDialog

    data class Share(val state: ShareState) : MapDialog

    /** A photo, enlarged. */
    data class Photo(val attachment: MapAttachment?, val added: NewPhoto?) : MapDialog
}

/** A place chosen in the location form's address search, held while its boundary is asked. */
data class AddressPick(val name: String, val address: String, val point: LatLng)

sealed interface ConfirmAction {
    data class DeleteCity(val id: String) : ConfirmAction
    data class DeleteZone(val id: String) : ConfirmAction
    data class DeleteLocation(val id: String) : ConfirmAction
    data class DeleteType(val id: String) : ConfirmAction
    data class UpdateType(val id: String, val form: TypeFormState) : ConfirmAction
}

data class AddCityState(
    val query: String = "",
    val suggestions: List<PlacePrediction> = emptyList(),
    val name: String = "",
    val description: String = "",
    val point: LatLng? = null,
    /** Opened on a place already resolved — Current Location, a search, a pin: no search field (req B). */
    val prefilled: Boolean = false,
    val saving: Boolean = false,
)

data class ShareState(
    val title: String,
    val text: String,
    val url: String,
    val people: List<SharePerson> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val sending: Boolean = false,
) {
    val visiblePeople: List<SharePerson>
        get() = query.trim().lowercase().let { q ->
            if (q.isEmpty()) {
                people
            } else {
                people.filter { it.name.lowercase().contains(q) || it.designation.lowercase().contains(q) }
            }
        }
}
