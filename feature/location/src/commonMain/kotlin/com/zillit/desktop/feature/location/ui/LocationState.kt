package com.zillit.desktop.feature.location.ui

import com.zillit.desktop.core.common.MapsLink
import com.zillit.desktop.core.locationpicker.mapsLink
import com.zillit.desktop.feature.location.domain.Folders
import com.zillit.desktop.feature.location.domain.LatLng
import com.zillit.desktop.feature.location.domain.GroupBy
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationFolder
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationMessage
import com.zillit.desktop.feature.location.domain.LocationPin
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationUnread
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** A folder opened over the grid: the tiles inside it, one gallery each. */
data class OpenFolder(
    val folder: LocationFolder,
    val by: GroupBy,
    val picks: List<LocationPick>,
) {
    val title: String
        get() = when (by) {
            GroupBy.LocationName -> folder.title
            GroupBy.SceneNo -> str(S.desktop_scene_numbered, folder.title)
            GroupBy.EpisodeNo -> str(S.desktop_episode_numbered, folder.title)
        }
}

/** One gallery — a (location, scene[, episode]) — and its records. */
data class OpenGallery(
    val pick: LocationPick,
    val records: List<LocationMedia> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
    val selecting: Boolean = false,
)

/**
 * Where a location is, as the form asks for it.
 *
 * The pin is the whole answer: the link saved with the record is always the
 * Google Maps link of these coordinates, never a URL somebody typed, and
 * [map] — the picture of the map around the pin — is what the record carries
 * as its `link_attachment`. Mirrors the web, which stopped keeping a free
 * link field when the Location tool took Recce's place control (PR #6521).
 *
 * [moved] is what decides whether anything place-shaped is sent at all: a
 * form opened and saved without touching the map leaves the record's pin,
 * link and picture exactly as they were.
 */
data class PlaceEditor(
    val pin: LatLng? = null,
    /** The reverse-geocoded address of [pin]; it fills an empty Address field. */
    val address: String = "",
    val moved: Boolean = false,
    /** The map picture, fetched once per pin and then saved with the record. */
    val map: ByteArray? = null,
    /** False while a picture is being fetched; true with a null [map] means there is none. */
    val mapKnown: Boolean = true,
) {
    val hasPin: Boolean get() = pin != null

    /** The canonical link for this place — what the record's `link` becomes. */
    val link: String get() = pin?.let { mapsLink(it.lat, it.long) }.orEmpty()

    /** Compared by identity for [map], which is what "a new picture arrived" means. */
    override fun equals(other: Any?): Boolean = other is PlaceEditor &&
        other.pin == pin && other.address == address && other.moved == moved &&
        other.map === map && other.mapKnown == mapKnown

    override fun hashCode(): Int {
        var result = pin?.hashCode() ?: 0
        result = 31 * result + address.hashCode()
        result = 31 * result + moved.hashCode()
        result = 31 * result + (map?.size ?: 0)
        result = 31 * result + mapKnown.hashCode()
        return result
    }
}

/** The create/edit form: metadata plus, on create, the picked file or a link. */
data class LocationEditor(
    val id: String? = null,
    val file: PickedLocationFile? = null,
    val location: String = "",
    val sceneNumber: String = "",
    val episodes: String = "",
    val city: String = "",
    val description: String = "",
    val contactName: String = "",
    val email: String = "",
    val phone: String = "",
    val countryCode: String = "",
    val address: String = "",
    val link: String = "",
    /** Where the location is — the form's map field. */
    val place: PlaceEditor = PlaceEditor(),
    /**
     * A **link entry** is being edited: a record that is only its link, so
     * its link cannot be taken away (the web's `isLinkOnlyEntry`).
     */
    val isLinkOnly: Boolean = false,
    val saving: Boolean = false,
) {
    fun toDraft(status: LocationStatus) = LocationDraft(
        location = location,
        sceneNumber = sceneNumber,
        episodes = episodes,
        city = city,
        description = description,
        contactName = contactName,
        email = email,
        phone = phone,
        countryCode = countryCode,
        address = address,
        // A pin set or moved here rewrites the link; one left alone sends an
        // empty string, which the server reads as "leave it as it is".
        link = if (place.moved) place.link else "",
        // An entry that had a link, has media of its own and has had its pin
        // cleared asks for the link to go. A link entry keeps its link
        // whatever happens — it is nothing without it.
        removeLink = place.moved && !place.hasPin && !isLinkOnly && link.isNotBlank(),
        pin = place.pin.takeIf { place.moved },
        status = status,
    )

    companion object {
        /**
         * An existing record in the form.
         *
         * The pin is the saved one, or the one read back out of a saved
         * Google Maps link for a record pinned before the coordinates were
         * stored (the web's `parseLatLngFromUrl` fallback).
         */
        fun from(record: LocationMedia) = LocationEditor(
            id = record.id,
            location = record.location,
            sceneNumber = record.sceneNumber,
            episodes = record.episodes.joinToString(","),
            city = record.city,
            description = record.description,
            contactName = record.contactName,
            email = record.email,
            phone = record.phone,
            countryCode = record.countryCode,
            address = record.address,
            link = record.link,
            place = PlaceEditor(
                pin = record.pin ?: MapsLink.parseLatLng(record.link)?.let { (lat, long) -> LatLng(lat, long) },
                address = record.address,
            ),
            isLinkOnly = record.isLink,
        )
    }
}

