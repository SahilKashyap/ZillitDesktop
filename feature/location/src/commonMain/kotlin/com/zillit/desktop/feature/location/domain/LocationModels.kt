package com.zillit.desktop.feature.location.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The Location tool — the scouting library: photos, videos and links of
 * places, filed under a location name / scene / episode and moved through
 * three shortlists. NOT the Map (pins) tool, which shares only the word.
 */
enum class LocationStatus(val wire: String, val label: String) {
    Selected("selected", "Selected"),
    Shortlisted("shortlisted", "Shortlisted"),
    Published("published", "Published"),
    ;

    /** Where a record can still be moved to from here. */
    val movesTo: List<LocationStatus>
        get() = when (this) {
            Selected -> listOf(Shortlisted, Published)
            Shortlisted -> listOf(Published)
            Published -> emptyList()
        }

    companion object {
        fun fromWire(value: String?): LocationStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Selected
    }
}

/** How the folder grid groups the flat list. */
enum class GroupBy(val label: String) { LocationName("Location"), SceneNo("Scene"), EpisodeNo("Episode") }

/** One row of `location-info` — the folder source. camelCase on the wire, unlike everything else. */
data class LocationInfo(
    val location: String,
    val sceneNumbers: List<String>,
    val episodes: List<String>,
    val cities: List<String>,
    val lastUpdateMs: Long,
    val deleted: Boolean,
)

/**
 * One gallery: the (location, scene[, episode]) the server lists by. The web
 * never asks for a location alone — a folder with several scenes opens a
 * scene list first (ZL-16395), and only a tile of it opens the gallery.
 */
data class LocationPick(val location: String, val scene: String, val episode: String = "") {
    val title: String
        get() = buildString {
            append(location.ifBlank { "Not Assigned" })
            append(" · Sc ")
            append(scene.ifBlank { "Not Assigned" })
            if (episode.isNotBlank()) append(" · Ep $episode")
        }
}

/** A folder tile: the group key plus what it holds — computed here, never served. */
data class LocationFolder(
    val key: String,
    val locations: List<String>,
    val sceneNumbers: List<String>,
    val episodes: List<String>,
    val cities: List<String>,
    val lastUpdateMs: Long,
) {
    /** "Not Assigned" on the tile when the key is blank. */
    val title: String get() = key.ifBlank { "Not Assigned" }
}

data class MediaAttachment(
    /** The S3 key (or Box id) — never a URL on the wire. */
    val media: String,
    val thumbnail: String,
    /** `image`, `video`, `document`… */
    val contentType: String,
    val contentSubtype: String,
    val name: String,
    val bucket: String,
    val region: String,
    val fileSize: String,
) {
    val isImage: Boolean get() = contentType.equals("image", ignoreCase = true)
    val isVideo: Boolean get() = contentType.equals("video", ignoreCase = true)
}

/** One record — a photo, a video, or a link, with the place it belongs to. */
data class LocationMedia(
    val id: String,
    val location: String,
    val sceneNumber: String,
    val episodes: List<String>,
    val city: String,
    val address: String,
    val description: String,
    val contactName: String,
    val email: String,
    val phone: String,
    val countryCode: String,
    val link: String,
    val attachment: MediaAttachment?,
    /** The link's preview image, when the record is a URL. */
    val linkAttachment: MediaAttachment?,
    val status: LocationStatus,
    val uploadedBy: String,
    val createdMs: Long,
    val updatedMs: Long,
    val deleted: Boolean,
    val discussion: Boolean,
) {
    val isLink: Boolean get() = link.isNotBlank()
    val visual: MediaAttachment? get() = attachment ?: linkAttachment
    val edited: Boolean get() = updatedMs > createdMs
}

/** What the create/edit form collects. */
data class LocationDraft(
    val location: String,
    val sceneNumber: String = "",
    /** Comma-separated episode numbers, as typed. */
    val episodes: String = "",
    val city: String = "",
    val description: String = "",
    val contactName: String = "",
    val email: String = "",
    val phone: String = "",
    val countryCode: String = "",
    val address: String = "",
    val link: String = "",
    val status: LocationStatus = LocationStatus.Selected,
) {
    val episodeList: List<String> get() = episodes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** A picked file for upload. */
data class PickedLocationFile(val name: String, val contentType: String, val bytes: ByteArray) {
    val isImage: Boolean get() = contentType.startsWith("image/")
    val isVideo: Boolean get() = contentType.startsWith("video/")

    override fun equals(other: Any?): Boolean = other is PickedLocationFile && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

data class LocationViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    val isTelevision: Boolean = false,
    val ready: Boolean = false,
) {
    /**
     * Rights as issued, with no project-admin bypass.
     *
     * Android's location pages read `postingAccess` / `viewAccess` off the
     * tool's row directly and never consult `isAdmin` for either. It *is*
     * consulted for somebody else's upload — see [mayDelete] — which is a
     * different rule and stays.
     */
    val isBlocked: Boolean get() = ready && !canView
    val mayPost: Boolean get() = canPost

    /**
     * Download is the one right an admin *does* inherit.
     *
     * iOS gates every download in this tool on
     * `getLoginUserAdminAccess() || getProjectDownloadRight(LOCATION_TOOL)`
     * (`FolderDetailVC+Collection`, `LocationChatVC+Ext`) and offers to ask an
     * admin for the right otherwise. Posting is not the same question — no
     * client grants that to an admin here.
     */
    val mayDownload: Boolean get() = isAdmin || canDownload

    /** The web's gallery-delete rule: admins, or every selected item is yours. */
    fun mayDelete(uploaders: Collection<String>): Boolean = isAdmin || (uploaders.isNotEmpty() && uploaders
        .all { it == userId })

    companion object {
        const val TOOL_IDENTIFIER = "location_tool"

        fun from(permissions: ProjectPermissions, userId: String, isTelevision: Boolean): LocationViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return LocationViewer(userId = userId, isTelevision = isTelevision, ready = false)
            }
            return LocationViewer(
                userId = userId,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                isTelevision = isTelevision,
                ready = true,
            )
        }
    }
}

/**
 * One line in a location record's discussion.
 *
 * The web runs this thread through a component shared with casting and
 * wardrobe (`pages/FilmTools/casting/CastingChat.jsx`), which dispatches by
 * route to each tool's own service. Bodies travel AES-encrypted exactly as
 * chat and the notice boards do.
 */
data class LocationMessage(
    val id: String,
    val senderId: String,
    val body: String,
    val sentAtMillis: Long,
    val isMine: Boolean,
)
