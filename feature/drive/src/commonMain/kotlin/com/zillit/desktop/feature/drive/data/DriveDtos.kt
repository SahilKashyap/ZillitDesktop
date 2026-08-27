package com.zillit.desktop.feature.drive.data

import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePage
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.EditorSession
import com.zillit.desktop.feature.drive.domain.StorageUsage
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
    @SerialName("is_folder") val isFolder: Boolean? = null,

    @SerialName("parent_folder") val parentFolder: String? = null,
    @SerialName("parent_folder_id") val parentFolderId: String? = null,

    @SerialName("file_size_bytes") val sizeBytes: Long? = null,
    @SerialName("file_extension") val extension: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_type") val fileType: String? = null,

    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("updated_at") val updatedAt: JsonPrimitive? = null,
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
        type != null -> DriveItemKind.from(type)
        default != null -> default
        !folderName.isNullOrBlank() && fileName.isNullOrBlank() -> DriveItemKind.Folder
        else -> DriveItemKind.File
    }

    private val identifier: String?
        get() = listOf(underscoreId, id, fileId, folderId)
            .firstOrNull { !it.isNullOrBlank() }

    /**
     * [known] is what the calling endpoint already established.
     *
     * `GET /folders` returns folders whatever their fields say, so the caller
     * passing that in is more reliable than any inference from the payload.
     */
    fun toDomain(known: DriveItemKind? = null): DriveItem? {
        val itemId = identifier ?: return null
        val resolved = kind(known)
        val displayName = listOf(name, fileName, folderName)
            .firstOrNull { !it.isNullOrBlank() }
            ?: if (resolved == DriveItemKind.Folder) "Untitled folder" else "Untitled"

        return DriveItem(
            id = itemId,
            kind = resolved,
            name = displayName,
            description = description.orEmpty(),
            parentFolderId = (parentFolder ?: parentFolderId)?.takeIf { it.isNotBlank() },
            sizeBytes = sizeBytes ?: 0,
            extension = extension.orEmpty().ifBlank {
                displayName.substringAfterLast('.', "")
            }.lowercase(),
            mimeType = mimeType ?: fileType,
            createdAt = (createdAt ?: createdOn).epochMillis(),
            updatedAt = updatedAt.epochMillis(),
            uploadedByName = listOf(uploadedByName, createdByName)
                .firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            uploadedById = (uploadedBy ?: createdBy).orEmpty(),
            permissions = permissions(),
            isFavourite = isFavourite ?: false,
            isShared = isShared ?: false,
            tagIds = tagIds,
            itemCount = itemCount,
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
    fun toDomain(): DrivePage {
        // Folders and files arrive under their own keys when the route splits
        // them, and merged under `items` when it does not. Both are read and
        // concatenated; a route that sends `items` sends the other two empty.
        val rows = items.mapNotNull { it.toDomain() } +
            folders.mapNotNull { it.toDomain(DriveItemKind.Folder) } +
            files.mapNotNull { it.toDomain(DriveItemKind.File) }
        return DrivePage(items = rows, total = total ?: rows.size)
    }
}

@Serializable
internal data class CrumbDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("folder_name") val folderName: String? = null,
)

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
    @SerialName("file_size_bytes") val sizeBytes: Long? = null,
    @SerialName("chunk_size") val chunkSize: Long? = null,
    @SerialName("total_parts") val totalParts: Int? = null,
    @SerialName("presigned_urls") val presignedUrls: List<UploadPartDto> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    fun toDomain(requestedSize: Long): UploadSession? {
        val identifier = (uploadId ?: id)?.takeIf { it.isNotBlank() } ?: return null
        val parts = presignedUrls.mapNotNull { it.toDomain() }.sortedBy { it.partNumber }
        val size = sizeBytes ?: requestedSize
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
internal data class StorageDto(
    @SerialName("total_size_bytes") val totalBytes: Long? = null,
    @SerialName("used_bytes") val usedBytes: Long? = null,
    @SerialName("total_files") val totalFiles: Int? = null,
    @SerialName("file_count") val fileCount: Int? = null,
    @SerialName("trash_size_bytes") val trashBytes: Long? = null,
    @SerialName("quota_bytes") val quotaBytes: Long? = null,
    @SerialName("by_type") val byType: Map<String, JsonElement> = emptyMap(),
) {
    fun toDomain(): StorageUsage = StorageUsage(
        usedBytes = usedBytes ?: totalBytes ?: 0,
        fileCount = fileCount ?: totalFiles ?: 0,
        trashBytes = trashBytes ?: 0,
        byType = byType.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.content?.toLongOrNull()?.let { key to it }
        }.toMap(),
        // Zero is not a quota. A meter drawn against it reads as full, which is
        // the opposite of what "no allowance configured" means.
        quotaBytes = quotaBytes?.takeIf { it > 0 },
    )
}

@Serializable
internal data class TagDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("color") val color: String? = null,
) {
    fun toDomain(): DriveTag? = id?.takeIf { it.isNotBlank() }?.let {
        DriveTag(id = it, name = name.orEmpty().ifBlank { "Tag" }, color = color.orEmpty())
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
) {
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
    @SerialName("created_on") val createdOn: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("parent_comment_id") val parentId: String? = null,
) {
    fun toDomain(): DriveComment? = id?.takeIf { it.isNotBlank() }?.let {
        DriveComment(
            id = it,
            fileId = fileId.orEmpty(),
            authorName = userName.orEmpty().ifBlank { "Someone" },
            text = (text ?: content).orEmpty(),
            createdAt = (createdOn ?: createdAt).epochMillis(),
            parentId = parentId?.takeIf { parent -> parent.isNotBlank() },
        )
    }
}

@Serializable
internal data class ActivityDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("item_name") val itemName: String? = null,
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
    @SerialName("file_size_bytes") val sizeBytes: Long? = null,
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
            sizeBytes = sizeBytes ?: 0,
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
) {
    fun toDomain(): DriveAccessEntry? = user.identifier()?.takeIf { it.isNotBlank() }?.let {
        val resolved = DriveRole.from(role)
        DriveAccessEntry(
            userId = it,
            userName = userName.orEmpty().ifBlank { user.personName() },
            role = resolved,
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
    @SerialName("editorUrl") val editorUrl: String? = null,
    @SerialName("wopiSrc") val wopiSrc: String? = null,
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("fileName") val fileName: String? = null,
) {
    fun toDomain(): EditorSession = EditorSession(
        // Discovery is cached for an hour and is empty on a cold server, so
        // the conventional path under `collaboraUrl` is the fallback — the
        // same one the web applies.
        baseUrl = editorUrl?.takeIf { it.isNotBlank() }
            ?: collaboraUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}$COOL_PATH" }
            ?: "",
        wopiSrc = wopiSrc.orEmpty(),
        accessToken = accessToken.orEmpty(),
        fileName = fileName.orEmpty(),
    )
}

/** Collabora Online's editor page, at its conventional location. */
private const val COOL_PATH = "/browser/dist/cool.html"

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
            title = title.orEmpty().ifBlank { "File request" },
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
