package com.zillit.desktop.feature.drive.data

import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveEditorKind
import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.domain.DriveFileText
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveTextSave
import com.zillit.desktop.feature.drive.domain.DriveActivityItem
import com.zillit.desktop.feature.drive.domain.DriveActivityPerson
import com.zillit.desktop.feature.drive.domain.DriveCellChange
import com.zillit.desktop.feature.drive.domain.DriveChangeAuthor
import com.zillit.desktop.feature.drive.domain.DriveChangeGroup
import com.zillit.desktop.feature.drive.domain.DriveChangeKind
import com.zillit.desktop.feature.drive.domain.DriveChangeStatus
import com.zillit.desktop.feature.drive.domain.DriveFileVersion
import com.zillit.desktop.feature.drive.domain.DriveGroupStatus
import com.zillit.desktop.feature.drive.domain.DriveSaveType
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveVersionActivity
import com.zillit.desktop.feature.drive.domain.DriveVersionChanges
import com.zillit.desktop.feature.drive.domain.DriveVersionHistory
import com.zillit.desktop.feature.drive.domain.DriveVersionSession
import com.zillit.desktop.feature.drive.domain.DriveZipLink
import com.zillit.desktop.feature.drive.domain.DriveShareLink
import com.zillit.desktop.feature.drive.domain.LinkPermission
import com.zillit.desktop.feature.drive.domain.LinkRecipient
import com.zillit.desktop.feature.drive.domain.EditorSession
import com.zillit.desktop.feature.drive.domain.UploadPart
import com.zillit.desktop.feature.drive.domain.UploadPlan
import com.zillit.desktop.feature.drive.domain.UploadSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The wire shapes of `/api/v2/drive`.
 *
 * ## Everything is nullable, and the id has four names
 *
 * The service is Mongo-backed: `_id` on a raw document, `id` once something has
 * passed through a `toJSON` transform, and `file_id` / `folder_id` when a row
 * is a join between two collections. The web carries a 40-line
 * `getDriveItemId` that tries fourteen candidates in order
 * (`driveItemUtils.js`) because it never established which shape it was
 * looking at. Here the endpoint decides the kind and [identifier] tries the
 * four that actually occur, in the order the server produces them.
 */
