package com.zillit.desktop.feature.drive.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.domain.DriveFileRequestDraft
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePage
import com.zillit.desktop.feature.drive.domain.DriveQuery
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.EditorSession
import com.zillit.desktop.feature.drive.domain.StorageUsage
import com.zillit.desktop.feature.drive.domain.UploadPart
import com.zillit.desktop.feature.drive.domain.UploadRequest
import com.zillit.desktop.feature.drive.domain.UploadSession
import com.zillit.desktop.core.socket.SocketEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Every `/api/v2/drive` route, on the drive service's own host.
 *
 * ## The paths come from the web client, not the requirements document
 *
 * `docs/Drive_Requirements.md` and `api/driveApi/driveApi.js` disagree in five
 * places, and the client is what actually runs against the deployed service:
 *
 *  | Operation        | Doc says                          | Server answers        |
 *  |------------------|-----------------------------------|-----------------------|
 *  | versions         | `/files/{id}/versions`            | `/versions/{id}`      |
 *  | file access      | `/files/{id}/access`              | `/file-access/{id}/access` |
 *  | bulk delete      | `/files/bulk-delete`              | `/bulk/delete`        |
 *  | trash restore    | `/trash/{id}/restore`             | `/trash/{type}/{id}/restore` |
 *  | favourite toggle | `POST` + `DELETE /favorites/{id}` | `/favorites/toggle`   |
 *  | file download    | `/files/{id}/download`            | `/files/{id}/stream?disposition=attachment` |
 *
 * Each of those doc paths 404s. They are transcribed here rather than left to
 * be rediscovered.
 *
 * ## Why the module header is `ProjectUser`
 *
 * Every route is scoped to one person on one production: what the listing
 * returns *is* the permission filter. The lighter `Device` header omits both
 * and the service answers 406 rather than falling back.
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface; see the interface.
class DriveRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /**
     * Names a person the service identified only by id.
     *
     * This backend sends `uploaded_by` / `created_by` / `user_id` and **no
     * name** on listings, trash, favourites and activity alike — so without
     * this every "Uploaded by" column reads "—" and every activity row reads
     * "Someone". Injected rather than fetched: the crew list is already loaded
     * per production by the session, and a second call for it here would be one
     * request per page open for data the app is holding.
     */
    private val resolveUserName: (String) -> String? = { null },
    /**
     * Which production (and which of the user's per-production ids) every
     * call is scoped to. The default is the open production — the ambient
     * headers. The Drive widget hands in a production of its own choosing,
     * so it can browse one drive while the main window is on another.
     */
    private val callOptions: () -> CallOptions = { CallOptions() },
    /** Null keeps the drive socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
) : DriveRepository {

    /**
     * See [DriveRepository.refreshes]. Conflated: a bulk delete emits one
     * event per item plus the bulk event and one refetch answers all.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(DRIVE_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()

    private val base = "${config.baseUrl(ZillitService.Drive)}/api/v2/drive"

    // -- browsing ----------------------------------------------------------

    override suspend fun contents(query: DriveQuery): ZillitResult<DrivePage> =
        get("$base/folders/contents", DrivePageDto.serializer(), query.toParameters())
            .map { dto -> dto.toDomain().let { page -> page.copy(items = page.items.named()) } }

    override suspend fun item(id: String, kind: DriveItemKind): ZillitResult<DriveItem> {
        val url = if (kind == DriveItemKind.Folder) "$base/folders/$id" else "$base/files/$id"
        return get(url, DriveItemDto.serializer()).flatMap { dto ->
            dto.toDomain(kind)?.named()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("drive item had no id"))
        }
    }

    // -- mutations ---------------------------------------------------------

    override suspend fun createFolder(
        name: String,
        parentId: String?,
        description: String,
    ): ZillitResult<DriveItem> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/folders",
        serializer = DriveItemDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            put("folder_name", JsonPrimitive(name.trim()))
            put("name", JsonPrimitive(name.trim()))
            if (description.isNotBlank()) put("description", JsonPrimitive(description.trim()))
            put("parent_folder_id", parentId.orJsonNull())
        },
    ).flatMap { dto ->
        dto.toDomain(DriveItemKind.Folder)?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("created folder had no id"))
    }

    override suspend fun rename(
        ref: DriveRef,
        name: String,
        description: String?,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Put,
        "${collection(ref.kind)}/${ref.id}",
        buildJsonObject {
            put("name", JsonPrimitive(name.trim()))
            // Only when supplied. The endpoint clears a field it is sent as an
            // empty string, so a rename that always writes description wipes a
            // description the user did not touch.
            description?.let { put("description", JsonPrimitive(it.trim())) }
        },
    )

    override suspend fun move(ref: DriveRef, targetFolderId: String?): ZillitResult<Unit> = mutate(
        HttpVerb.Put,
        "${collection(ref.kind)}/${ref.id}/move",
        buildJsonObject { put("target_folder_id", targetFolderId.orJsonNull()) },
    )

    override suspend fun delete(ref: DriveRef): ZillitResult<Unit> {
        // `force=false` keeps a folder delete a *soft* delete that cascades to
        // its contents. Omitting it is fine today, but the flag is what stands
        // between this and a hard delete if the server's default ever changes.
        val url = if (ref.kind == DriveItemKind.Folder) {
            "$base/folders/${ref.id}?force=false"
        } else {
            "$base/files/${ref.id}"
        }
        return mutate(HttpVerb.Delete, url, null)
    }

    override suspend fun bulkDelete(refs: List<DriveRef>): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/bulk/delete",
        buildJsonObject { put("items", refs.toJsonArray()) },
    )

    override suspend fun bulkMove(
        refs: List<DriveRef>,
        targetFolderId: String?,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/bulk/move",
        buildJsonObject {
            put("items", refs.toJsonArray())
            put("target_folder_id", targetFolderId.orJsonNull())
        },
    )

    override suspend fun bulkDownloadUrls(fileIds: List<String>): ZillitResult<List<String>> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/bulk/download-urls",
            serializer = ListSerializer(BulkUrlDto.serializer()),
            module = RequestModule.ProjectUser,
            options = callOptions(),
            body = buildJsonObject { put("file_ids", fileIds.toJsonArray()) },
        ).map { rows -> rows.mapNotNull { it.url?.takeIf(String::isNotBlank) } }

    // -- reading a file ----------------------------------------------------

    /**
     * A presigned URL the browser will **save** rather than render.
     *
     * There is no `/download` route — `docs/Drive_Requirements.md` API-06
     * invents one and it 404s (verified live 2026-08-11). A single-file
     * download is the *stream* route asked for an attachment disposition,
     * which is what puts `Content-Disposition: attachment` on S3's response;
     * without it the browser renders text and PDFs inline and nothing is saved.
     * The web does the same:
     * `getFileStreamUrlForDrive(id, { disposition: 'attachment' })`.
     */
    override suspend fun downloadUrl(fileId: String): ZillitResult<String> =
        url("$base/files/$fileId/stream", mapOf("disposition" to "attachment"))

    /** A presigned URL for viewing only — needs view rights, not download. */
    override suspend fun previewUrl(fileId: String): ZillitResult<String> =
        url("$base/files/$fileId/preview")

    /** The same route, inline — for seeking through video and audio. */
    override suspend fun streamUrl(fileId: String): ZillitResult<String> =
        url("$base/files/$fileId/stream")

    override suspend fun shareLink(fileId: String): ZillitResult<String> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/files/$fileId/share-link",
        serializer = UrlDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        // The server fixes the expiry at 24 hours; sending it makes the
        // client's intent explicit and survives a future default change.
        body = buildJsonObject { put("expiry", JsonPrimitive(SHARE_EXPIRY)) },
    ).flatMap { it.required("share link") }

    /** `GET /v2/drive/folders/{id}/file-requests` — what is open on a folder. */
    override suspend fun fileRequests(folderId: String): ZillitResult<List<DriveFileRequest>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/folders/$folderId/file-requests",
            serializer = ListSerializer(FileRequestDto.serializer()),
            module = RequestModule.ProjectUser,
            options = callOptions(),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * `POST /v2/drive/file-requests` (`RequestFilesDrawer.jsx:179-193`).
     *
     * Optional fields are omitted rather than sent empty: the web sends
     * `undefined` for a blank description, and this service treats an empty
     * string as a value rather than as absence.
     */
    override suspend fun createFileRequest(
        draft: DriveFileRequestDraft,
    ): ZillitResult<DriveFileRequest> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/file-requests",
        serializer = FileRequestDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            put("destination_folder_id", JsonPrimitive(draft.destinationFolderId))
            put("title", JsonPrimitive(draft.title.trim()))
            draft.description.trim().takeIf { it.isNotBlank() }
                ?.let { put("description", JsonPrimitive(it)) }
            draft.thankYouMessage.trim().takeIf { it.isNotBlank() }
                ?.let { put("thank_you_message", JsonPrimitive(it)) }
            if (draft.expiresInMillis > 0) put("expires_in_ms", JsonPrimitive(draft.expiresInMillis))
            if (draft.maxFilesPerSession > 0) {
                put("max_files_per_session", JsonPrimitive(draft.maxFilesPerSession))
            }
            if (draft.maxTotalSizeBytes > 0) {
                put("max_total_size_bytes", JsonPrimitive(draft.maxTotalSizeBytes))
            }
            put("allowed_mime_patterns", draft.allowedMimePatterns.toJsonArray())
            put("require_uploader_email", JsonPrimitive(draft.requireUploaderEmail))
            put("require_uploader_name", JsonPrimitive(draft.requireUploaderName))
            put("recipients", draft.recipients.toJsonArray())
        },
    ).flatMap { dto ->
        dto.toDomain()
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(
                ZillitError.Http(status = 200, serverMessage = "the request was made but came back empty"),
            )
    }

    /** `POST /v2/drive/file-requests/{id}/revoke` — closed for good. */
    override suspend fun revokeFileRequest(requestId: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/file-requests/$requestId/revoke",
        module = RequestModule.ProjectUser,
        options = callOptions(),
    ).map { }

    /**
     * The address to load, assembled from the pieces the config returns.
     *
     * There is no endpoint that hands back a ready URL — see [EditorSession].
     * A config missing any of its three parts is a failure rather than a
     * half-built address, because Collabora answers one of those with its own
     * error page and the user reads that as the document being broken.
     */
    override suspend fun editorUrl(fileId: String, editable: Boolean): ZillitResult<String> = get(
        "$base/editor/$fileId/config",
        EditorConfigDto.serializer(),
        mapOf("mode" to if (editable) "edit" else "view"),
    ).flatMap { dto ->
        val session = dto.toDomain()
        if (session.isUsable) {
            ZillitResult.Success(session.address())
        } else {
            ZillitResult.Failure(
                ZillitError.Serialization("the editor configuration was incomplete"),
            )
        }
    }

    // -- uploads -----------------------------------------------------------

    override suspend fun initiateUpload(request: UploadRequest): ZillitResult<UploadSession> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/uploads",
            serializer = UploadSessionDto.serializer(),
            module = RequestModule.ProjectUser,
            options = callOptions(),
            body = buildJsonObject {
                put("file_name", JsonPrimitive(request.fileName))
                put("file_size_bytes", JsonPrimitive(request.sizeBytes))
                put("mime_type", JsonPrimitive(request.mimeType))
                put("folder_id", request.folderId.orJsonNull())
                if (request.description.isNotBlank()) {
                    put("description", JsonPrimitive(request.description))
                }
            },
        ).flatMap { dto ->
            dto.toDomain(request.sizeBytes)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("upload session had no id"))
        }

    override suspend fun completeUpload(
        uploadId: String,
        parts: List<UploadPart>,
    ): ZillitResult<DriveItem> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/uploads/$uploadId/complete",
        serializer = DriveItemDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            put(
                "parts",
                buildJsonArray {
                    // Sorted, because S3 assembles in the order it is given and
                    // an out-of-order part list produces a corrupt object that
                    // completes without complaint.
                    parts.sortedBy { it.partNumber }.forEach { part ->
                        add(
                            buildJsonObject {
                                put("part_number", JsonPrimitive(part.partNumber))
                                put("etag", JsonPrimitive(part.etag.orEmpty()))
                            },
                        )
                    }
                },
            )
        },
    ).flatMap { dto ->
        dto.toDomain(DriveItemKind.File)?.named()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("completed upload had no file"))
    }

    override suspend fun abortUpload(uploadId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/uploads/$uploadId", null)

    override suspend fun remainingParts(uploadId: String): ZillitResult<List<UploadPart>> =
        get("$base/uploads/$uploadId/parts", ListSerializer(UploadPartDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    // -- trash -------------------------------------------------------------

    override suspend fun trash(): ZillitResult<List<DriveItem>> =
        get("$base/trash", DrivePageDto.serializer()).map { it.toDomain().items.named() }

    override suspend fun restore(ref: DriveRef): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/trash/${ref.kind.wire}/${ref.id}/restore",
        null,
    )

    override suspend fun purge(ref: DriveRef): ZillitResult<Unit> = mutate(
        HttpVerb.Delete,
        "$base/trash/${ref.kind.wire}/${ref.id}",
        null,
    )

    override suspend fun emptyTrash(): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/trash", null)

    // -- favourites --------------------------------------------------------

    override suspend fun toggleFavourite(ref: DriveRef): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/favorites/toggle",
        buildJsonObject {
            put("item_id", JsonPrimitive(ref.id))
            put("item_type", JsonPrimitive(ref.kind.wire))
        },
    )

    override suspend fun favourites(): ZillitResult<List<DriveItem>> =
        get("$base/favorites", DrivePageDto.serializer()).map { it.toDomain().items.named() }

    /**
     * A **bare array** of ids, with no wrapper object.
     *
     * Verified against live dev traffic, 2026-08-11 — and note that
     * `/drive/activity` on this same service is wrapped as `{ items, total }`.
     * The two are inverted, so neither shape can be inferred from the other.
     */
    override suspend fun favouriteIds(): ZillitResult<Set<String>> =
        get("$base/favorites/ids", ListSerializer(String.serializer()))
            .map { ids -> ids.filter { it.isNotBlank() }.toSet() }

    // -- sharing -----------------------------------------------------------

    override suspend fun access(ref: DriveRef): ZillitResult<List<DriveAccessEntry>> {
        // Two different route shapes for the same idea — folders under
        // `/folders/{id}/access`, files under `/file-access/{id}/access`.
        val url = if (ref.kind == DriveItemKind.Folder) {
            "$base/folders/${ref.id}/access"
        } else {
            "$base/file-access/${ref.id}/access"
        }
        return get(url, ListSerializer(AccessDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }
    }

    override suspend fun updateAccess(
        ref: DriveRef,
        entries: List<DriveAccessEntry>,
        applyToChildren: Boolean,
    ): ZillitResult<Unit> {
        val folder = ref.kind == DriveItemKind.Folder
        val url = if (folder) {
            "$base/folders/${ref.id}/access"
        } else {
            "$base/file-access/${ref.id}/access"
        }
        val result = mutate(
            HttpVerb.Put,
            url,
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("user_id", JsonPrimitive(entry.userId))
                                    if (folder) {
                                        put("role", JsonPrimitive(entry.role.wire))
                                    } else {
                                        put("can_view", JsonPrimitive(entry.permissions.canView))
                                        put("can_edit", JsonPrimitive(entry.permissions.canEdit))
                                        put(
                                            "can_download",
                                            JsonPrimitive(entry.permissions.canDownload),
                                        )
                                        put(
                                            "can_delete",
                                            JsonPrimitive(entry.permissions.canDelete),
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
                // The endpoint replaces rather than patches — an entry left out
                // is revoked. Stated on the wire as well as in the interface
                // doc, because the default here is not obvious from the name.
                put("replace_existing", JsonPrimitive(true))
            },
        )
        // Inheritance is a second call, and deliberately after: it walks every
        // descendant, so firing it against access that then failed to save
        // would push the *old* roles down the tree.
        if (result is ZillitResult.Success && folder && applyToChildren) {
            return mutate(
                HttpVerb.Post,
                "$base/folders/${ref.id}/access/inherit",
                buildJsonObject { put("trigger", JsonPrimitive(true)) },
            )
        }
        return result
    }

    // -- metadata ----------------------------------------------------------

    override suspend fun storage(): ZillitResult<StorageUsage> =
        get("$base/storage", StorageDto.serializer()).map { it.toDomain() }

    override suspend fun activity(itemId: String?): ZillitResult<List<DriveActivity>> = get(
        "$base/activity",
        ActivityPageDto.serializer(),
        itemId?.let { mapOf("item_id" to it) }.orEmpty(),
    ).map { page ->
        page.items.mapNotNull { row ->
            // The name is filled in here rather than in the DTO: the crew list
            // is the caller's, and a DTO that reached for it would put a
            // session dependency inside the wire mapping.
            row.toDomain()?.let { entry ->
                if (entry.userName.isNotBlank()) {
                    entry
                } else {
                    entry.copy(userName = resolveUserName(entry.userId).orEmpty())
                }
            }
        }
    }

    override suspend fun comments(fileId: String): ZillitResult<List<DriveComment>> = get(
        "$base/comments",
        CommentsSerializer,
        mapOf("file_id" to fileId),
    ).map { page -> page.rows().mapNotNull { it.toDomain() } }

    override suspend fun addComment(
        fileId: String,
        text: String,
        parentId: String?,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/comments",
        buildJsonObject {
            put("file_id", JsonPrimitive(fileId))
            put("text", JsonPrimitive(text.trim()))
            parentId?.let { put("parent_comment_id", JsonPrimitive(it)) }
        },
    )

    override suspend fun deleteComment(commentId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/comments/$commentId", null)

    override suspend fun tags(): ZillitResult<List<DriveTag>> =
        get("$base/tags", ListSerializer(TagDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createTag(name: String, color: String): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/tags",
        buildJsonObject {
            put("name", JsonPrimitive(name.trim()))
            if (color.isNotBlank()) put("color", JsonPrimitive(color))
        },
    )

    override suspend fun deleteTag(tagId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$base/tags/$tagId", null)

    /**
     * The rows here are joins, not tags — see [ItemTagDto]. A row whose
     * `tag_id` arrived bare has no name, so the project list fills it in;
     * that list is already loaded and costs nothing to consult.
     */
    override suspend fun itemTags(ref: DriveRef): ZillitResult<List<DriveTag>> =
        get(
            "$base/tags/item-tags",
            ListSerializer(ItemTagDto.serializer()),
            mapOf("item_id" to ref.id, "item_type" to ref.kind.wire),
        ).flatMap { rows ->
            val applied = rows.mapNotNull { it.toDomain() }
            if (applied.none { it.name.isBlank() }) {
                ZillitResult.Success(applied)
            } else {
                named(applied)
            }
        }

    /** Fills blank names from the production's tag list, leaving the ids alone. */
    private suspend fun named(applied: List<DriveTag>): ZillitResult<List<DriveTag>> =
        when (val all = tags()) {
            is ZillitResult.Failure -> ZillitResult.Success(applied)
            is ZillitResult.Success -> {
                val byId = all.data.associateBy { it.id }
                ZillitResult.Success(
                    applied.map { tag ->
                        if (tag.name.isNotBlank()) tag else byId[tag.id] ?: tag
                    },
                )
            }
        }

    override suspend fun assignTag(tagId: String, ref: DriveRef): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/tags/assign", tagBody(tagId, ref))

    override suspend fun removeTag(tagId: String, ref: DriveRef): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/tags/remove", tagBody(tagId, ref))

    // -- versions ----------------------------------------------------------

    override suspend fun versions(fileId: String): ZillitResult<List<DriveVersion>> =
        get("$base/versions/$fileId", ListSerializer(VersionDto.serializer()))
            // Newest first regardless of what the server returned: the panel
            // shows "current, then what it replaced", and a list that arrives
            // oldest-first reads as the history running backwards.
            .map { rows -> rows.mapNotNull { it.toDomain() }.sortedByDescending { it.versionNumber } }

    override suspend fun versionDownloadUrl(
        fileId: String,
        versionId: String,
    ): ZillitResult<String> = url("$base/versions/$fileId/$versionId/download")

    override suspend fun restoreVersion(
        fileId: String,
        versionId: String,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Post,
        "$base/versions/$fileId/$versionId/restore",
        null,
    )

    // -- plumbing ----------------------------------------------------------

    /**
     * Fills in the uploader's name from the crew list when the wire carried
     * only an id, which is the usual case on every listing.
     *
     * A name the server *did* send always wins — an external uploader is not on
     * the crew list and their name would otherwise be replaced with a blank.
     */
    private fun DriveItem.named(): DriveItem =
        if (uploadedByName.isNotBlank() || uploadedById.isBlank()) {
            this
        } else {
            copy(uploadedByName = resolveUserName(uploadedById).orEmpty())
        }

    private fun List<DriveItem>.named(): List<DriveItem> = map { it.named() }

    private fun collection(kind: DriveItemKind): String =
        if (kind == DriveItemKind.Folder) "$base/folders" else "$base/files"

    private fun tagBody(tagId: String, ref: DriveRef): JsonObject = buildJsonObject {
        put("tag_id", JsonPrimitive(tagId))
        put("item_id", JsonPrimitive(ref.id))
        put("item_type", JsonPrimitive(ref.kind.wire))
    }

    private suspend fun <T> get(
        url: String,
        serializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<T> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = serializer,
        module = RequestModule.ProjectUser,
        options = callOptions(),
        queryParameters = query,
    )

    private suspend fun url(
        endpoint: String,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<String> =
        get(endpoint, UrlDto.serializer(), query).flatMap { it.required("file") }

    /**
     * A mutation whose response body this client does not read.
     *
     * Returns `Unit` rather than the updated row on purpose: these endpoints
     * answer with differently-shaped envelopes depending on the operation, and
     * every screen refetches afterwards anyway. Decoding a body nobody reads is
     * a parse failure waiting to be reported as a failed delete that in fact
     * succeeded.
     */
    private inline fun <T, R> ZillitResult<T>.flatMap(
        transform: (T) -> ZillitResult<R>,
    ): ZillitResult<R> = when (this) {
        is ZillitResult.Success -> transform(data)
        is ZillitResult.Failure -> ZillitResult.Failure(error)
    }

    private suspend fun mutate(
        verb: HttpVerb,
        path: String,
        body: JsonObject?,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = verb,
        url = path,
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = body,
    ).map { }

    private companion object {
        const val SHARE_EXPIRY = "24h"
    }
}

/** A URL reply with nothing in it is a failure, not an empty string. */
private fun UrlDto.required(what: String): ZillitResult<String> =
    value?.let { ZillitResult.Success(it) }
        ?: ZillitResult.Failure(ZillitError.Serialization("no $what URL in the response"))

/**
 * `null` written explicitly rather than omitted.
 *
 * The move and create routes read an *absent* `target_folder_id` as "leave it
 * where it is" and an explicit null as "move it to the root". Omitting the key
 * is how a move-to-root silently does nothing.
 */
private fun String?.orJsonNull() = this?.let(::JsonPrimitive) ?: JsonNull

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { add(JsonPrimitive(it)) }
}

@JvmName("refsToJsonArray")
private fun List<DriveRef>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { ref ->
        add(
            buildJsonObject {
                put("id", JsonPrimitive(ref.id))
                put("type", JsonPrimitive(ref.kind.wire))
            },
        )
    }
}
