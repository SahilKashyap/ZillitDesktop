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
import com.zillit.desktop.core.network.TextBody
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.domain.DriveFileRequestDraft
import com.zillit.desktop.feature.drive.domain.DriveFileText
import com.zillit.desktop.feature.drive.domain.DriveTextSave
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveActivityPage
import com.zillit.desktop.feature.drive.domain.DriveListQuery
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DriveShareLink
import com.zillit.desktop.feature.drive.domain.DriveShareLinkDraft
import com.zillit.desktop.feature.drive.domain.NewFileType
import com.zillit.desktop.feature.drive.domain.NewFolder
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveVersionChanges
import com.zillit.desktop.feature.drive.domain.DriveVersionHistory
import com.zillit.desktop.feature.drive.domain.DriveZipLink
import com.zillit.desktop.feature.drive.domain.EditorSession
import com.zillit.desktop.feature.drive.domain.percentEncoded
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
@Suppress("TooManyFunctions", "LargeClass") // Mirrors the server's operation surface; see the interface.
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

    /** `access/users` on the core host — who may open the tool (ZL-18292). */
    private val accessUsers = config.apiV2() + "access/users"

    // -- browsing ----------------------------------------------------------

    /**
     * `GET /files` and `GET /folders` with the same `quick_filter`, as the
     * web's `fetchDriveData` fires them together. Not `/folders/contents`:
     * that route pages one folder at a time, and a folder created at the
     * root never appeared in its `root=true` answer (seen live 2026-08-27)
     * while the two list routes showed it at once.
     */
    override suspend fun listing(query: DriveListQuery): ZillitResult<DriveListing> {
        val params = query.toParameters()
        val files = get("$base/files", ItemListSerializer, params)
        val folders = get("$base/folders", ItemListSerializer, params)
        return files.flatMap { f ->
            folders.map { d ->
                DriveListing(
                    files = f.rows(DriveItemKind.File).named(),
                    folders = d.rows(DriveItemKind.Folder).named(),
                )
            }
        }
    }

    /**
     * One folder's own contents, scoped on the server — see
     * [DriveRepository.folderContents] for why the tab-wide lists cannot
     * answer this.
     */
    override suspend fun folderContents(folderId: String): ZillitResult<DriveListing> {
        val files = get("$base/files", ItemListSerializer, mapOf("folder_id" to folderId))
        val folders = get("$base/folders", ItemListSerializer, mapOf("parent_folder_id" to folderId))
        return files.flatMap { f ->
            folders.map { d ->
                DriveListing(
                    files = f.rows(DriveItemKind.File).named(),
                    folders = d.rows(DriveItemKind.Folder).named(),
                )
            }
        }
    }

    override suspend fun item(id: String, kind: DriveItemKind): ZillitResult<DriveItem> {
        val url = if (kind == DriveItemKind.Folder) "$base/folders/$id" else "$base/files/$id"
        return get(url, DriveItemDto.serializer()).flatMap { dto ->
            dto.toDomain(kind)?.named()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("drive item had no id"))
        }
    }

    // -- mutations ---------------------------------------------------------

    /** `POST /folders` — `handleCreateFolder`'s payload, access entries included. */
    override suspend fun createFolder(folder: NewFolder): ZillitResult<DriveItem> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/folders",
        serializer = DriveItemDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            put("folder_name", JsonPrimitive(folder.name.trim()))
            put("name", JsonPrimitive(folder.name.trim()))
            if (folder.description.isNotBlank()) put("description", JsonPrimitive(folder.description.trim()))
            put("parent_folder_id", folder.parentId.orJsonNull())
            if (folder.color.isNotBlank()) put("folder_color", JsonPrimitive(folder.color))
            // No `inheritToChildren`: the server gives everything inside a
            // folder the folder's own access, so the flag is ignored and the
            // toggle that set it is gone.
            if (folder.access.isNotEmpty()) put("folder_access", folder.access.toRoleEntries())
        },
    ).flatMap { dto ->
        dto.toDomain(DriveItemKind.Folder)?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("created folder had no id"))
    }

    override suspend fun createNewFile(
        type: NewFileType,
        name: String,
        folderId: String?,
    ): ZillitResult<DriveItem> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/files/new",
        serializer = DriveItemDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            put("type", JsonPrimitive(type.extension))
            name.trim().takeIf { it.isNotEmpty() }?.let { put("name", JsonPrimitive(it)) }
            put("folder_id", folderId.orJsonNull())
        },
    ).flatMap { dto ->
        dto.toDomain(DriveItemKind.File)?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("new file had no id"))
    }

    override suspend fun rename(
        ref: DriveRef,
        name: String,
        description: String?,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Put,
        "${collection(ref.kind)}/${ref.id}",
        buildJsonObject {
            // `folder_name` / `file_name` are what the web sends
            // (`handleEditSubmit` renames the key); `name` rides along for the
            // deployments that read that spelling.
            put("name", JsonPrimitive(name.trim()))
            put(if (ref.kind == DriveItemKind.Folder) "folder_name" else "file_name", JsonPrimitive(name.trim()))
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

    /** The selection as one .zip, in two steps — see [DriveRepository.filesZipLink]. */
    override suspend fun filesZipLink(fileIds: List<String>): ZillitResult<DriveZipLink> = zipLink(
        url = "$base/bulk/download-zip-link",
        body = buildJsonObject { put("file_ids", fileIds.toJsonArray()) },
    )

    /** No auth header on this one: the token is the authorisation. */
    override fun zipDownloadUrl(token: String): String =
        "$base/bulk/download-zip?token=${token.percentEncoded()}"

    /** A whole folder as one .zip — the same two steps as a selection. */
    override suspend fun folderZipLink(folderId: String): ZillitResult<DriveZipLink> =
        zipLink("$base/folders/$folderId/download-zip", body = null)

    override fun folderZipDownloadUrl(token: String): String =
        "$base/folders/download-zip?token=${token.percentEncoded()}"

    /**
     * Both zip routes, which refuse the same way: a 200 carrying `status: 0`
     * and no `data`. Through `request` that decodes as the client's own
     * "response had no data field", which throws away the one thing the user
     * needs to be told.
     */
    private suspend fun zipLink(url: String, body: JsonObject?): ZillitResult<DriveZipLink> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = url,
            module = RequestModule.ProjectUser,
            options = callOptions(),
            body = body ?: buildJsonObject { },
        ).flatMap { envelope ->
            val link = decodeZipLink(envelope.data)
            if (envelope.status == ENVELOPE_OK && link != null) {
                ZillitResult.Success(link)
            } else {
                ZillitResult.Failure(
                    ZillitError.Http(
                        status = ENVELOPE_REFUSED_STATUS,
                        serverMessage = envelope.message,
                        messageElements = envelope.messageElements.orEmpty(),
                    ),
                )
            }
        }

    // -- text files --------------------------------------------------------

    override suspend fun fileText(fileId: String, versionId: String?): ZillitResult<DriveFileText> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/files/$fileId/text",
            serializer = FileTextDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = buildMap { versionId?.let { put("version_id", it) } },
            options = callOptions(),
        ).map { it.toDomain() }

    /**
     * The one body on this service that is not JSON: the file's own text, as
     * `text/markdown`, which is what the route's body parser reads (the web
     * sends exactly this). A JSON object wrapping it arrives as no body at all.
     */
    override suspend fun saveFileText(
        fileId: String,
        content: String,
        contentTime: String?,
    ): ZillitResult<DriveTextSave> = apiClient.request(
        verb = HttpVerb.Put,
        url = "$base/files/$fileId/text",
        serializer = TextSaveDto.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = buildMap { contentTime?.takeIf { it.isNotBlank() }?.let { put("content_time", it) } },
        options = callOptions(),
        textBody = TextBody(content, TEXT_MARKDOWN),
    ).map { it.toDomain() }

    override suspend fun announceEdit(fileId: String, note: String): ZillitResult<String> = message(
        verb = HttpVerb.Post,
        path = "$base/editor/$fileId/announce",
        body = buildJsonObject { put("note", JsonPrimitive(note)) },
    )

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

    override suspend fun shareLinks(fileId: String): ZillitResult<List<DriveShareLink>> =
        get("$base/files/$fileId/email-share-links", ListSerializer(ShareLinkDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createShareLink(
        fileId: String,
        draft: DriveShareLinkDraft,
    ): ZillitResult<DriveShareLink> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/files/$fileId/email-share-link",
        serializer = ShareLinkDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            put(
                "recipients",
                buildJsonArray {
                    draft.recipients.forEach { email ->
                        add(buildJsonObject { put("email", JsonPrimitive(email)) })
                    }
                },
            )
            put("permission", JsonPrimitive(draft.permission.wire))
            put("expires_in_ms", JsonPrimitive(draft.expiresInMillis))
            put("max_views", JsonPrimitive(draft.maxViews))
            draft.message.trim().takeIf { it.isNotBlank() }?.let { put("message", JsonPrimitive(it)) }
        },
    ).flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("the share link came back without an id"))
    }

    override suspend fun revokeShareLink(linkId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Post, "$base/email-share-links/$linkId/revoke", null)

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
     * The pieces one document opens with — see [EditorSession], which
     * assembles them into an address.
     *
     * A config missing any of its three parts is a failure rather than a
     * half-built address: the editor answers one of those with its own error
     * page, and the user reads that as the document being broken.
     */
    override suspend fun editorSession(fileId: String, viewOnly: Boolean): ZillitResult<EditorSession> = get(
        "$base/editor/$fileId/config",
        EditorConfigDto.serializer(),
        // `mode` is sent only to ask for read-only. An edit session is the
        // route's own default, and the server downgrades it by itself for
        // someone without edit rights.
        if (viewOnly) mapOf("mode" to "view") else emptyMap(),
    ).flatMap { dto ->
        val session = dto.toDomain(viewOnly)
        if (session.isUsable) {
            ZillitResult.Success(session)
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
                put("file_access", request.fileAccess.toFlagEntries())
            },
        ).flatMap { dto ->
            dto.toDomain(request.sizeBytes)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("upload session had no id"))
        }

    override suspend fun completeUpload(
        uploadId: String,
        parts: List<UploadPart>,
        fileName: String,
        description: String,
    ): ZillitResult<DriveItem> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/uploads/$uploadId/complete",
        serializer = DriveItemDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            // The web's completion body carries the name and description
            // again (`useFileUpload.js:484-487`); sent when known.
            if (fileName.isNotBlank()) put("file_name", JsonPrimitive(fileName))
            if (description.isNotBlank()) put("description", JsonPrimitive(description))
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

    override suspend fun resumeUpload(uploadId: String, sizeBytes: Long): ZillitResult<UploadSession> =
        get("$base/uploads/$uploadId/parts", UploadSessionDto.serializer()).flatMap { dto ->
            // The same body the opening call answers with, fresh presigned
            // URLs included — the old ones may have expired during a long pause.
            dto.toDomain(sizeBytes)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("upload session had no id"))
        }

    // -- trash -------------------------------------------------------------

    override suspend fun trash(): ZillitResult<List<DriveItem>> =
        get("$base/trash", ItemListSerializer, mapOf("limit" to TRASH_LIMIT)).map { it.rows().named() }

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

    /**
     * One call, and its message back.
     *
     * `POST /folders/{id}/access/inherit` is **not** called any more: the
     * server reaches every subfolder and file itself, at once, and that route
     * only remains for older app versions.
     */
    override suspend fun updateAccess(
        ref: DriveRef,
        entries: List<DriveAccessEntry>,
    ): ZillitResult<String> {
        val folder = ref.kind == DriveItemKind.Folder
        val url = if (folder) {
            "$base/folders/${ref.id}/access"
        } else {
            "$base/file-access/${ref.id}/access"
        }
        return message(
            HttpVerb.Put,
            url,
            buildJsonObject {
                put("entries", if (folder) entries.toRoleEntries() else entries.toFlagEntries())
                // The endpoint replaces rather than patches — an entry left out
                // is revoked. Stated on the wire as well as in the interface
                // doc, because the default here is not obvious from the name.
                if (folder) put("replace_existing", JsonPrimitive(true))
            },
        )
    }

    /**
     * `GET access/users?toolIdentifier=drive_tool&viewing_access=true` — the
     * web's `fetchuserapproveringrights({ view_access: true })` (ZL-18292).
     * A failure answers null: the picker then offers the whole crew rather
     * than nobody, which is what the web does when its call fails too.
     */
    override suspend fun viewAccessUserIds(): ZillitResult<Set<String>?> =
        when (
            val got = get(
                accessUsers,
                IdListSerializer,
                mapOf("toolIdentifier" to TOOL_IDENTIFIER, "viewing_access" to "true"),
            )
        ) {
            is ZillitResult.Success -> ZillitResult.Success(got.data.ids())
            is ZillitResult.Failure -> ZillitResult.Success(null)
        }

    // -- metadata ----------------------------------------------------------

    override suspend fun activity(itemId: String?): ZillitResult<List<DriveActivity>> = get(
        "$base/activity",
        ActivityPageDto.serializer(),
        buildMap {
            itemId?.let { put("item_id", it) }
            put("limit", DETAIL_ACTIVITY_LIMIT)
        },
    ).map { page -> page.items.named() }

    override suspend fun activityPage(limit: Int, offset: Int): ZillitResult<DriveActivityPage> = get(
        "$base/activity",
        ActivityPageDto.serializer(),
        mapOf("limit" to limit, "offset" to offset),
    ).map { page -> DriveActivityPage(items = page.items.named(), total = page.total ?: page.items.size) }

    /** The actors named from the crew list; see [resolveUserName]. */
    @JvmName("namedActivity")
    private fun List<ActivityDto>.named(): List<DriveActivity> =
        mapNotNull { row ->
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

    override suspend fun comments(fileId: String): ZillitResult<List<DriveComment>> = get(
        "$base/comments",
        CommentsSerializer,
        mapOf("file_id" to fileId),
    ).map { page ->
        page.rows().mapNotNull { it.toDomain() }.map { comment ->
            if (comment.authorName.isNotBlank() || comment.authorId.isBlank()) {
                comment
            } else {
                comment.copy(authorName = resolveUserName(comment.authorId).orEmpty())
            }
        }
    }

    override suspend fun updateComment(commentId: String, text: String): ZillitResult<Unit> = mutate(
        HttpVerb.Put,
        "$base/comments/$commentId",
        buildJsonObject { put("text", JsonPrimitive(text.trim())) },
    )

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

    /** The answer carries the new tag when the service sends it (`resp.data._id`); a bare envelope is fine too. */
    override suspend fun createTag(name: String, color: String): ZillitResult<DriveTag?> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/tags",
        serializer = TagDto.serializer(),
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = buildJsonObject {
            put("name", JsonPrimitive(name.trim()))
            if (color.isNotBlank()) put("color", JsonPrimitive(color))
        },
    ).map { it.toDomain() }

    /** `GET /tags/items-by-tag?tag_id=&item_type=` — asked for both kinds, as the web does. */
    override suspend fun itemsByTag(tagId: String): ZillitResult<Set<String>> {
        val ids = mutableSetOf<String>()
        for (kind in DriveItemKind.entries) {
            val rows = get(
                "$base/tags/items-by-tag",
                ListSerializer(ItemTagDto.serializer()),
                mapOf("tag_id" to tagId, "item_type" to kind.wire),
            )
            when (rows) {
                is ZillitResult.Success -> ids += rows.data.mapNotNull { it.itemId() }
                is ZillitResult.Failure -> return rows
            }
        }
        return ZillitResult.Success(ids)
    }

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

    override suspend fun versionHistory(fileId: String): ZillitResult<DriveVersionHistory> =
        get("$base/versions/$fileId/history", VersionHistoryDto.serializer()).map { it.toDomain() }

    override suspend fun versionChanges(
        fileId: String,
        versionId: String,
    ): ZillitResult<DriveVersionChanges> =
        get("$base/versions/$fileId/$versionId/changes", VersionChangesDto.serializer())
            .map { it.toDomain() }

    /**
     * The version's own editor session.
     *
     * The route answers the editor config's shape, so it is decoded with the
     * same DTO — but it names the *version's* address in `liveWopiSrc` and
     * need not repeat the editor page, which belongs to the file rather than
     * to any one version. When it does not, the file's live config supplies
     * it: the alternative is a second window code path for something the user
     * cannot tell apart from opening the file read-only.
     *
     * Always view-only, whatever the config says: a stored version is not
     * writable, and a session that claimed otherwise would offer Save against
     * a key nothing reads back.
     */
    override suspend fun versionPreview(
        fileId: String,
        versionId: String,
    ): ZillitResult<EditorSession> =
        get("$base/versions/$fileId/$versionId/preview", EditorConfigDto.serializer()).flatMap { dto ->
            val session = dto.toDomain(viewOnly = true).copy(canSwitchToEdit = false)
            when {
                session.isUsable -> ZillitResult.Success(session)
                session.wopiSrc.isBlank() || session.accessToken.isBlank() -> ZillitResult.Failure(
                    ZillitError.Serialization("the version preview configuration was incomplete"),
                )

                else -> editorSession(fileId, viewOnly = true).map { live ->
                    session.copy(
                        baseUrl = live.baseUrl,
                        kind = session.kind ?: live.kind,
                        legacyFrame = live.legacyFrame,
                        fileName = session.fileName.ifBlank { live.fileName },
                    )
                }
            }
        }

    /** An empty `name` is how the web clears one, so it is sent as given. */
    override suspend fun nameVersion(
        fileId: String,
        versionId: String,
        name: String,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Patch,
        "$base/versions/$fileId/$versionId",
        buildJsonObject { put("name", JsonPrimitive(name.trim())) },
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

    /**
     * A mutation whose **message** is the answer — the key the screen shows
     * (`folder_access_updated`), rather than wording of our own.
     */
    private suspend fun message(
        verb: HttpVerb,
        path: String,
        body: JsonObject?,
    ): ZillitResult<String> = apiClient.envelope(
        verb = verb,
        url = path,
        module = RequestModule.ProjectUser,
        options = callOptions(),
        body = body,
    ).map { it.message.orEmpty() }

    private companion object {
        const val SHARE_EXPIRY = "24h"
        /** What this backend calls success in the envelope. */
        const val ENVELOPE_OK = 1
        /** A refusal the envelope carried on a 200; reported as the bad request it is. */
        const val ENVELOPE_REFUSED_STATUS = 400
        const val TOOL_IDENTIFIER = "drive_tool"
        /** The web's `getTrashForDrive({ limit: 200 })`. */
        const val TRASH_LIMIT = 200
        /** The web's details-panel fetch (`getActivityForDrive({ item_id, limit: 20 })`). */
        const val DETAIL_ACTIVITY_LIMIT = 20
        /** What the text save goes out as; the route's parser takes text, not JSON. */
        const val TEXT_MARKDOWN = "text/markdown; charset=utf-8"
    }
}

/** Folder grants — `{ user_id, role }` rows. */
private fun List<DriveAccessEntry>.toRoleEntries(): JsonArray = buildJsonArray {
    forEach { entry ->
        add(
            buildJsonObject {
                put("user_id", JsonPrimitive(entry.userId))
                put("role", JsonPrimitive(entry.role.wire))
            },
        )
    }
}

/** File grants — `{ user_id, can_view, can_edit, can_download }` rows. */
private fun List<DriveAccessEntry>.toFlagEntries(): JsonArray = buildJsonArray {
    forEach { entry ->
        add(
            buildJsonObject {
                put("user_id", JsonPrimitive(entry.userId))
                put("can_view", JsonPrimitive(entry.permissions.canView))
                put("can_edit", JsonPrimitive(entry.permissions.canEdit))
                put("can_download", JsonPrimitive(entry.permissions.canDownload))
            },
        )
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