@Serializable
internal data class DriveItemDto(
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("folder_id") val folderId: String? = null,

    @SerialName("name") val name: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("folder_name") val folderName: String? = null,
    @SerialName("description") val description: String? = null,

    @SerialName("type") val type: String? = null,
    /** The trash route's discriminator — `file` or `folder`. */
    @SerialName("item_type") val itemType: String? = null,
    @SerialName("is_folder") val isFolder: Boolean? = null,

    @SerialName("parent_folder") val parentFolder: String? = null,
    @SerialName("parent_folder_id") val parentFolderId: String? = null,

    /**
     * A size, in the three spellings the routes use — and in whatever shape
     * each one sends it.
     *
     * Read as literals rather than as numbers because **`file_size` is often
     * text**: dev answers `"297.82 KB"` on `/files`, which as a declared
     * `Long` fails the whole listing (one row takes the page down with it:
     * "The server sent something unexpected"). The web is accidentally immune
     * — `Number("297.82 KB")` is `NaN`, and its `sizeBytesOf` keeps only
     * finite values above zero, which is exactly what [bytes] does here.
     */
    @SerialName("file_size_bytes") val sizeBytes: JsonPrimitive? = null,
    @SerialName("size") val size: JsonPrimitive? = null,
    @SerialName("file_size") val fileSize: JsonPrimitive? = null,
    @SerialName("file_extension") val extension: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_type") val fileType: String? = null,

    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("updated_at") val updatedAt: JsonPrimitive? = null,
    @SerialName("updated_on") val updatedOn: JsonPrimitive? = null,
    @SerialName("deleted_on") val deletedOn: JsonPrimitive? = null,

    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("uploaded_by") val uploadedBy: String? = null,
    @SerialName("uploaded_by_name") val uploadedByName: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("deleted_by_name") val deletedByName: String? = null,

    @SerialName("can_view") val canView: Boolean? = null,
    @SerialName("can_edit") val canEdit: Boolean? = null,
    @SerialName("can_download") val canDownload: Boolean? = null,
    @SerialName("can_delete") val canDelete: Boolean? = null,
    @SerialName("role") val role: String? = null,
    /**
     * What the server resolved for *this* user on this row — the web reads
     * `record._userPermissions` and falls back to ownership only when it is
     * absent. The four bare flags above are an older spelling some routes
     * still use; both are read.
     */
    @SerialName("_userPermissions") val userPermissions: UserPermissionsDto? = null,
    /**
     * Who the owner has shared this with (`_accessUserIds`) — the Sharing
     * column, and (under "Shared with me") the test of whether this person can
     * open the folder at all. Null and empty mean different things there, so
     * the absent list is kept absent rather than defaulted.
     */
    @SerialName("_accessUserIds") val accessUserIds: List<JsonElement>? = null,
    /** When this row was shared with the person asking; absent on their own rows. */
    @SerialName("_sharedAt") val sharedAt: JsonPrimitive? = null,
    @SerialName("_accessCount") val accessCount: Int? = null,
    @SerialName("folder_color") val folderColor: String? = null,

    @SerialName("is_favorite") val isFavourite: Boolean? = null,
    @SerialName("is_shared") val isShared: Boolean? = null,
    @SerialName("tag_ids") val tagIds: List<String> = emptyList(),
    @SerialName("item_count") val itemCount: Int? = null,
) {

    /**
     * The kind, decided in the order that cannot be wrong.
     *
     * The explicit flags first, the `type` discriminator second, and the
     * name-shape guess only as a last resort — that guess is the web's *first*
     * test and is why a file called with no `file_name` set has been rendered
     * as a folder.
     */
    private fun kind(default: DriveItemKind?): DriveItemKind = when {
        isFolder == true -> DriveItemKind.Folder
        isFolder == false -> DriveItemKind.File
        itemType != null -> DriveItemKind.from(itemType)
        type != null -> DriveItemKind.from(type)
        default != null -> default
        !folderName.isNullOrBlank() && fileName.isNullOrBlank() -> DriveItemKind.Folder
        else -> DriveItemKind.File
    }

    /**
     * `folder_id` is a **file's parent**, not its id, so it only names the row
     * on a folder join; a file's own id lives under `file_id` when `_id` is
     * absent.
     */
    private fun identifier(resolved: DriveItemKind): String? = listOf(
        underscoreId,
        id,
        if (resolved == DriveItemKind.Folder) folderId else fileId,
    ).firstOrNull { !it.isNullOrBlank() }

    /**
     * [known] is what the calling endpoint already established.
     *
     * `GET /folders` returns folders whatever their fields say, so the caller
     * passing that in is more reliable than any inference from the payload.
     */
    @Suppress("CyclomaticComplexMethod") // One fallback per field the wire spells two ways.
    fun toDomain(known: DriveItemKind? = null): DriveItem? {
        val resolved = kind(known)
        val itemId = identifier(resolved) ?: return null
        val displayName = listOf(name, fileName, folderName)
            .firstOrNull { !it.isNullOrBlank() }
            ?: if (resolved == DriveItemKind.Folder) str(S.drive_untitled_folder) else str(S.untitled)

        return DriveItem(
            id = itemId,
            kind = resolved,
            name = displayName,
            description = description.orEmpty(),
            // A file's parent is `folder_id` (`file.folder_id === currentFolder`
            // on the web); a folder's is `parent_folder_id`. Reading only the
            // latter left every file at the root, whatever folder it was in.
            parentFolderId = listOf(parentFolder, parentFolderId, folderId.takeIf { resolved == DriveItemKind.File })
                .firstOrNull { !it.isNullOrBlank() },
            sizeBytes = listOf(sizeBytes, size, fileSize).firstNotNullOfOrNull { it.bytes() } ?: 0,
            extension = extension.orEmpty().ifBlank {
                displayName.substringAfterLast('.', "")
            }.lowercase(),
            mimeType = mimeType ?: fileType,
            createdAt = (createdOn ?: createdAt).epochMillis(),
            updatedAt = (updatedOn ?: updatedAt).epochMillis(),
            uploadedByName = listOf(uploadedByName, createdByName)
                .firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            uploadedById = (uploadedBy ?: createdBy).orEmpty(),
            createdById = (createdBy ?: uploadedBy).orEmpty(),
            permissions = permissions(),
            hasExplicitPermissions = userPermissions != null ||
                listOf(canView, canEdit, canDownload, canDelete).any { it != null },
            isFavourite = isFavourite ?: false,
            isShared = (isShared ?: false) || (accessCount ?: 0) > 0 || !accessUserIds.isNullOrEmpty(),
            hasAccessList = accessUserIds != null,
            accessUserIds = accessUserIds.orEmpty().mapNotNull { it.identifier()?.takeIf(String::isNotBlank) },
            sharedAt = sharedAt.epochMillis(),
            tagIds = tagIds,
            itemCount = itemCount,
            folderColor = folderColor.orEmpty(),
            deletedAt = deletedOn.epochMillis(),
            deletedByName = deletedByName.orEmpty(),
        )
    }

    /**
     * The four flags, or the role they were inherited from.
     *
     * A row that carries neither is **view-only**, not owner: absence of a
     * grant is the server's way of saying "inherited nothing explicit", and
     * assuming ownership there would put a delete button on a file the delete
     * would 403 on.
     */
    private fun permissions(): DrivePermissions {
        userPermissions?.let { return it.toDomain() }
        val explicit = listOf(canView, canEdit, canDownload, canDelete).any { it != null }
        if (explicit) {
            return DrivePermissions(
                canView = canView ?: true,
                canEdit = canEdit ?: false,
                canDownload = canDownload ?: false,
                canDelete = canDelete ?: false,
            )
        }
        return role?.let { DriveRole.from(it).permissions } ?: DrivePermissions.ViewOnly
    }
}

/** `_userPermissions` — the server's resolved grant for the requesting user. */
@Serializable
internal data class UserPermissionsDto(
    @SerialName("can_view") val canView: Boolean? = null,
    @SerialName("can_edit") val canEdit: Boolean? = null,
    @SerialName("can_download") val canDownload: Boolean? = null,
    @SerialName("can_delete") val canDelete: Boolean? = null,
) {
    fun toDomain() = DrivePermissions(
        canView = canView ?: true,
        canEdit = canEdit ?: false,
        canDownload = canDownload ?: false,
        canDelete = canDelete ?: false,
    )
}

/**
 * One page of the listing.
 *
 * The service pages some routes as `{ items, total }` and others as a bare
 * array; both are read, because a route that quietly changed shape would show
 * an empty drive rather than an error.
 */
@Serializable
internal data class DrivePageDto(
    @SerialName("items") val items: List<DriveItemDto> = emptyList(),
    @SerialName("files") val files: List<DriveItemDto> = emptyList(),
    @SerialName("folders") val folders: List<DriveItemDto> = emptyList(),
    @SerialName("total") val total: Int? = null,
) {
    /**
     * Folders and files arrive under their own keys when the route splits
     * them, and merged under `items` when it does not. Both are read and
     * concatenated; a route that sends `items` sends the other two empty.
     * [known] is what the calling route established about the `items`.
     */
    fun rows(known: DriveItemKind? = null): List<DriveItem> =
        items.mapNotNull { it.toDomain(known) } +
            folders.mapNotNull { it.toDomain(DriveItemKind.Folder) } +
            files.mapNotNull { it.toDomain(DriveItemKind.File) }
}

