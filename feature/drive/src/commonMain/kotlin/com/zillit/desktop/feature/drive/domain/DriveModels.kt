package com.zillit.desktop.feature.drive.domain

/**
 * What kind of thing a drive row is.
 *
 * Files and folders come back from different endpoints and are merged into one
 * listing, so every row has to carry which it is — the web infers it from
 * whether `folder_name` is set and has been wrong about it (see
 * `driveItemUtils.isFolderItem`, four fallbacks deep). Here it is decided once,
 * at the data layer, by which endpoint the row came from.
 */
enum class DriveItemKind(val wire: String) {
    File("file"),
    Folder("folder"),
    ;

    companion object {
        fun from(wire: String?): DriveItemKind =
            if (wire?.lowercase() == Folder.wire) Folder else File
    }
}

/**
 * How a file previews, decided from its MIME type and extension.
 *
 * Ported from `driveItemUtils.inferPreviewType` and `DriveManagement.getPreviewType`.
 * Kept as a domain concept rather than a UI detail because the *repository*
 * needs it too: a video asks for a streaming URL and an image asks for a
 * preview URL, and those are different endpoints.
 */
enum class PreviewKind {
    Image,
    Video,
    Audio,
    Pdf,
    Text,
    Document,
    ;

    companion object {

        private val images = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "ico", "tiff", "tif",
        )
        private val videos = setOf("mp4", "mov", "mkv", "avi", "wmv", "flv", "webm", "m4v")
        private val audio = setOf("mp3", "wav", "aac", "flac", "ogg", "m4a", "mpeg")
        private val text = setOf("txt", "md", "json", "xml", "csv", "log")

        /**
         * MIME first, extension second.
         *
         * That order matters: the server stores a MIME type it sniffed from the
         * bytes, and a `.dat` that is really a JPEG previews correctly only if
         * the sniffed type wins. An extension-first check gets that backwards.
         */
        fun of(mimeType: String?, fileName: String?, extension: String? = null): PreviewKind {
            val mime = mimeType.orEmpty().lowercase()
            val byMime = when {
                mime.startsWith("image/") -> Image
                mime.startsWith("video/") -> Video
                mime.startsWith("audio/") -> Audio
                mime.contains("pdf") -> Pdf
                else -> null
            }
            if (byMime != null) return byMime
            val ext = extension?.lowercase()?.trimStart('.')?.takeIf { it.isNotBlank() }
                ?: fileName.orEmpty().substringAfterLast('.', "").lowercase()
            return when (ext) {
                in images -> Image
                in videos -> Video
                in audio -> Audio
                "pdf" -> Pdf
                in text -> Text
                else -> Document
            }
        }
    }
}

/**
 * What one person may do with one drive item.
 *
 * Four independent booleans rather than a role, because that is what the
 * server resolves down to: a folder role (owner/editor/viewer) is *inherited*
 * into these flags, an explicit file grant overrides them, and the creator
 * always keeps delete. By the time a row reaches this client the hierarchy has
 * already collapsed — modelling it as a role again here would invite a second,
 * disagreeing resolution.
 */
data class DrivePermissions(
    val canView: Boolean = true,
    val canEdit: Boolean = false,
    val canDownload: Boolean = false,
    val canDelete: Boolean = false,
) {
    /**
     * The web's "view only" test (`DriveManagement.getContextMenuItems`): a
     * row the user may look at and nothing else — typical of a share made
     * with view rights only. Such a row offers Open, Preview and Favourite and
     * suppresses everything else.
     */
    val isViewOnly: Boolean get() = canView && !canEdit && !canDownload && !canDelete

    /** The full set, which is how the server signals ownership on a shared folder. */
    val isOwnerLevel: Boolean get() = canView && canEdit && canDownload && canDelete

    companion object {
        /** What an admin, or a file's own creator, gets. */
        val Owner = DrivePermissions(
            canView = true,
            canEdit = true,
            canDownload = true,
            canDelete = true,
        )

        /**
         * What a row with no explicit grant gets.
         *
         * View-only. A row the server returned is one this person may see, but
         * nothing beyond that can be assumed — and offering a delete that then
         * 403s is worse than not offering it.
         */
        val ViewOnly = DrivePermissions()
    }
}