data class LocationUiState(
    val viewer: LocationViewer = LocationViewer(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val status: LocationStatus = LocationStatus.Selected,
    val groupBy: GroupBy = GroupBy.LocationName,
    val query: String = "",
    val info: List<LocationInfo> = emptyList(),
    /** The tile list of a folder with more than one gallery inside it. */
    val browsing: OpenFolder? = null,
    val gallery: OpenGallery? = null,
    val editor: LocationEditor? = null,
    /** The record opened full-size. */
    val viewing: LocationMedia? = null,
    val confirmDelete: List<String>? = null,
    /** The open record's discussion, oldest first. */
    val discussion: List<LocationMessage> = emptyList(),
    val discussionLoading: Boolean = false,
    val discussionDraft: String = "",
    val discussionSending: Boolean = false,
    /** The tool's unread rows — the tabs', folders', galleries' and records' badges. */
    val unread: LocationUnread = LocationUnread.None,
    /** The map of this tab's pinned locations, shown in place of the folder grid. */
    val map: MapView = MapView(),
) {
    val folders: List<LocationFolder> get() = Folders.search(Folders.group(info, groupBy), query)
}

/**
 * The tab's pinned locations on a map, in place of the folder grid.
 *
 * The web draws a live Google map with a clickable marker per place. There is
 * no live map in this client outside the Map tool's own browser pane, so this
 * is the still the rest of the desktop uses (Recce's route picture, a shared
 * pin in chat) with the places numbered beside it: the same places, in the
 * same order, and clicking one still opens its folder.
 */
data class MapView(
    val open: Boolean = false,
    val loading: Boolean = false,
    val pins: List<LocationPin> = emptyList(),
    /** The picture of the map with the pins on it; null when there is none to draw. */
    val picture: ByteArray? = null,
) {
    /** Compared by identity for [picture] — a new array is a new picture. */
    override fun equals(other: Any?): Boolean = other is MapView && other.open == open &&
        other.loading == loading && other.pins == pins && other.picture === picture

    override fun hashCode(): Int {
        var result = open.hashCode()
        result = 31 * result + loading.hashCode()
        result = 31 * result + pins.hashCode()
        result = 31 * result + (picture?.size ?: 0)
        return result
    }
}

sealed interface LocationEvent {
    data class SelectStatus(val status: LocationStatus) : LocationEvent

    /** The open record's discussion — the thread the phones and web have. */
    data class DiscussionDraftChanged(val text: String) : LocationEvent
    data object SendDiscussion : LocationEvent
    data class SelectGroupBy(val by: GroupBy) : LocationEvent
    data class Search(val query: String) : LocationEvent
    data object Refresh : LocationEvent
    data object DismissError : LocationEvent

    /** The map of this tab's pinned locations, in place of the folder grid. */
    data object ToggleMapView : LocationEvent

    /** A pin on the map view — opens the folder it belongs to. */
    data class OpenPin(val pin: LocationPin) : LocationEvent

    data class OpenFolder(val folder: LocationFolder) : LocationEvent
    data class OpenPick(val pick: LocationPick) : LocationEvent
    /** Gallery → back to the folder's tiles (or closed, if it opened straight from the grid). */
    data object Back : LocationEvent
    /** Everything over the grid closes. */
    data object CloseFolder : LocationEvent
    data object LoadMore : LocationEvent
    data object ToggleSelecting : LocationEvent
    data class ToggleSelect(val id: String) : LocationEvent
    data class MoveSelected(val to: LocationStatus) : LocationEvent
    data object DeleteSelected : LocationEvent
    data object ConfirmDelete : LocationEvent
    data object CancelDelete : LocationEvent
    data object PdfSelected : LocationEvent

    data class View(val record: LocationMedia) : LocationEvent
    data object CloseView : LocationEvent
    data class Download(val record: LocationMedia) : LocationEvent

    /** Opens a record's link, or a pin's Google Maps link, in the browser. */
    data class OpenUrl(val url: String) : LocationEvent

    /** Opens the OS picker; the host answers with [FilePicked]. */
    data object PickFile : LocationEvent
    data class FilePicked(val file: PickedLocationFile) : LocationEvent
    data object NewLink : LocationEvent
    data class Edit(val record: LocationMedia) : LocationEvent
    data class EditorChanged(val editor: LocationEditor) : LocationEvent
    data object Save : LocationEvent
    data object CancelEdit : LocationEvent
}

sealed interface LocationEffect {
    data class Notice(val text: String) : LocationEffect
    data object PickFile : LocationEffect
    data class OpenUrl(val url: String) : LocationEffect
}