@Serializable
internal data class UploadPartDto(
    @SerialName("part_number") val partNumber: Int? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("presigned_url") val presignedUrl: String? = null,
) {
    fun toDomain(): UploadPart? {
        val number = partNumber?.takeIf { it > 0 } ?: return null
        val target = (url ?: presignedUrl)?.takeIf { it.isNotBlank() } ?: return null
        return UploadPart(partNumber = number, url = target)
    }
}

@Serializable
internal data class UploadSessionDto(
    @SerialName("upload_id") val uploadId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    // A literal, like every other size on this service: see [bytes].
    @SerialName("file_size_bytes") val sizeBytes: JsonPrimitive? = null,
    @SerialName("chunk_size") val chunkSize: Long? = null,
    @SerialName("total_parts") val totalParts: Int? = null,
    @SerialName("presigned_urls") val presignedUrls: List<UploadPartDto> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    fun toDomain(requestedSize: Long): UploadSession? {
        val identifier = (uploadId ?: id)?.takeIf { it.isNotBlank() } ?: return null
        val parts = presignedUrls.mapNotNull { it.toDomain() }.sortedBy { it.partNumber }
        val size = sizeBytes.bytes() ?: requestedSize
        return UploadSession(
            uploadId = identifier,
            fileName = fileName.orEmpty(),
            // The server's own chunk size wins over the predicted one. They
            // should agree — see UploadPlan — but if they ever diverge, slicing
            // the file the way the URLs were signed for is what matters.
            plan = UploadPlan(
                fileSizeBytes = size,
                chunkSizeBytes = chunkSize ?: UploadPlan.chunkSizeFor(size),
                totalParts = totalParts ?: parts.size.coerceAtLeast(1),
            ),
            parts = parts,
            expiresAt = expiresAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class TagDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("color") val color: String? = null,
) {
    fun toDomain(): DriveTag? = id?.takeIf { it.isNotBlank() }?.let {
        DriveTag(id = it, name = name.orEmpty().ifBlank { str(S.desktop_drive_tag) }, color = color.orEmpty())
    }
}

/**
 * One row of `/tags/item-tags` — a **join**, not a tag.
 *
 * `_id` is the join row's own id and must never be read as the tag's: a
 * remove call carrying it finds nothing. The tag hangs off [tagId], which the
 * server sends either populated (the whole tag object) or bare (its id),
 * depending on the route — the web branches on exactly that
 * (`FileDetailsPanel.jsx:732`).
 *
 * A bare id yields a tag with no name, which the caller fills in from the
 * project's tag list.
 */
@Serializable
internal data class ItemTagDto(
    @SerialName("tag_id") val tagId: JsonElement? = null,
    /** The tagged item, on the `items-by-tag` answer. */
    @SerialName("item_id") val item: JsonElement? = null,
) {
    fun itemId(): String? = item.identifier()?.takeIf { it.isNotBlank() }

    fun toDomain(): DriveTag? = when (val value = tagId) {
        is JsonObject -> TagDto(
            id = value["_id"]?.jsonPrimitive?.contentOrNull,
            name = value["name"]?.jsonPrimitive?.contentOrNull,
            color = value["color"]?.jsonPrimitive?.contentOrNull,
        ).toDomain()

        is JsonPrimitive -> value.contentOrNull
            ?.takeIf { it.isNotBlank() }
            // No name on the wire; the caller resolves it. Blank rather than
            // TagDto's "Tag" placeholder, so an unresolved one is visibly
            // unresolved instead of quietly mislabelled.
            ?.let { DriveTag(id = it, name = "", color = "") }

        else -> null
    }
}

@Serializable
internal data class CommentDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("user_name") val userName: String? = null,
    /** The author, as an id or a populated user — the web resolves it against the crew. */
    @SerialName("user_id") val user: JsonElement? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("updated_on") val updatedOn: JsonPrimitive? = null,
    @SerialName("parent_comment_id") val parentId: String? = null,
) {
    fun toDomain(): DriveComment? = id?.takeIf { it.isNotBlank() }?.let {
        DriveComment(
            id = it,
            fileId = fileId.orEmpty(),
            authorName = userName.orEmpty().ifBlank { user.personName() },
            authorId = user.identifier().orEmpty(),
            text = (text ?: content).orEmpty(),
            createdAt = (createdOn ?: createdAt).epochMillis(),
            updatedAt = updatedOn.epochMillis(),
            parentId = parentId?.takeIf { parent -> parent.isNotBlank() },
        )
    }
}

@Serializable
internal data class ActivityDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("item_type") val itemType: String? = null,
    /**
     * The actor, as an **id** — this service does not send a name.
     *
     * The web resolves it against the crew list it already holds
     * (`ActivityLogDrawer.jsx: getUserFullName(a.user_id)`); so does this, via
     * the resolver handed to `DriveViewModel`. A row whose id is not on the
     * crew list reads "Someone" rather than a raw ObjectId.
     */
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_name") val userName: String? = null,
    /** `created_on`, not `created_at` — verified live 2026-08-11. */
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    /**
     * Free-form, and **not a string**.
     *
     * The server sends an object whose keys depend on the action — a move
     * carries source and destination, a rename carries both names. Declaring it
     * `String` threw `Expected JsonPrimitive, but had JsonObject` and took the
     * whole Activity page down with it (verified live, 2026-08-11). Kept as raw
     * JSON and flattened for display, so a shape this client has not seen costs
     * one blank cell rather than the page.
     */
    @SerialName("details") val details: JsonElement? = null,
) {
    fun toDomain(): DriveActivity? = id?.takeIf { it.isNotBlank() }?.let {
        DriveActivity(
            id = it,
            action = action.orEmpty().ifBlank { "updated" },
            itemName = itemName.orEmpty(),
            itemType = itemType.orEmpty(),
            userName = userName.orEmpty(),
            userId = userId.orEmpty(),
            at = (createdOn ?: createdAt).epochMillis(),
            detail = details.flatten(),
        )
    }
}

