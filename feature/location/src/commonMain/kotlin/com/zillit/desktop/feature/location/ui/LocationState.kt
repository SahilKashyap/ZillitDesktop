package com.zillit.desktop.feature.location.ui

import com.zillit.desktop.feature.location.domain.Folders
import com.zillit.desktop.feature.location.domain.GroupBy
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationFolder
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationMessage
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationUnread
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.PickedLocationFile

/** A folder opened over the grid: the tiles inside it, one gallery each. */
data class OpenFolder(
    val folder: LocationFolder,
    val by: GroupBy,
    val picks: List<LocationPick>,
) {
    val title: String
        get() = when (by) {
            GroupBy.LocationName -> folder.title
            GroupBy.SceneNo -> "Scene ${folder.title}"
            GroupBy.EpisodeNo -> "Episode ${folder.title}"
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
        link = link,
        status = status,
    )

    companion object {
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
) {
    val folders: List<LocationFolder> get() = Folders.search(Folders.group(info, groupBy), query)
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
}
