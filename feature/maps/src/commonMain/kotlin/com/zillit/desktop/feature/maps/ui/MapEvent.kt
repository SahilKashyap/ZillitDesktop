package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.feature.maps.domain.BoundaryChoice
import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.PickedPhoto
import com.zillit.desktop.feature.maps.domain.PlacePrediction

/** Everything a person can do on the map tool, grouped by where they do it. */
sealed interface MapEvent {

    /** The orange toolbar. */
    sealed interface Toolbar : MapEvent {
        data object Back : Toolbar
        data object FitAll : Toolbar
        data object ToggleSearch : Toolbar
        data object ToggleFilter : Toolbar
        data object TogglePinMode : Toolbar
        data object ToggleListView : Toolbar
        data object ToggleZoneList : Toolbar
        data object ToggleTypes : Toolbar
        data object ShowGuide : Toolbar
    }

    /** The search, filter and pin-mode bars under the toolbar. */
    sealed interface Bars : MapEvent {
        data class SearchQuery(val query: String) : Bars
        data class SearchPick(val locationId: String) : Bars
        data class ToggleTypeFilter(val type: String) : Bars
        data object ClearTypeFilters : Bars
        data object ExitPinMode : Bars
        data object DismissCanvasError : Bars
    }

    /** The Get Directions panel. */
    sealed interface Directions : MapEvent {
        data object Close : Directions
        data class PickupText(val text: String) : Directions
        data class PickupPick(val prediction: PlacePrediction) : Directions
        data object UseCurrentLocation : Directions
        data class DropText(val text: String) : Directions
        data class DropPick(val prediction: PlacePrediction) : Directions
        data object Share : Directions
        data object CallDriver : Directions
    }

    /** The Cities panel and the Add City dialog. */
    sealed interface Cities : MapEvent {
        data object Open : Cities
        data object Close : Cities
        data class Search(val text: String) : Cities
        data class SearchPick(val prediction: PlacePrediction) : Cities
        data class Select(val cityId: String) : Cities
        data object Add : Cities

        /** "Add" on the Current Location card: Add City filled in from here. */
        data object AddCurrentPlace : Cities
        data class Delete(val cityId: String) : Cities
        data class Reorder(val cityIds: List<String>) : Cities
        data class AddQuery(val text: String) : Cities
        data class AddPick(val prediction: PlacePrediction) : Cities
        data object AddSave : Cities
        data object AddCancel : Cities
    }

    /** The location form. */
    sealed interface LocationForm : MapEvent {
        data object New : LocationForm
        data class Name(val value: String) : LocationForm
        data class TypeSearch(val value: String) : LocationForm
        data class TypeMenu(val open: Boolean) : LocationForm
        data class PickType(val name: String) : LocationForm
        data class CustomType(val value: String) : LocationForm
        data class ToggleSubType(val name: String) : LocationForm
        data class SceneNumber(val value: String) : LocationForm
        data class Description(val value: String) : LocationForm
        data class AddressText(val value: String) : LocationForm
        data class AddressPick(val prediction: PlacePrediction) : LocationForm
        data object UseAddressAnyway : LocationForm
        data object BrowsePhotos : LocationForm
        data class PhotosDropped(val photos: List<PickedPhoto>, val refused: List<String>) : LocationForm
        data class RemoveExisting(val index: Int) : LocationForm
        data class RemoveAdded(val key: String) : LocationForm
        data object NewType : LocationForm
        data object Save : LocationForm
        data object Close : LocationForm
    }

    /** The locations list, a location's details, and the card actions. */
    sealed interface Locations : MapEvent {
        data class Filter(val type: String) : Locations
        data class View(val locationId: String) : Locations
        data class Edit(val locationId: String) : Locations
        data class Delete(val locationId: String) : Locations
        data class Share(val locationId: String) : Locations
        data class Directions(val locationId: String) : Locations
        data object CloseDetail : Locations
        data class CopyCoordinates(val locationId: String) : Locations
        data class OpenPhoto(val locationId: String, val index: Int) : Locations
    }

    /** The studio-zone list, form and details. */
    sealed interface Zones : MapEvent {
        data class Filter(val text: String) : Zones
        data class Activate(val zoneId: String) : Zones
        data object Clear : Zones
        data class Details(val zoneId: String) : Zones
        data class Edit(val zoneId: String) : Zones
        data class Delete(val zoneId: String) : Zones
        data object Add : Zones
        data object CloseDetail : Zones
        data class Name(val value: String) : Zones
        data class Mode(val mode: CenterPointType) : Zones
        data class CenterQuery(val text: String) : Zones
        data class CenterPick(val prediction: PlacePrediction) : Zones
        data class Street1(val text: String) : Zones
        data class Street1Pick(val prediction: PlacePrediction) : Zones
        data class Street2(val text: String) : Zones
        data class Street2Pick(val prediction: PlacePrediction) : Zones
        data object FindIntersection : Zones
        data class Preset(val miles: Int) : Zones
        data object Custom : Zones
        data class CustomRadius(val text: String) : Zones
        data object CustomRadiusLeft : Zones
        data object Save : Zones
        data object CloseForm : Zones
    }

    /** The location-types panel and its form (inline, or the New Type dialog). */
    sealed interface Types : MapEvent {
        data class Filter(val text: String) : Types
        data object New : Types
        data class Edit(val typeId: String) : Types
        data class Delete(val typeId: String) : Types
        data class Name(val value: String) : Types
        data class Icon(val icon: String) : Types
        data class IconPicker(val open: Boolean) : Types
        data class NewSubType(val value: String) : Types
        data object AddSubType : Types
        data class RemoveSubType(val index: Int) : Types
        data object Save : Types
        data object Cancel : Types
    }

    /** Modal answers. */
    sealed interface Dialogs : MapEvent {
        data class Boundary(val choice: BoundaryChoice) : Dialogs
        data object Confirm : Dialogs
        data object Dismiss : Dialogs
        data class ShareToggle(val userId: String) : Dialogs
        data class ShareQuery(val text: String) : Dialogs
        data object ShareSend : Dialogs
        data object ShareCopy : Dialogs
        data object ShareOpenMaps : Dialogs
    }
}

/** One-shot things the screen does for the view model. */
sealed interface MapEffect {
    data class Notice(val message: String, val tone: NoticeTone) : MapEffect
    data class OpenUrl(val url: String) : MapEffect
    data class Copy(val text: String) : MapEffect

    /** Leave the tool — the web's back arrow to Film Tools. */
    data object Leave : MapEffect

    /** Open another tool in its own window. */
    data class OpenTool(val path: String) : MapEffect
}

/** react-toastify's four voices. */
enum class NoticeTone { Success, Info, Warning, Error }