/**
 * A one-line rendering of whatever `details` turned out to be.
 *
 * `from: /Camera · to: /Camera/Day 12` for an object, the value itself for a
 * plain string, and nothing at all for a shape with no readable content —
 * never an exception, because this field is decoration and the row around it
 * is not.
 */
private fun JsonElement?.flatten(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> content
    is JsonObject -> entries
        .mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { "$key: $it" }
        }
        .joinToString(" · ")

    else -> ""
}

@Serializable
internal data class VersionDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("version_number") val versionNumber: Int? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size_bytes") val sizeBytes: JsonPrimitive? = null,
    @SerialName("uploaded_by_name") val uploadedByName: String? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
) {
    fun toDomain(): DriveVersion? = id?.takeIf { it.isNotBlank() }?.let {
        DriveVersion(
            id = it,
            fileId = fileId.orEmpty(),
            versionNumber = versionNumber ?: 0,
            fileName = fileName.orEmpty(),
            sizeBytes = sizeBytes.bytes() ?: 0,
            uploadedByName = uploadedByName.orEmpty(),
            createdAt = (createdOn ?: createdAt).epochMillis(),
        )
    }
}

@Serializable
internal data class AccessDto(
    /**
     * A string **or** a populated user document.
     *
     * The access routes `populate()` the user, so `user_id` arrives as
     * `{_id, full_name, …}` — declaring it `String` threw `Expected
     * JsonPrimitive, but had JsonObject` and emptied the whole share panel
     * (verified live 2026-08-11). The web reads it the same way round:
     * `user?.user_id || user?._id || user?.id`.
     *
     * The upside is that the populated form carries the name, so a share list
     * needs no crew-list lookup at all.
     */
    @SerialName("user_id") val user: JsonElement? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("can_view") val canView: Boolean? = null,
    @SerialName("can_edit") val canEdit: Boolean? = null,
    @SerialName("can_download") val canDownload: Boolean? = null,
    @SerialName("can_delete") val canDelete: Boolean? = null,
    /**
     * The row came from a folder above, not from this item.
     *
     * Folder sharing reaches everything inside it, so both access lists now
     * carry these: on a folder they are the parent's roles, on a file they are
     * the people its folder gives access to. They are the floor the share
     * panel locks — the server keeps them whatever a client sends.
     */
    @SerialName("inherited") val inherited: Boolean? = null,
    @SerialName("inherited_role") val inheritedRole: String? = null,
    @SerialName("inherited_from_folder_id") val inheritedFrom: String? = null,
) {
    fun toDomain(): DriveAccessEntry? = user.identifier()?.takeIf { it.isNotBlank() }?.let {
        val resolved = DriveRole.from(role)
        DriveAccessEntry(
            userId = it,
            userName = userName.orEmpty().ifBlank { user.personName() },
            designation = user.designation(),
            role = resolved,
            inherited = inherited == true || !inheritedRole.isNullOrBlank(),
            // `DriveRole.from` answers Viewer for anything it does not know,
            // so an absent field has to be checked before it is read — else
            // every row would come back with a Viewer floor it never had.
            inheritedRole = inheritedRole?.takeIf { role -> role.isNotBlank() }?.let(DriveRole::from),
            inheritedFromFolderId = inheritedFrom?.takeIf { id -> id.isNotBlank() },
            // File-level rows carry flags and no role; folder-level rows carry
            // a role and no flags. Whichever came, the other is derived so the
            // share panel renders one shape.
            permissions = if (listOf(canView, canEdit, canDownload, canDelete).any { f -> f != null }) {
                DrivePermissions(
                    canView = canView ?: true,
                    canEdit = canEdit ?: false,
                    canDownload = canDownload ?: false,
                    canDelete = canDelete ?: false,
                )
            } else {
                resolved.permissions
            },
        )
    }
}

/**
 * `GET /drive/editor/{id}/config` — **camelCase**, unlike every other route on
 * this service. See [EditorSession] for why, and for what the pieces are.
 */
@Serializable
internal data class EditorConfigDto(
    @SerialName("collaboraUrl") val collaboraUrl: String? = null,
    /**
     * Zillit's own editor page. The mobile contract names it `editorUrl` and
     * the web reads `liveEditorUrl`; the same server answers both spellings,
     * so both are declared rather than guessed at.
     */
    @SerialName("editorUrl") val editorUrl: String? = null,
    @SerialName("liveEditorUrl") val liveEditorUrl: String? = null,
    @SerialName("wopiSrc") val wopiSrc: String? = null,
    /**
     * The version preview route's own address for the file.
     *
     * `GET /versions/{id}/{version}/preview` answers the same config shape but
     * points at the *stored version* on the WOPI base the editor host can
     * reach, which the web prefers over `wopiSrc` for exactly that reason
     * (`liveWopiSrc || wopiSrc`, `799776305`). Absent from the live config.
     */
    @SerialName("liveWopiSrc") val liveWopiSrc: String? = null,
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("fileName") val fileName: String? = null,
    /** `sheet` / `doc` / `slide`. */
    @SerialName("editor") val editor: String? = null,
    @SerialName("_permissions") val permissions: EditorPermissionsDto? = null,
) {
    /** The Zillit editor page, if this server named one. */
    private val livePage: String?
        get() = listOf(editorUrl, liveEditorUrl).firstOrNull { !it.isNullOrBlank() }

    fun toDomain(viewOnly: Boolean): EditorSession {
        val live = livePage
        return EditorSession(
            // Discovery is cached for an hour and is empty on a cold server, so
            // the conventional path under `collaboraUrl` is the fallback — the
            // same one the web applies.
            baseUrl = live
                ?: collaboraUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}$COOL_PATH" }
                ?: "",
            wopiSrc = listOf(liveWopiSrc, wopiSrc).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            accessToken = accessToken.orEmpty(),
            fileName = fileName.orEmpty(),
            kind = DriveEditorKind.from(editor),
            // A config with no `_permissions` opens for editing: the token the
            // server minted is what decides what may be saved (the web reads
            // it the same way round).
            viewOnly = viewOnly || permissions?.canEdit == false,
            canSwitchToEdit = permissions?.canSwitchToEdit == true,
            legacyFrame = live == null,
        )
    }
}

