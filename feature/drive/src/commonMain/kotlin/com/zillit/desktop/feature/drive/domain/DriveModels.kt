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
 * Ported from `driveItemUtils.inferPreviewType`. Kept as a domain concept
 * rather than a UI detail because the *repository* needs it too: a video asks
 * for a streaming URL and an image asks for a preview URL, and those are
 * different endpoints.
 */
enum class PreviewKind {
    Image,
    Video,
    Audio,
    Document,
    ;

    companion object {

        private val images = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif",
        )
        private val videos = setOf("mp4", "mov", "mkv", "avi", "wmv", "flv", "webm", "m4v")
        private val audio = setOf("mp3", "wav", "aac", "flac", "ogg", "m4a", "mpeg")

        /**
         * MIME first, extension second.
         *
         * That order matters: the server stores a MIME type it sniffed from the
         * bytes, and a `.dat` that is really a JPEG previews correctly only if
         * the sniffed type wins. An extension-first check gets that backwards.
         */
        fun of(mimeType: String?, fileName: String?, extension: String? = null): PreviewKind {
            val mime = mimeType.orEmpty().lowercase()
            when {
                mime.startsWith("image/") -> return Image
                mime.startsWith("video/") -> return Video
                mime.startsWith("audio/") -> return Audio
            }
            val ext = extension?.lowercase()?.trimStart('.')
                ?: fileName.orEmpty().substringAfterLast('.', "").lowercase()
            return when (ext) {
                in images -> Image
                in videos -> Video
                in audio -> Audio
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
enum class DriveRole(val wire: String, val label: String) {
    Owner("owner", "Owner"),
    Editor("editor", "Editor"),
    Viewer("viewer", "Viewer"),
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
 * One row of the drive listing — a file or a folder.
 *
 * One type rather than two because every surface treats them together: the
 * table, the grid, multi-select, bulk move, bulk delete, the trash and the
 * favourites list all hold mixed collections. Two types would mean every one
 * of those carrying a sealed `when` and a pair of parallel lists.
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
    val uploadedById: String = "",
    val permissions: DrivePermissions = DrivePermissions.ViewOnly,
    val isFavourite: Boolean = false,
    /** Shared with someone other than its owner — drives the shared indicator. */
    val isShared: Boolean = false,
    val tagIds: List<String> = emptyList(),
    /** Populated for folders the server counted; null when it did not. */
    val itemCount: Int? = null,
    /** When soft-deleted. Zero or null means live. */
    val deletedAt: Long? = null,
    /**
     * Only ever populated where a deployment sends `deleted_by_name`; the dev
     * trash route sends nothing for it, which is why no column shows it.
     */
    val deletedByName: String = "",
) {
    val isFolder: Boolean get() = kind == DriveItemKind.Folder

    val previewKind: PreviewKind get() = PreviewKind.of(mimeType, name, extension)

    /**
     * Whether this can be opened in the document editor.
     *
     * Editing is a WOPI round trip through Collabora, and only the office
     * formats it serves are editable — offering "Edit" on a PDF opens a viewer
     * that cannot save, which reads as a broken feature rather than an
     * unsupported one.
     */
    val isEditableDocument: Boolean
        get() = !isFolder && extension.lowercase() in EDITABLE

    private companion object {
        val EDITABLE = setOf(
            "docx", "xlsx", "pptx", "doc", "xls", "ppt",
            "odt", "ods", "odp", "csv", "txt", "rtf",
        )
    }
}

/** A step in the folder path, for the breadcrumb. */
data class DriveCrumb(val id: String?, val name: String)

/** What the project is using, from `GET /drive/storage`. */
data class StorageUsage(
    val usedBytes: Long = 0,
    val fileCount: Int = 0,
    val trashBytes: Long = 0,
    /** Bytes per broad file type — images, videos, documents, other. */
    val byType: Map<String, Long> = emptyMap(),
    /**
     * The allowance, when the production has one.
     *
     * Null rather than zero for "no quota": a meter drawn against a zero quota
     * reads as full, which is the opposite of what no quota means.
     */
    val quotaBytes: Long? = null,
) {
    /** 0..1 against the quota, or null when there is none to measure against. */
    val fraction: Float?
        get() = quotaBytes?.takeIf { it > 0 }?.let {
            (usedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
        }
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
    val text: String,
    val createdAt: Long? = null,
    val parentId: String? = null,
)

/** One entry of the audit trail. */
data class DriveActivity(
    val id: String,
    val action: String,
    val itemName: String,
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
    /** What the Activity table shows in its "who" column. */
    val displayName: String get() = userName.ifBlank { "Someone" }

    /** `file.deleted` → "File deleted". The server sends dotted action keys. */
    val label: String
        get() = action.replace('.', ' ').replace('_', ' ').trim()
            .replaceFirstChar { it.uppercase() }
}

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
    val role: DriveRole = DriveRole.Viewer,
    val permissions: DrivePermissions = DrivePermissions.ViewOnly,
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