/** The three folder-level roles, which the server inherits down a tree. */
enum class DriveRole(val wire: String, val label: String, val description: String) {
    Owner("owner", "Owner", "Full access — view, edit, download, delete"),
    Editor("editor", "Editor", "Can view, edit, and download"),
    Viewer("viewer", "Viewer", "View only"),
    ;

    /** What this role grants once resolved onto an item. */
    val permissions: DrivePermissions
        get() = when (this) {
            Owner -> DrivePermissions.Owner
            Editor -> DrivePermissions(canView = true, canEdit = true, canDownload = true)
            Viewer -> DrivePermissions(canView = true)
        }

    companion object {
        fun from(wire: String?): DriveRole =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Viewer
    }
}

/**
 * The cumulative file permission levels the web's pickers offer
 * (`FilePermissionsPanel.FILE_PERMISSIONS`): Edit > Download > View. Each
 * collapses into the `{can_view, can_edit, can_download}` flags the server
 * takes, so no wire change is needed.
 */
enum class FileAccessLevel(val label: String, val description: String) {
    View("View", "Can view only"),
    Download("Download", "Can view and download"),
    Edit("Edit", "Can view, edit, and download"),
    ;

    val permissions: DrivePermissions
        get() = when (this) {
            View -> DrivePermissions(canView = true)
            Download -> DrivePermissions(canView = true, canDownload = true)
            Edit -> DrivePermissions(canView = true, canEdit = true, canDownload = true)
        }

    companion object {
        /** `flagsToLevel` — edit outranks download outranks view. */
        fun of(permissions: DrivePermissions): FileAccessLevel = when {
            permissions.canEdit -> Edit
            permissions.canDownload -> Download
            else -> View
        }
    }
}

/**
 * One row of the drive listing — a file or a folder.
 *
 * One type rather than two because every surface treats them together: the
 * table, the grid, multi-select, bulk move, bulk delete and the trash all hold
 * mixed collections. Two types would mean every one of those carrying a sealed
 * `when` and a pair of parallel lists.
 */
data class DriveItem(
    val id: String,
    val kind: DriveItemKind,
    val name: String,
    val description: String = "",
    /** Null at the drive root. */
    val parentFolderId: String? = null,
    val sizeBytes: Long = 0,
    val extension: String = "",
    val mimeType: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val uploadedByName: String = "",
    /** `uploaded_by`, else `created_by` — who put it here. */
    val uploadedById: String = "",
    /** `created_by` — whose item it is; the web's "shared with you" test compares this. */
    val createdById: String = "",
    val permissions: DrivePermissions = DrivePermissions.ViewOnly,
    /** Whether the server sent `_userPermissions` for this row at all. */
    val hasExplicitPermissions: Boolean = false,
    val isFavourite: Boolean = false,
    /** Shared with someone other than its owner — drives the shared indicator. */
    val isShared: Boolean = false,
    /** `_accessUserIds` — who the owner has shared it with, for the Sharing column. */
    val accessUserIds: List<String> = emptyList(),
    val tagIds: List<String> = emptyList(),
    /** Populated for folders the server counted; null when it did not. */
    val itemCount: Int? = null,
    /** A hex colour the folder was given, or blank for the accent. */
    val folderColor: String = "",
    /** When soft-deleted. Zero or null means live. */
    val deletedAt: Long? = null,
    /**
     * Only ever populated where a deployment sends `deleted_by_name`; the dev
     * trash route sends nothing for it, which is why no column shows it.
     */
    val deletedByName: String = "",
) {
    val isFolder: Boolean get() = kind == DriveItemKind.Folder

    val ref: DriveRef get() = DriveRef(id, kind)

    val previewKind: PreviewKind get() = PreviewKind.of(mimeType, name, extension)

    /** `updated_on || created_on` — the "Date Modified" column. */
    val modifiedAt: Long? get() = updatedAt ?: createdAt

    /**
     * Whether this can be opened in the document editor.
     *
     * Editing is a WOPI round trip through Collabora, and only the office
     * formats it serves are editable — offering "Edit" on a PDF opens a viewer
     * that cannot save, which reads as a broken feature rather than an
     * unsupported one. The list is the web's `ONLYOFFICE_EDITABLE_EXTENSIONS`.
     */
    val isEditableDocument: Boolean
        get() = !isFolder && extension.lowercase() in EDITABLE

    /** Who this row belongs to — the creator, else the uploader. */
    val ownerId: String get() = createdById.ifBlank { uploadedById }

    private companion object {
        val EDITABLE = setOf(
            "docx", "xlsx", "pptx", "doc", "xls", "ppt",
            "odt", "ods", "odp", "csv", "txt",
        )
    }
}