/** `_permissions` on the editor config — camelCase, like the rest of it. */
@Serializable
internal data class EditorPermissionsDto(
    @SerialName("canEdit") val canEdit: Boolean? = null,
    /** A read-only session belonging to someone who may edit after all. */
    @SerialName("canSwitchToEdit") val canSwitchToEdit: Boolean? = null,
)

/** Collabora Online's editor page, at its conventional location. */
private const val COOL_PATH = "/browser/dist/cool.html"

/**
 * `POST /bulk/download-zip-link` — a token and what the zip will hold.
 *
 * `skipped_count` is the files the person may not download: they are left out
 * and named in a `_skipped.txt` inside the zip rather than failing the whole
 * download.
 */
@Serializable
internal data class ZipLinkDto(
    @SerialName("token") val token: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_count") val fileCount: Int? = null,
    @SerialName("skipped_count") val skippedCount: Int? = null,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int? = null,
) {
    fun toDomain(): DriveZipLink? = token?.takeIf { it.isNotBlank() }?.let {
        DriveZipLink(
            token = it,
            fileName = fileName.orEmpty(),
            fileCount = fileCount ?: 0,
            skippedCount = skippedCount ?: 0,
            expiresInSeconds = expiresInSeconds ?: 0,
        )
    }
}

/** A URL-only reply, which the download, preview, stream and share routes send. */
@Serializable
internal data class UrlDto(
    @SerialName("url") val url: String? = null,
    @SerialName("signed_url") val signedUrl: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("share_url") val shareUrl: String? = null,
    @SerialName("editor_url") val editorUrl: String? = null,
) {
    val value: String?
        get() = listOf(url, signedUrl, downloadUrl, shareUrl, editorUrl)
            .firstOrNull { !it.isNullOrBlank() }
}

/** `bulk/download-urls` answers a list of these. */
@Serializable
internal data class BulkUrlDto(
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("url") val url: String? = null,
)

/**
 * The activity listing, which is **wrapped** — `{ items, total }`.
 *
 * Verified against live dev traffic, 2026-08-11: decoding it as a bare array
 * threw `Expected JsonArray, but had JsonObject`. The web reads
 * `resp.data.items` (`ActivityLogDrawer.jsx`, `FileDetailsPanel.jsx`), which is
 * the same shape.
 *
 * Note that `/favorites/ids` on the same service is the **opposite** — a bare
 * array with no wrapper. The two are inverted, so neither can be inferred from
 * the other. See `DriveRepositoryImpl.favouriteIds`.
 */
@Serializable
internal data class ActivityPageDto(
    @SerialName("items") val items: List<ActivityDto> = emptyList(),
    @SerialName("total") val total: Int? = null,
)

/**
 * `GET /files/{id}/text` — a text file as text.
 *
 * `content_time` is a stamp the client only ever hands back, so it is read as
 * whatever the server sent it as (a date string on this route, a number on
 * others) and never parsed.
 */
@Serializable
internal data class FileTextDto(
    @SerialName("content") val content: String? = null,
    @SerialName("content_time") val contentTime: JsonPrimitive? = null,
    @SerialName("can_edit") val canEdit: Boolean? = null,
) {
    fun toDomain(): DriveFileText = DriveFileText(
        content = content.orEmpty(),
        contentTime = contentTime.stampText(),
        canEdit = canEdit ?: false,
    )
}

/** `PUT /files/{id}/text` — the new stamp, and whether a version was written. */
@Serializable
internal data class TextSaveDto(
    @SerialName("content_time") val contentTime: JsonPrimitive? = null,
    @SerialName("saved") val saved: Boolean? = null,
) {
    fun toDomain(): DriveTextSave = DriveTextSave(
        contentTime = contentTime.stampText(),
        saved = saved ?: true,
    )
}

/**
 * A size in bytes from whatever the field holds — the web's `sizeBytesOf`.
 *
 * A number, or a string that is one ("42"); null for everything else, which
 * is how `"297.82 KB"` and `""` are turned down rather than thrown on. Zero
 * is not a size either: the next spelling is asked instead, and a row with
 * none of them ends at 0, as the web does.
 */
private fun JsonPrimitive?.bytes(): Long? =
    this?.content?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }?.toLong()

/** The stamp as the server spelt it — quoted or bare, never reinterpreted. */
private fun JsonPrimitive?.stampText(): String = this?.content.orEmpty()

/**
 * Decodes the two inverted envelopes from raw JSON, for `DriveWireShapeTest`.
 *
 * These exist so the shapes can be pinned against *captured responses* rather
 * than against this file's own declarations — a test that asserts the DTO
 * matches itself would have passed on both of the bugs live traffic found.
 * Internal, and used by nothing in production: the repository decodes through
 * `ApiClient`, which applies the same serializers.
 */
internal fun decodeFavouriteIds(json: String): Set<String> =
    driveWireJson.decodeFromString(ListSerializer(String.serializer()), json)
        .filter { it.isNotBlank() }
        .toSet()

/** The zip-link reply, decoded off the envelope so a refusal keeps its message. */
internal fun decodeZipLink(data: JsonElement?): DriveZipLink? = data
    ?.let { runCatching { driveWireJson.decodeFromJsonElement(ZipLinkDto.serializer(), it) }.getOrNull() }
    ?.toDomain()

