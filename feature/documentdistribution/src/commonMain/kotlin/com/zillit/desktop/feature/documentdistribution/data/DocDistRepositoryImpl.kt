package com.zillit.desktop.feature.documentdistribution.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.DocumentStorage
import kotlinx.coroutines.flow.Flow
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.NewDistributionDefaults
import com.zillit.desktop.feature.documentdistribution.domain.OpenState
import com.zillit.desktop.feature.documentdistribution.domain.PublicationCategory
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettings
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettingsPatch
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.DocDistTransfer
import com.zillit.desktop.feature.documentdistribution.domain.HistoryPage
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.ZipRecipient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Every `/api/v2/document-distribution` route, on the doc-dist service's host.
 *
 * ## The `status: 0` envelope
 *
 * This service reports business-rule rejections — a duplicate folder name, a
 * recipient the production has blocked, an unpublishable category — as **HTTP
 * 200** with `{ status: 0, message }`. The shared [ApiClient] decides success
 * on the HTTP code, so those would otherwise come back as successes with an
 * empty payload and the screen would report nothing at all. [checked] is where
 * that is caught, mirroring the web's `req()` helper.
 *
 * ## Why the module header is `ProjectUser`
 *
 * Every route here is scoped to one person on one production: the library is
 * the production's, and what a person may send is theirs. The lighter `Device`
 * header omits both and the service answers 406 rather than falling back.
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface; see the interface.
class DocDistRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    bus: SocketEventBus? = null,
    /**
     * Turns an S3 object into a presigned GET URL.
     *
     * Injected because the signing primitives are the platform's. Null — or a
     * null answer — means this host cannot presign, and the document simply
     * cannot be opened, which is reported rather than guessed at.
     */
    private val presign: suspend (DocumentStorage) -> String? = { null },
    /** The byte-level I/O: storage PUTs, signed fetches, the file-answering routes. */
    private val transfer: DocDistTransfer = DocDistTransfer.None,
    /**
     * Whether the open production stores in S3 — anything but `LOCAL`.
     *
     * Decides the upload path: S3 productions PUT the bytes themselves and
     * register the key; LOCAL ones multipart the bytes to the service.
     */
    private val isS3Storage: () -> Boolean = { true },
    /** Injected for the storage key: common code has no UUID of its own. */
    private val newUniqueId: () -> String = { "" },
    /** This device's id, to drop the echo of its own watermark-settings save. */
    selfDeviceId: () -> String? = { null },
) : DocDistRepository {

    /** See [DocDistRepository.refreshes] and [docDistRefreshes]. */
    override val refreshes: Flow<DocDistRefresh> = docDistRefreshes(bus)

    /** See [DocDistRepository.watermarkSettingsUpdates] and [watermarkSettingsUpdates]. */
    override val watermarkSettingsUpdates: Flow<WatermarkSettings> = watermarkSettingsUpdates(bus, selfDeviceId)

    private val base = "${config.baseUrl(ZillitService.DocDistribution)}/api/v2/document-distribution"

    /**
     * Open tracking lives with the mail service that sent the copy.
     *
     * Not a mistake in the routing table: doc-dist hands the send to the email
     * service, which owns the pixel log, so the status of a sent copy is only
     * ever answerable there.
     */
    private val emailBase = "${config.baseUrl(ZillitService.Email)}/api/v2"

    // -- library -----------------------------------------------------------

    override suspend fun folders(): ZillitResult<List<LibraryFolder>> =
        get("$base/folders", ListSerializer(FolderDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createFolder(
        name: String,
        parentId: String?,
        description: String,
        folderDate: String?,
    ): ZillitResult<Unit> =
        post(
            "$base/folders",
            buildJsonObject {
                put("name", JsonPrimitive(name.trim()))
                put("description", JsonPrimitive(description.trim()))
                // Explicitly null rather than omitted: null is what this
                // service reads as "at the root", and an absent key files the
                // folder under whatever it last had.
                put("parent_id", parentId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                folderDate?.takeIf { it.isNotBlank() }?.let { put("folder_date", JsonPrimitive(it)) }
            },
        )

    override suspend fun updateFolder(folderId: String, name: String, description: String): ZillitResult<Unit> =
        put(
            "$base/folders",
            buildJsonObject {
                put("folderId", JsonPrimitive(folderId))
                put("name", JsonPrimitive(name.trim()))
                put("description", JsonPrimitive(description.trim()))
            },
        )

    override suspend fun deleteFolder(folderId: String): ZillitResult<Unit> =
        delete("$base/folders", buildJsonObject { put("folderId", JsonPrimitive(folderId)) })

    override suspend fun moveFolders(
        folderIds: List<String>,
        parentId: String?,
    ): ZillitResult<Unit> = post(
        "$base/folders/move",
        buildJsonObject {
            put("ids", folderIds.toJsonArray())
            put("parent_id", parentId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        },
    )

    override suspend fun documents(query: LibraryQuery): ZillitResult<LibraryPage> = get(
        "$base/documents",
        DocumentPageDto.serializer(),
        buildMap {
            // The literal string "null", not an omitted key: this service reads
            // an absent `folder_id` as "any folder" and answers with the whole
            // library, so browsing the root would list every document on the
            // production. The web sends the same sentinel.
            put("folder_id", query.folderId ?: ROOT_SENTINEL)
            put("page", query.page)
            put("limit", query.limit)
            put("sort_by", query.sort.wire)
            query.search.trim().takeIf { it.isNotEmpty() }?.let { put("q", it) }
            query.documentDate?.takeIf { it.isNotBlank() }?.let { put("document_date", it) }
        },
    ).map { it.toDomain() }

    override suspend fun documentsInFolders(folderIds: Collection<String>): ZillitResult<List<LibraryDocument>> {
        val found = linkedMapOf<String, LibraryDocument>()
        for (folderId in folderIds.distinct()) {
            val page = get(
                "$base/documents",
                DocumentPageDto.serializer(),
                mapOf("folder_id" to folderId, "page" to 0, "limit" to BULK_LIMIT),
            )
            when (page) {
                is ZillitResult.Failure -> return page
                is ZillitResult.Success -> page.data.toDomain().documents.forEach { found[it.id] = it }
            }
        }
        return ZillitResult.Success(found.values.toList())
    }

    /** No `folder_id` at all: that is what this service reads as "every folder". */
    override suspend fun allDocuments(): ZillitResult<List<LibraryDocument>> =
        get("$base/documents", DocumentPageDto.serializer(), mapOf("page" to 0, "limit" to BULK_LIMIT))
            .map { it.toDomain().documents }

    override suspend fun documentsByIds(ids: List<String>): ZillitResult<List<LibraryDocument>> {
        if (ids.isEmpty()) return ZillitResult.Success(emptyList())
        return get("$base/documents", DocumentPageDto.serializer(), mapOf("ids" to ids.joinToString(",")))
            .map { it.toDomain().documents }
    }

    override suspend fun ephemeralByIds(ids: List<String>): ZillitResult<List<LibraryDocument>> {
        if (ids.isEmpty()) return ZillitResult.Success(emptyList())
        return apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/attachments/by-ids",
            serializer = EphemeralListDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("ids", ids.toJsonArray()) },
        ).map { dto -> dto.attachments.mapNotNull { it.toDomain(ephemeral = true) } }
    }

    /**
     * S3 productions PUT the bytes under `document-distribution/{uuid}/{name}`
     * — Android's key shape — and register the row with `from-s3`; LOCAL
     * productions multipart the bytes to the service. Both answer the
     * catalogued row.
     */
    override suspend fun uploadDocument(
        file: LocalFile,
        folderId: String?,
        documentDate: String?,
    ): ZillitResult<LibraryDocument> {
        if (!isS3Storage()) {
            val fields = buildMap {
                folderId?.let { put("folder_id", it) }
                documentDate?.let { put("document_date", it) }
            }
            return transfer.postMultipart("$base/documents", fields, file).flatMap { it.toDocument() }
        }
        val stored = transfer.putObject(storageKey("document-distribution", file.name), file.contentType, file.bytes)
        val storage = when (stored) {
            is ZillitResult.Failure -> return stored
            is ZillitResult.Success -> stored.data
        }
        return apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/documents/from-s3",
            serializer = DocumentDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                // `folder_id` must not lead: a leading JSON null trips the
                // platform's body-hash builder (Android's note on the same body).
                documentDate?.let { put("document_date", JsonPrimitive(it)) }
                put("folder_id", folderId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                putStored(file, storage)
            },
        ).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("registered document had no id"))
        }
    }

    override suspend fun uploadEphemeral(file: LocalFile): ZillitResult<LibraryDocument> {
        if (!isS3Storage()) {
            return transfer.postMultipart("$base/attachments", emptyMap(), file).flatMap { it.toDocument(
                ephemeral = true,
            ) }
        }
        val stored = transfer.putObject(
            storageKey("document-distribution/ephemeral", file.name),
            file.contentType,
            file.bytes,
        )
        val storage = when (stored) {
            is ZillitResult.Failure -> return stored
            is ZillitResult.Success -> stored.data
        }
        return apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/attachments/from-s3",
            serializer = DocumentDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { putStored(file, storage) },
        ).flatMap { dto ->
            dto.toDomain(ephemeral = true)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("registered attachment had no id"))
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStored(file: LocalFile, storage: DocumentStorage) {
        put("original_name", JsonPrimitive(file.name))
        put("content_type", JsonPrimitive(file.contentType))
        put("file_size", JsonPrimitive(file.sizeBytes))
        put("media", JsonPrimitive(storage.key))
        put("bucket", JsonPrimitive(storage.bucket))
        put("region", JsonPrimitive(storage.region))
        put("content_id", JsonPrimitive(storage.key))
        put("thumbnail", JsonPrimitive(""))
        put("width", JsonPrimitive(0))
        put("height", JsonPrimitive(0))
        put("media_type", JsonPrimitive(mediaTypeOf(file.contentType)))
    }

    /** `{prefix}/{uuid}/{name}` with the characters S3 keys dislike swapped out. */
    private fun storageKey(prefix: String, name: String): String =
        "$prefix/${newUniqueId()}/" + name.replace(UNSAFE_KEY_CHARS, "_")

    private fun JsonElement.toDocument(ephemeral: Boolean = false): ZillitResult<LibraryDocument> =
        runCatching { docDistJson.decodeFromJsonElement(DocumentDto.serializer(), this) }
            .getOrNull()?.toDomain(ephemeral)
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("upload answered no document"))

    override suspend fun deleteEphemeral(attachmentId: String): ZillitResult<Unit> =
        delete("$base/attachments", buildJsonObject { put("attachmentId", JsonPrimitive(attachmentId)) })

    /**
     * S3 documents are read through the app's signed fetch; LOCAL ones
     * through the service's `/raw` proxy, which needs the encrypted headers
     * a browser cannot send — which is why the bytes come through here rather
     * than a URL.
     */
    override suspend fun documentBytes(document: LibraryDocument): ZillitResult<ByteArray> {
        val storage = document.storage
        return if (storage != null && storage.bucket.isNotBlank()) {
            transfer.fetchObject(storage)
        } else {
            val route = if (document.isEphemeral) "attachments" else "documents"
            transfer.getBytes("$base/$route/${document.id}/raw")
        }
    }

    override suspend fun watermarkedCopy(
        documentId: String,
        text: String,
        style: WatermarkStyle,
    ): ZillitResult<ByteArray> = transfer.postBytes(
        "$base/documents/$documentId/watermarked",
        buildJsonObject {
            put("text", JsonPrimitive(text))
            put("size", JsonPrimitive(style.size.wire))
            put("color", JsonPrimitive(style.color))
            put("opacity", JsonPrimitive(style.opacity))
        },
    )

    override suspend fun watermarkedZip(
        documentIds: List<String>,
        recipients: List<ZipRecipient>,
        style: WatermarkStyle,
    ): ZillitResult<ByteArray> = transfer.postBytes(
        "$base/documents/watermark-zip",
        buildJsonObject {
            put("documentIds", documentIds.toJsonArray())
            put(
                "recipients",
                buildJsonArray {
                    recipients.forEach { recipient ->
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(recipient.name))
                                put("email", JsonPrimitive(recipient.email))
                                put("watermarkText", JsonPrimitive(recipient.watermarkText))
                            },
                        )
                    }
                },
            )
            put(
                "style",
                buildJsonObject {
                    put("size", JsonPrimitive(style.size.wire))
                    put("color", JsonPrimitive(style.color))
                    put("opacity", JsonPrimitive(style.opacity))
                },
            )
        },
    )

    override suspend fun deleteDocument(documentId: String): ZillitResult<Unit> =
        delete("$base/documents", buildJsonObject { put("documentId", JsonPrimitive(documentId)) })

    override suspend fun moveDocuments(
        documentIds: List<String>,
        folderId: String?,
    ): ZillitResult<Unit> = post(
        "$base/documents/move",
        buildJsonObject {
            put("ids", documentIds.toJsonArray())
            put("folder_id", folderId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        },
    )

    /**
     * The URL a document's bytes can be read from.
     *
     * Two storage backends, one answer: S3 productions get a presigned URL from
     * the server, LOCAL productions have no such concept and are served the
     * `/raw` route directly. Deciding here rather than at each call site is
     * what keeps preview, download and watermark from each carrying their own
     * copy of the branch — the web has three.
     */
    /**
     * Where to fetch a document's bytes from.
     *
     * S3 documents get a presigned URL, which is the only form the OS browser
     * can open: the server's `/documents/:id/raw` proxy answers only to the
     * app's encrypted headers, and a browser sends none of them. Both phones
     * split the same way — `attachment != null` is S3, everything else is the
     * proxy.
     *
     * There was a `/documents/:id/download-url` endpoint here until
     * 2026-08-27. It does not exist: every open and every download in this
     * tool came back 404, and the `/raw` fallback beneath it was unreachable
     * because a 404 is a failure, not a null body.
     */
    override suspend fun documentUrl(document: LibraryDocument): ZillitResult<String> {
        val storage = document.storage
            ?: return ZillitResult.Failure(
                ZillitError.Storage(
                    technical = "document ${document.id} has no S3 attachment",
                    userMessage = "This file is stored on the server and cannot be opened from the desktop yet.",
                ),
            )
        return presign(storage)?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(
                ZillitError.Storage(
                    technical = "no AWS credentials, or an incomplete attachment",
                    userMessage = "This file cannot be opened — the workspace has no file storage configured.",
                ),
            )
    }

    // -- distribution lists ------------------------------------------------

    override suspend fun lists(): ZillitResult<List<DistributionList>> =
        get("$base/presets", ListSerializer(PresetDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createList(
        name: String,
        recipients: List<Recipient>,
        description: String,
    ): ZillitResult<DistributionList> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/presets",
        serializer = PresetDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("name", JsonPrimitive(name.trim()))
            put("description", JsonPrimitive(description.trim()))
            put("recipients", recipients.toJsonArray())
        },
    ).flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("created list had no id"))
    }

    override suspend fun updateList(
        listId: String,
        name: String?,
        recipients: List<Recipient>,
        description: String?,
    ): ZillitResult<Unit> = put(
        "$base/presets",
        buildJsonObject {
            put("presetId", JsonPrimitive(listId))
            name?.let { put("name", JsonPrimitive(it.trim())) }
            description?.let { put("description", JsonPrimitive(it.trim())) }
            put("recipients", recipients.toJsonArray())
        },
    )

    override suspend fun deleteList(listId: String): ZillitResult<Unit> =
        delete("$base/presets", buildJsonObject { put("presetId", JsonPrimitive(listId)) })

    override suspend fun exportList(listId: String): ZillitResult<ByteArray> =
        transfer.getBytes("$base/presets/$listId/export")

    // -- address book ------------------------------------------------------

    override suspend fun contacts(): ZillitResult<List<Contact>> =
        get("$base/contacts", ListSerializer(ContactDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * Creates or updates, decided by the server rather than by this client.
     *
     * The address book is keyed by email and both routes take the same body, so
     * a client-side "does this exist" check would only add a round trip and a
     * race. `POST` upserts; the web's separate `updateContact` exists solely
     * because its modal already knew which it was doing.
     */
    override suspend fun saveContact(contact: Contact): ZillitResult<Unit> = post(
        "$base/contacts",
        buildJsonObject {
            put("email", JsonPrimitive(contact.email.trim()))
            put("name", JsonPrimitive(contact.name.trim()))
            put("job", JsonPrimitive(contact.jobTitle.trim()))
        },
    )

    override suspend fun deleteContact(email: String): ZillitResult<Unit> =
        delete("$base/contacts", buildJsonObject { put("email", JsonPrimitive(email)) })

    // -- templates ---------------------------------------------------------

    override suspend fun templates(): ZillitResult<List<EmailTemplate>> =
        get("$base/email-templates", ListSerializer(TemplateDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun saveTemplate(template: EmailTemplate): ZillitResult<Unit> {
        val body = buildJsonObject {
            if (template.id.isNotBlank()) put("templateId", JsonPrimitive(template.id))
            put("name", JsonPrimitive(template.name.trim()))
            put("subject", JsonPrimitive(template.subject))
            put("body", JsonPrimitive(template.bodyHtml))
        }
        return if (template.id.isBlank()) {
            post("$base/email-templates", body)
        } else {
            put("$base/email-templates", body)
        }
    }

    override suspend fun deleteTemplate(templateId: String): ZillitResult<Unit> =
        delete(
            "$base/email-templates",
            buildJsonObject { put("templateId", JsonPrimitive(templateId)) },
        )

    // -- sending and history -----------------------------------------------

    override suspend fun send(distribution: NewDistribution): ZillitResult<Unit> = post(
        "$base/distributions",
        buildJsonObject {
            put(
                "folder_id",
                distribution.folderId?.let(::JsonPrimitive)
                    ?: kotlinx.serialization.json.JsonNull,
            )
            put(
                "preset_id",
                distribution.listId?.let(::JsonPrimitive)
                    ?: kotlinx.serialization.json.JsonNull,
            )
            // The server renders "(no subject)" itself on an empty string, but
            // sending the placeholder makes History read the same on both
            // clients — the web sends it explicitly for that reason.
            put(
                "subject",
                JsonPrimitive(
                    distribution.subject.trim().ifBlank { NewDistributionDefaults.NO_SUBJECT },
                ),
            )
            put("body", JsonPrimitive(distribution.bodyHtml))
            distribution.replyTo?.takeIf { it.isNotBlank() }
                ?.let { put("reply_to", JsonPrimitive(it)) }
            put("recipients", distribution.to.toJsonArray())
            put("cc", distribution.cc.toJsonArray())
            put("bcc", distribution.bcc.toJsonArray())
            put("attachment_ids", distribution.attachmentIds.toJsonArray())
            put("ephemeral_attachment_ids", distribution.ephemeralAttachmentIds.toJsonArray())
            // Omitted entirely when empty. An empty object here is read as "no
            // watermarks", which is the same outcome, but the server logs the
            // key's presence as an explicit opt-out and the History row then
            // claims a stamping decision the sender never made.
            if (distribution.watermarks.isNotEmpty()) {
                put("watermarks", distribution.watermarks.toJsonObject())
            }
        },
    )

    /**
     * History, always paged.
     *
     * The route answers `{ distributions, total }` when paged and a **bare
     * array** of the newest 200 when called with no parameters — the same trap
     * payroll's weekly processing route carries. Paging is always requested so
     * only the first shape can arrive; the array fallback is kept because a
     * server that ignores the parameters would otherwise decode to nothing and
     * show an empty History with no error.
     */
    override suspend fun history(
        page: Int,
        search: String,
        senderIds: Set<String>,
        limit: Int,
    ): ZillitResult<HistoryPage> {
        val query = buildMap<String, Any?> {
            put("page", page)
            put("limit", limit)
            search.trim().takeIf { it.isNotEmpty() }?.let { put("q", it) }
            senderParam(senderIds)?.let { put("sent_by", it) }
        }
        val paged = get("$base/distributions", DistributionPageDto.serializer(), query)
        if (paged is ZillitResult.Success) {
            val rows = paged.data.distributions.mapNotNull { it.toDomain() }
            return ZillitResult.Success(HistoryPage(rows, paged.data.total ?: rows.size))
        }
        return get("$base/distributions", ListSerializer(DistributionDto.serializer()), query)
            .map { rows -> rows.mapNotNull { it.toDomain() }.let { HistoryPage(it, it.size) } }
    }

    override suspend fun senders(): ZillitResult<List<DistributionSender>> =
        get("$base/distributions/senders", DistributionSendersDto.serializer())
            .map { dto -> dto.senders.mapNotNull { it.toDomain() } }

    override suspend fun distribution(id: String): ZillitResult<Distribution> =
        get("$base/distributions/$id", DistributionDto.serializer()).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("distribution had no id"))
        }

    override suspend fun openStatus(
        uniqueIds: List<String>,
    ): ZillitResult<Map<String, DeliveryStatus>> {
        if (uniqueIds.isEmpty()) return ZillitResult.Success(emptyMap())
        return apiClient.request(
            verb = HttpVerb.Post,
            url = "$emailBase/email-sent-log/open-status",
            serializer = OpenStatusEnvelopeDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("unique_ids", uniqueIds.toJsonArray()) },
        ).map { envelope ->
            envelope.emails.mapNotNull { row ->
                val id = row.uniqueId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // `found == false` means the service has never heard of this
                // copy — not that it went unread. Reporting that as unopened
                // would turn a logging gap into an accusation.
                val state = when {
                    row.found == false -> OpenState.Unknown
                    row.opened == true -> OpenState.Opened
                    row.opened == false -> OpenState.NotOpened
                    else -> OpenState.Unknown
                }
                id to DeliveryStatus(
                    recipient = Recipient(email = ""),
                    uniqueId = id,
                    state = state,
                    openedAt = row.openedAt.toEpochMillisOrNull(),
                    openCount = row.openCount ?: 0,
                )
            }.toMap()
        }
    }

    // -- watermark settings ------------------------------------------------

    override suspend fun watermarkSettings(): ZillitResult<WatermarkSettings> =
        get("$base/watermark-settings", WatermarkSettingsDto.serializer()).map { it.toDomain() }

    /**
     * Only the fields in the patch go on the wire: an absent key keeps the
     * server's value, a present one replaces it, and the upsert is atomic per
     * field. The answer is the settings after the save, which the caller
     * takes over its cache rather than refetching.
     */
    override suspend fun updateWatermarkSettings(patch: WatermarkSettingsPatch): ZillitResult<WatermarkSettings> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "$base/watermark-settings",
            serializer = WatermarkSettingsDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                patch.size?.let { put("size", JsonPrimitive(it.wire)) }
                patch.color?.let { put("color", JsonPrimitive(it)) }
                patch.opacity?.let { put("opacity", JsonPrimitive(it)) }
            },
        ).map { it.toDomain() }

    // -- publishing --------------------------------------------------------

    override suspend fun publicationCategories(): ZillitResult<List<PublicationCategory>> =
        get("$base/publications/categories", ListSerializer(PublicationCategoryDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun publishedFiles(category: String): ZillitResult<List<PublishedFile>> =
        get(
            "$base/publications/published-files",
            ListSerializer(PublishedFileDto.serializer()),
            mapOf("category" to category),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun publish(category: String, draft: PublishDraft): ZillitResult<Unit> =
        post("$base/publications", publicationWire(category, draft))

    // -- plumbing ----------------------------------------------------------

    private suspend fun <T> get(
        url: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<T> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = serializer,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> =
        mutate(HttpVerb.Post, url, body)

    private suspend fun put(url: String, body: JsonObject?): ZillitResult<Unit> =
        mutate(HttpVerb.Put, url, body)

    /**
     * A DELETE that carries a body.
     *
     * Unusual, and not this client's choice: every destructive route here takes
     * its target id in the body rather than the path (`DELETE /folders` with
     * `{ folderId }`). Putting it in the path instead 404s.
     */
    private suspend fun delete(url: String, body: JsonObject?): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, url, body)

    private suspend fun mutate(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = verb,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    ).flatMap { it.checked() }

    /**
     * Turns this service's soft rejection into a real failure.
     *
     * `{ status: 0, message }` on an HTTP 200 is how the doc-dist backend says
     * no — a folder name already taken, an address the production blocks. The
     * shared client cannot see it, so a caller that does not check reports a
     * successful send that never left.
     */
    private fun ApiEnvelope.checked(): ZillitResult<Unit> =
        if (status == REJECTED) {
            ZillitResult.Failure(
                ZillitError.Validation(
                    userMessage = message?.humanised() ?: "That could not be done.",
                    technical = message,
                ),
            )
        } else {
            ZillitResult.Success(Unit)
        }

    private companion object {
        /** `{ status: 0 }` — a business-rule rejection dressed as a 200. */
        const val REJECTED = 0

        /** What this service wants in `folder_id` to mean the library root. */
        const val ROOT_SENTINEL = "null"

        /** One high-limit slice for the cross-folder actions and the picker — the web's choice. */
        const val BULK_LIMIT = 1000

        val UNSAFE_KEY_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

/**
 * `folder_name_exists` → "Folder name exists".
 *
 * These messages are translation keys, and the label dictionaries do not carry
 * this service's set. Rendering the raw key is worse than a humanised guess at
 * it, and both are better than swallowing the reason.
 */
private fun String.humanised(): String =
    replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { add(JsonPrimitive(it)) }
}

@JvmName("recipientsToJsonArray")
private fun List<Recipient>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { recipient ->
        add(
            buildJsonObject {
                put("email", JsonPrimitive(recipient.email.trim()))
                put("name", JsonPrimitive(recipient.name.trim()))
                if (recipient.jobTitle.isNotBlank()) {
                    put("job", JsonPrimitive(recipient.jobTitle.trim()))
                }
            },
        )
    }
}

/** Attachment id → stamp, in the shape the server's personaliser reads. */
private fun Map<String, WatermarkStyle>.toJsonObject(): JsonObject = buildJsonObject {
    forEach { (attachmentId, style) ->
        put(
            attachmentId,
            buildJsonObject {
                put("line1", JsonPrimitive(style.line1.wire))
                put("line1Custom", JsonPrimitive(style.line1Custom))
                put("line2", JsonPrimitive(style.line2.wire))
                put("line2Custom", JsonPrimitive(style.line2Custom))
                put("size", JsonPrimitive(style.size.wire))
                put("color", JsonPrimitive(style.color))
                put("opacity", JsonPrimitive(style.opacity))
            },
        )
    }
}

/** The `media_type` the server files an upload under, from its MIME. */
private fun mediaTypeOf(contentType: String): String = when {
    contentType.startsWith("image/", ignoreCase = true) -> "image"
    contentType.startsWith("video/", ignoreCase = true) -> "video"
    contentType.startsWith("audio/", ignoreCase = true) -> "audio"
    else -> "document"
}

/** Unused today; kept beside its writer so the two shapes stay together. */
@Suppress("unused")
private fun JsonObject.idsOf(key: String): List<String> =
    this[key]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

/** `sent_by` as the wire wants it: comma-joined ids, blanks dropped, null when there is nothing to send. */
internal fun senderParam(senderIds: Collection<String>): String? =
    senderIds.map(String::trim).filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.joinToString(",")