/** A step in the folder path, for the breadcrumb. Null id is the root. */
data class DriveCrumb(val id: String?, val name: String)

/** Files and folders of one scope, as the two list routes answer them together. */
data class DriveListing(
    val files: List<DriveItem> = emptyList(),
    val folders: List<DriveItem> = emptyList(),
) {
    val isEmpty: Boolean get() = files.isEmpty() && folders.isEmpty()
}

/** A project-level label that can be put on files and folders. */
data class DriveTag(
    val id: String,
    val name: String,
    /** Hex, as the picker stores it. Blank means the UI picks a colour. */
    val color: String = "",
)

/** A comment on a file, possibly a reply to another. */
data class DriveComment(
    val id: String,
    val fileId: String,
    val authorName: String,
    val authorId: String = "",
    val text: String,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val parentId: String? = null,
) {
    /** The web's "(edited)" marker: an update stamp that differs from creation. */
    val edited: Boolean get() = updatedAt != null && createdAt != null && updatedAt != createdAt
}

/** One entry of the audit trail. */
data class DriveActivity(
    val id: String,
    val action: String,
    val itemName: String,
    val itemType: String = "",
    /**
     * Blank on the wire — this service sends [userId] and nothing else.
     *
     * Filled in by the repository from the crew list, the way the web's
     * `getUserFullName(a.user_id)` does. Kept as a separate field from the id
     * so a row whose actor has left the production still renders.
     */
    val userName: String,
    val userId: String = "",
    val at: Long? = null,
    val detail: String = "",
) {
    /** What the Activity list shows in its "who" line. */
    val displayName: String get() = userName.ifBlank { "Unknown" }

    /**
     * `file_created` → "File uploaded", the web's `ACTION_LABELS`; anything
     * unlisted is de-snaked. The server sends underscore action keys.
     */
    val label: String
        get() = LABELS[action] ?: action.replace('.', ' ').replace('_', ' ').trim()
            .replaceFirstChar { it.uppercase() }

    /** The web's filter chips: which family a row belongs to. */
    val category: ActivityCategory
        get() = when {
            action.startsWith("file") -> ActivityCategory.Files
            action.startsWith("folder") -> ActivityCategory.Folders
            action.startsWith("access") -> ActivityCategory.Access
            else -> ActivityCategory.Other
        }

    private companion object {
        val LABELS = mapOf(
            "file_created" to "File uploaded",
            "file_updated" to "File updated",
            "file_deleted" to "File deleted",
            "file_moved" to "File moved",
            "file_restored" to "File restored",
            "folder_created" to "Folder created",
            "folder_updated" to "Folder updated",
            "folder_deleted" to "Folder deleted",
            "folder_moved" to "Folder moved",
            "folder_restored" to "Folder restored",
            "access_updated" to "Access updated",
            "access_inherited" to "Access inherited",
        )
    }
}

enum class ActivityCategory { Files, Folders, Access, Other }

/** One page of the activity log — `{ items, total }`. */
data class DriveActivityPage(val items: List<DriveActivity>, val total: Int)