internal fun decodeActivityPage(json: String): List<DriveActivity> =
    driveWireJson.decodeFromString(ActivityPageDto.serializer(), json)
        .items
        .mapNotNull { it.toDomain() }

/** The id, whether the field is a bare string or a populated user document. */
private fun JsonElement?.identifier(): String? = when (this) {
    is JsonPrimitive -> content
    is JsonObject -> listOf("_id", "id", "user_id")
        .firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.content }

    else -> null
}

/**
 * `designation_name` off a populated user document — a label key, so it goes
 * through the dictionary, as the web's `getUserRoleName` does.
 */
private fun JsonElement?.designation(): String = (this as? JsonObject)
    ?.let { row -> (row["designation_name"] as? JsonPrimitive)?.content }
    .orEmpty()
    .localised()
    .split(' ')
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    .trim()

/** The name off a populated user document, when there is one. */
private fun JsonElement?.personName(): String = (this as? JsonObject)
    ?.let { row ->
        listOf("full_name", "name", "user_name")
            .firstNotNullOfOrNull { (row[it] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    }
    .orEmpty()

/**
 * The comments listing, which the service sends **either** wrapped as
 * `{ comments: [...] }` or as a bare array.
 *
 * Both, because the web reads both (`resp?.data?.comments || resp?.data`) and
 * live dev answered the wrapped form while the route's own name suggests the
 * bare one. Decoding only one shape throws at the top level and empties the
 * comment thread.
 */
@Serializable
internal data class CommentPageDto(
    @SerialName("comments") val comments: List<CommentDto> = emptyList(),
    @SerialName("items") val items: List<CommentDto> = emptyList(),
) {
    fun rows(): List<CommentDto> = comments + items
}

/** Reads the comments listing whichever of its two shapes arrived. */
internal object CommentsSerializer :
    JsonTransformingSerializer<CommentPageDto>(CommentPageDto.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) buildJsonObject { put("comments", element) } else element
}

/**
 * Reads a wire timestamp whichever way this service typed it.
 *
 * Epoch millis arrive as a bare JSON **number** on some rows and as a quoted
 * string on others, sometimes within one response. Declaring these `String`
 * throws `Expected JsonPrimitive, but had ...` on the number rows and takes the
 * whole listing down, so every timestamp here is raw and read through this.
 */
private fun JsonPrimitive?.epochMillis(): Long? = this?.content.toEpochMillisOrNull()

/** Matches `HttpClientFactory.json` — lenient about keys this client does not read. */
private val driveWireJson = Json { ignoreUnknownKeys = true }

/**
 * A file request as the service sends it.
 *
 * The public address comes back under more than one name depending on the
 * route (`url` from the create, `link` from the list), so both are read —
 * a request with no address is one nobody can use.
 */
@Serializable
internal data class FileRequestDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("id") val altId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("destination_folder_id") val folderId: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("link") val link: String? = null,
    @SerialName("public_url") val publicUrl: String? = null,
    /**
     * Both stamps arrive as an ISO string on this route, but the drive's
     * other routes send epoch millis — read either rather than assume.
     */
    @SerialName("expires_at") val expiresAt: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("upload_count") val uploadCount: Int? = null,
    @SerialName("revoked") val revoked: Boolean? = null,
) {
    fun toDomain(): DriveFileRequest? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: altId?.takeIf { it.isNotBlank() } ?: return null
        return DriveFileRequest(
            id = identifier,
            title = title.orEmpty().ifBlank { str(S.desktop_drive_file_request) },
            destinationFolderId = folderId.orEmpty(),
            link = listOfNotNull(url, link, publicUrl).firstOrNull { it.isNotBlank() }.orEmpty(),
            expiresAtMillis = expiresAt.stamp(),
            createdAtMillis = createdAt.stamp(),
            uploadCount = uploadCount ?: 0,
            revoked = revoked == true,
        )
    }
}

/**
 * Epoch millis from a number, a numeric string, or an ISO instant.
 *
 * The web only ever *sends* `expires_in_ms` and never renders what comes
 * back, so the response format is unattested — and the shared
 * `toEpochMillisOrNull` reads numbers only, which would turn an ISO stamp
 * into a silent zero. Both are accepted, as the invoices and cost-report
 * wires do.
 */
private fun JsonPrimitive?.stamp(): Long {
    val text = this?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return 0
    return this?.longOrNull
        ?: text.toEpochMillisOrNull()
        ?: runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
        ?: 0
}

/**
 * An email-tracked share link (`ShareViaLink.jsx`'s `ActiveLinkRow`): the
 * create answers `{ url, token, … }`, the list answers the same rows.
 */
@Serializable
internal data class ShareLinkDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("token") val token: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("permission") val permission: String? = null,
    @SerialName("expires_on") val expiresOn: JsonPrimitive? = null,
    @SerialName("max_views") val maxViews: Int? = null,
    @SerialName("view_count") val viewCount: Int? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("revoked") val revoked: Boolean? = null,
    @SerialName("recipients") val recipients: List<ShareLinkRecipientDto> = emptyList(),
) {
    fun toDomain(): DriveShareLink? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        if (revoked == true) return null
        return DriveShareLink(
            id = identifier,
            token = token.orEmpty(),
            url = url.orEmpty(),
            permission = LinkPermission.from(permission),
            expiresOn = expiresOn.stamp(),
            maxViews = maxViews ?: 0,
            viewCount = viewCount ?: 0,
            recipients = recipients.mapNotNull { it.toDomain() },
            createdOn = createdOn.stamp(),
        )
    }
}

@Serializable
internal data class ShareLinkRecipientDto(
    @SerialName("email") val email: String? = null,
    @SerialName("view_count") val viewCount: Int? = null,
) {
    fun toDomain(): LinkRecipient? =
        email?.takeIf { it.isNotBlank() }?.let { LinkRecipient(it, viewCount ?: 0) }
}