/** A previous revision of a file, snapshotted before an edit overwrote it. */
data class DriveVersion(
    val id: String,
    val fileId: String,
    val versionNumber: Int,
    val fileName: String,
    val sizeBytes: Long = 0,
    val uploadedByName: String = "",
    val createdAt: Long? = null,
)

/** Who has access to one item, as the share panel lists it. */
data class DriveAccessEntry(
    val userId: String,
    val userName: String = "",
    val designation: String = "",
    val role: DriveRole = DriveRole.Viewer,
    val permissions: DrivePermissions = DrivePermissions.ViewOnly,
)

/** A crew member, as the share and permission pickers list them. */
data class DrivePerson(
    val id: String,
    val name: String,
    val designation: String = "",
    val avatarUrl: String? = null,
)

/** The link's grant — the web's `PERMISSION_OPTIONS`. */
enum class LinkPermission(val wire: String, val label: String) {
    View("view", "View only"),
    ViewDownload("view_download", "View + download"),
    ;

    companion object {
        fun from(wire: String?): LinkPermission = entries.firstOrNull { it.wire == wire } ?: View
    }
}

/** One person a share link was emailed to, and whether they opened it. */
data class LinkRecipient(val email: String, val viewCount: Int = 0)

/**
 * A public, email-tracked link to one file
 * (`driveShareLinkApi.js`, `ShareViaLink.jsx`).
 *
 * Distinct from [DriveRepository.shareLink], which is a bare presigned S3
 * address good for a day. This one is revocable, view-counted and can be sent
 * by the service to recipients who have no Zillit account.
 */
data class DriveShareLink(
    val id: String,
    val token: String,
    val url: String,
    val permission: LinkPermission = LinkPermission.View,
    /** Epoch millis; zero means never. */
    val expiresOn: Long = 0,
    val maxViews: Int = 0,
    val viewCount: Int = 0,
    val recipients: List<LinkRecipient> = emptyList(),
    val createdOn: Long = 0,
) {
    fun isExpired(now: Long): Boolean = expiresOn > 0 && now > expiresOn
}

/** What the share-via-link form sends. */
data class DriveShareLinkDraft(
    val recipients: List<String> = emptyList(),
    val permission: LinkPermission = LinkPermission.View,
    /** Zero means never expires. */
    val expiresInMillis: Long = 0,
    /** Zero means unlimited. */
    val maxViews: Int = 0,
    val message: String = "",
)

/**
 * An open invitation to put files into one folder.
 *
 * A production asks a supplier, a location owner or a crew member who has no
 * Zillit account to send files: the request is a public link scoped to a
 * folder, with an expiry and limits, and whatever arrives lands in the Drive.
 * The web calls this "Request files" (`components/drive/RequestFilesDrawer.jsx`).
 */
data class DriveFileRequest(
    val id: String,
    val title: String,
    val destinationFolderId: String,
    /** The public address to send out; empty once revoked. */
    val link: String = "",
    val expiresAtMillis: Long = 0,
    val createdAtMillis: Long = 0,
    val uploadCount: Int = 0,
    val revoked: Boolean = false,
)

/** What a new request asks for. Everything but the folder and title is optional. */
data class DriveFileRequestDraft(
    val destinationFolderId: String,
    val title: String,
    val description: String = "",
    val thankYouMessage: String = "",
    val expiresInMillis: Long = 0,
    val maxFilesPerSession: Int = 0,
    val maxTotalSizeBytes: Long = 0,
    val allowedMimePatterns: List<String> = emptyList(),
    val requireUploaderName: Boolean = false,
    val requireUploaderEmail: Boolean = false,
    val recipients: List<String> = emptyList(),
)

/** What the create-folder drawer sends (`handleCreateFolder`). */
data class NewFolder(
    val name: String,
    val parentId: String?,
    val description: String = "",
    val access: List<DriveAccessEntry> = emptyList(),
    val inheritToChildren: Boolean = false,
    val color: String = "",
)