/** Both list routes, `/files` and `/folders`, answer a bare array or `{ items }`. */
internal object ItemListSerializer :
    JsonTransformingSerializer<DrivePageDto>(DrivePageDto.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) buildJsonObject { put("items", element) } else element
}

/** A bare array of ids or a wrapped one — `access/users` has answered both. */
internal object IdListSerializer :
    JsonTransformingSerializer<IdListDto>(IdListDto.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) buildJsonObject { put("items", element) } else element
}

@Serializable
internal data class IdListDto(
    @SerialName("items") val items: List<JsonElement> = emptyList(),
    @SerialName("data") val data: List<JsonElement> = emptyList(),
) {
    fun ids(): Set<String> = (items + data).mapNotNullTo(mutableSetOf()) { it.identifier()?.takeIf(String::isNotBlank) }
}

// -- version history -------------------------------------------------------

/**
 * `GET /versions/{id}/history` — the saves grouped into editing sessions,
 * with the caller's own rights on the file.
 *
 * ## `changes.sheets` is a number here and an array in the other route
 *
 * The history's per-version `changes` is a **summary**: `cells` and `sheets`
 * are counts. The same key on `GET /versions/{id}/{version}/changes` is the
 * list of sheets with their changes in it. One name, two types, so they are
 * two DTOs ([VersionChangeSummaryDto] and [VersionChangesDto]) rather than one
 * with a `JsonElement` — declaring either as the other throws at the top level
 * and empties the whole panel.
 *
 * Shapes read off the web's `versionHistory` (`d0723d0c4`); **no response
 * from this route has been captured** — see the module note.
 */
@Serializable
internal data class VersionHistoryDto(
    @SerialName("file") val file: VersionFileDto? = null,
    @SerialName("permissions") val permissions: VersionPermissionsDto? = null,
    @SerialName("sessions") val sessions: List<VersionSessionDto> = emptyList(),
    @SerialName("truncated") val truncated: Boolean? = null,
) {
    fun toDomain(): DriveVersionHistory = DriveVersionHistory(
        fileName = file?.fileName.orEmpty(),
        canEdit = permissions?.canEdit == true,
        canDownload = permissions?.canDownload == true,
        truncated = truncated == true,
        sessions = sessions.mapNotNull { it.toDomain() },
    )
}

@Serializable
internal data class VersionFileDto(
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("name") val name: String? = null,
) {
    val displayName: String get() = fileName?.takeIf { it.isNotBlank() } ?: name.orEmpty()
}

/** The caller's rights **on the file**, which is what gates naming and restoring. */
@Serializable
internal data class VersionPermissionsDto(
    @SerialName("can_edit") val canEdit: Boolean? = null,
    @SerialName("can_download") val canDownload: Boolean? = null,
)

@Serializable
internal data class VersionSessionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("started_at") val startedAt: JsonPrimitive? = null,
    @SerialName("ended_at") val endedAt: JsonPrimitive? = null,
    @SerialName("editors") val editors: List<JsonElement> = emptyList(),
    @SerialName("versions") val versions: List<VersionRowDto> = emptyList(),
) {
    /**
     * A session with no versions in it is dropped: it is a row that would
     * render as a day heading with nothing under it, and the panel's own
     * "newest version" is read off `versions[0]`.
     */
    fun toDomain(): DriveVersionSession? {
        val rows = versions.mapNotNull { it.toDomain() }
        if (rows.isEmpty()) return null
        val identifier = listOf(id, underscoreId).firstOrNull { !it.isNullOrBlank() }
        return DriveVersionSession(
            // Falls back to the newest save's id: the id is only ever a key for
            // the expand/collapse set, and a session without one would collapse
            // every other session that also lacked one.
            id = identifier ?: "session-${rows.first().id}",
            startedAt = startedAt.epochMillis() ?: rows.last().savedAt,
            endedAt = endedAt.epochMillis() ?: rows.first().savedAt,
            editorIds = editors.mapNotNull { it.identifier()?.takeIf(String::isNotBlank) },
            versions = rows,
        )
    }
}

@Serializable
internal data class VersionRowDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("id") val plainId: String? = null,
    @SerialName("version_number") val versionNumber: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("saved_at") val savedAt: JsonPrimitive? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("saved_by") val savedBy: JsonElement? = null,
    @SerialName("editors") val editors: List<JsonElement> = emptyList(),
    @SerialName("save_type") val saveType: String? = null,
    @SerialName("restored_from_number") val restoredFromNumber: Int? = null,
    @SerialName("is_current") val isCurrent: Boolean? = null,
    @SerialName("synthetic") val synthetic: Boolean? = null,
    @SerialName("changes") val changes: VersionChangeSummaryDto? = null,
) {
    fun toDomain(): DriveFileVersion? {
        val identifier = listOf(id, plainId).firstOrNull { !it.isNullOrBlank() } ?: return null
        return DriveFileVersion(
            id = identifier,
            versionNumber = versionNumber ?: 0,
            name = name.orEmpty().trim(),
            savedAt = (savedAt ?: createdOn).epochMillis() ?: 0,
            savedById = savedBy.identifier().orEmpty(),
            editorIds = editors.mapNotNull { it.identifier()?.takeIf(String::isNotBlank) },
            saveType = DriveSaveType.of(saveType),
            restoredFromNumber = restoredFromNumber,
            isCurrent = isCurrent == true,
            synthetic = synthetic == true,
            changes = changes?.toDomain(),
        )
    }
}

/** The history's own `changes`: counts and the activity, never the change list. */
@Serializable
internal data class VersionChangeSummaryDto(
    @SerialName("status") val status: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("cells") val cells: Int? = null,
    @SerialName("sheets") val sheets: Int? = null,
    @SerialName("activity") val activity: VersionActivityDto? = null,
) {
    fun toDomain(): DriveVersionChanges = DriveVersionChanges(
        status = DriveChangeStatus.of(status),
        reason = reason.orEmpty(),
        cells = cells ?: 0,
        sheets = sheets ?: 0,
        activity = activity?.toDomain(),
    )
}

/**
 * `GET /versions/{id}/{version}/changes` — the comparison with the version
 * before it, plus the activity.
 *
 * `sheets` is the group list for every kind: a document's body is one group
 * and a deck's slides are one each, which is why the domain calls them
 * [DriveChangeGroup] rather than sheets.
 */
@Serializable
internal data class VersionChangesDto(
    @SerialName("status") val status: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("truncated") val truncated: Boolean? = null,
    @SerialName("sheets") val sheets: List<VersionChangeGroupDto> = emptyList(),
    @SerialName("activity") val activity: VersionActivityDto? = null,
) {
    fun toDomain(): DriveVersionChanges {
        val groups = sheets.map { it.toDomain() }
        return DriveVersionChanges(
            status = DriveChangeStatus.of(status),
            reason = reason.orEmpty(),
            // Kept in step with the groups so one domain type serves both
            // routes: the summary's counts are what a row prints, and the
            // detail counts the same things.
            cells = total ?: groups.sumOf { it.changes.size },
            sheets = groups.size,
            total = total ?: groups.sumOf { it.changes.size },
            truncated = truncated == true,
            groups = groups,
            activity = activity?.toDomain(),
        )
    }
}

@Serializable
internal data class VersionChangeGroupDto(
    @SerialName("name") val name: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("changes") val changes: List<VersionChangeDto> = emptyList(),
) {
    fun toDomain(): DriveChangeGroup = DriveChangeGroup(
        name = name.orEmpty(),
        status = DriveGroupStatus.of(status),
        total = total ?: changes.size,
        changes = changes.map { it.toDomain() },
    )
}

/**
 * One change, in the server's short keys: `c` cell, `k` kind, `b` before,
 * `a` after, `by` the people the edit log attributed it to (last writer
 * first). A paragraph has no `c`.
 */
@Serializable
internal data class VersionChangeDto(
    @SerialName("c") val cell: String? = null,
    @SerialName("k") val kind: String? = null,
    @SerialName("b") val before: String? = null,
    @SerialName("a") val after: String? = null,
    @SerialName("by") val by: List<VersionChangeAuthorDto> = emptyList(),
) {
    fun toDomain(): DriveCellChange = DriveCellChange(
        cell = cell.orEmpty(),
        kind = DriveChangeKind.of(kind),
        before = before.orEmpty(),
        after = after.orEmpty(),
        by = by.map { it.toDomain() },
    )
}

@Serializable
internal data class VersionChangeAuthorDto(
    @SerialName("user_id") val userId: JsonElement? = null,
    @SerialName("name") val name: String? = null,
) {
    fun toDomain(): DriveChangeAuthor =
        DriveChangeAuthor(userId = userId.identifier().orEmpty(), name = name.orEmpty())
}

/**
 * The editors' own edit log, summarised by the server.
 *
 * Each item carries the server's wording in `text`, which is what the client
 * prints — a new kind of edit then reads correctly without a release here.
 */
@Serializable
internal data class VersionActivityDto(
    @SerialName("ops") val ops: Int? = null,
    @SerialName("truncated") val truncated: Boolean? = null,
    @SerialName("items") val items: List<VersionActivityItemDto> = emptyList(),
    @SerialName("people") val people: List<VersionActivityPersonDto> = emptyList(),
) {
    fun toDomain(): DriveVersionActivity = DriveVersionActivity(
        ops = ops ?: 0,
        truncated = truncated == true,
        items = items.map { it.toDomain() },
        people = people.map { it.toDomain() },
    )
}

@Serializable
internal data class VersionActivityItemDto(
    @SerialName("type") val type: String? = null,
    @SerialName("count") val count: Int? = null,
    @SerialName("text") val text: String? = null,
) {
    fun toDomain(): DriveActivityItem =
        DriveActivityItem(type = type.orEmpty(), count = count ?: 0, text = text.orEmpty().trim())
}

@Serializable
internal data class VersionActivityPersonDto(
    @SerialName("user_id") val userId: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("items") val items: List<VersionActivityItemDto> = emptyList(),
) {
    fun toDomain(): DriveActivityPerson = DriveActivityPerson(
        userId = userId.identifier().orEmpty(),
        name = name.orEmpty(),
        items = items.map { it.toDomain() },
    )
}

/** Decodes a captured history body, for `DriveVersionHistoryWireTest`. */
internal fun decodeVersionHistory(json: String): DriveVersionHistory =
    driveWireJson.decodeFromString(VersionHistoryDto.serializer(), json).toDomain()

/** Decodes a captured changes body, for `DriveVersionHistoryWireTest`. */
internal fun decodeVersionChanges(json: String): DriveVersionChanges =
    driveWireJson.decodeFromString(VersionChangesDto.serializer(), json).toDomain()

/** Decodes a captured text body, for `DriveTextDocWireTest`. */
internal fun decodeFileText(json: String): DriveFileText =
    driveWireJson.decodeFromString(FileTextDto.serializer(), json).toDomain()

/** Decodes a captured save reply, for `DriveTextDocWireTest`. */
internal fun decodeTextSave(json: String): DriveTextSave =
    driveWireJson.decodeFromString(TextSaveDto.serializer(), json).toDomain()

/**
 * Decodes one captured listing row, for the tests that pin what a row's own
 * fields mean — the three spellings of its size, and the two flags that say
 * whether a shared folder is this person's to open.
 */
internal fun decodeDriveItem(json: String, kind: DriveItemKind? = null): DriveItem? =
    driveWireJson.decodeFromString(DriveItemDto.serializer(), json).toDomain(kind)
