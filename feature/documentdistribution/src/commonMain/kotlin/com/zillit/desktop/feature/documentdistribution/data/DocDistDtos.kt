package com.zillit.desktop.feature.documentdistribution.data

import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.DocumentStorage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.MediaKind
import com.zillit.desktop.feature.documentdistribution.domain.OpenState
import com.zillit.desktop.feature.documentdistribution.domain.PublicationCategory
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire shapes of `/api/v2/document-distribution`.
 *
 * ## Everything is nullable
 *
 * The service is Mongo-backed and omits rather than nulls: a folder with no
 * parent has no `parent_id` key at all, a document never given a production
 * date has no `document_date`. Declaring these non-null makes the whole listing
 * fail to decode the first time a production has one such row, which is a blank
 * screen for a field that was never required.
 *
 * ## `_id`, not `id`
 *
 * Mongoose serialises the primary key as `_id`. The web shims a virtual `.id`
 * across every response (`api.js: addId`); here the DTO simply reads `_id`, and
 * the domain model is the only thing downstream that exists.
 */
@Serializable
internal data class FolderDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    /**
     * `YYYY-MM-DD`, and **not a created timestamp**.
     *
     * The production date the folder is *for*, set by hand when it is created
     * — the same concept as a document's `document_date`. There is no
     * `created_at` on this collection; reading one gave an empty column on
     * every row (verified live 2026-08-11).
     */
    @SerialName("folder_date") val folderDate: String? = null,
) {
    fun toDomain(): LibraryFolder? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return LibraryFolder(
            id = identifier,
            name = name.orEmpty().ifBlank { "Untitled folder" },
            parentId = parentId?.takeIf { it.isNotBlank() },
            folderDate = folderDate.orEmpty().take(ISO_DATE_LENGTH),
        )
    }
}

@Serializable
internal data class DocumentDto(
    @SerialName("_id") val id: String? = null,
    /**
     * `original_name` — the file's own name, as uploaded.
     *
     * There is no `name` on a document; every row read "Untitled" until this
     * was corrected (verified live 2026-08-11). `name` is kept as a fallback
     * only because the from-tool registration path sets one.
     */
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("document_date") val documentDate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("media") val media: String? = null,
    /**
     * The S3 object, when there is one.
     *
     * Nested — `media` at the top level is not where the listing puts it, and
     * reading only that left every document with no storage at all.
     */
    @SerialName("attachment") val attachment: AttachmentStorageDto? = null,
) {
    fun toDomain(): LibraryDocument? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        val fileName = listOf(originalName, name)
            .firstOrNull { !it.isNullOrBlank() } ?: "Untitled"
        return LibraryDocument(
            id = identifier,
            name = fileName,
            folderId = folderId?.takeIf { it.isNotBlank() },
            contentType = contentType,
            mediaKind = MediaKind.of(mediaType, contentType, fileName),
            sizeBytes = fileSize ?: 0,
            // Trimmed to the date part: the column is a date, but some rows
            // carry a full ISO timestamp, and an ungrouped `2026-08-11T09:00Z`
            // bucket beside `2026-08-11` splits one day into two headings.
            documentDate = documentDate.orEmpty().take(ISO_DATE_LENGTH),
            createdAt = createdAt.toEpochMillisOrNull(),
            storage = attachment?.toDomain() ?: media?.takeIf { it.isNotBlank() }
                ?.let { DocumentStorage(key = it, bucket = "", region = "") },
        )
    }
}

/** The S3 triplet a presigned GET needs. */
@Serializable
internal data class AttachmentStorageDto(
    @SerialName("media") val media: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
) {
    fun toDomain(): DocumentStorage? = media?.takeIf { it.isNotBlank() }?.let { key ->
        DocumentStorage(key = key, bucket = bucket.orEmpty(), region = region.orEmpty())
    }
}

@Serializable
internal data class DocumentPageDto(
    @SerialName("documents") val documents: List<DocumentDto> = emptyList(),
    @SerialName("total") val total: Int? = null,
    /**
     * `YYYY-MM-DD` → count, for the whole result rather than this page.
     *
     * Typed as raw JSON because the service sends the counts as numbers on some
     * routes and as numeric strings on others, and a `Map<String, Int>` fails
     * outright on the second. See [countsOf].
     */
    @SerialName("date_counts") val dateCounts: Map<String, JsonElement> = emptyMap(),
) {
    fun toDomain(): LibraryPage {
        val rows = documents.mapNotNull { it.toDomain() }
        return LibraryPage(
            documents = rows,
            total = total ?: rows.size,
            dateCounts = countsOf(dateCounts),
        )
    }
}

/** Reads the per-day totals whichever way this service typed them. */
private fun countsOf(raw: Map<String, JsonElement>): Map<String, Int> =
    raw.mapNotNull { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        primitive.content.toIntOrNull()?.let { key to it }
    }.toMap()

@Serializable
internal data class RecipientDto(
    @SerialName("email") val email: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("job") val job: String? = null,
    /** Present on a *sent* recipient — what open status is asked by. */
    @SerialName("unique_id") val uniqueId: String? = null,
    @SerialName("opened") val opened: Boolean? = null,
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("open_count") val openCount: Int? = null,
) {
    fun toRecipient(): Recipient? {
        val address = email?.takeIf { it.isNotBlank() } ?: return null
        return Recipient(email = address, name = name.orEmpty(), jobTitle = job.orEmpty())
    }

    fun toDelivery(): DeliveryStatus? = toRecipient()?.let { recipient ->
        DeliveryStatus(
            recipient = recipient,
            uniqueId = uniqueId?.takeIf { it.isNotBlank() },
            // An absent `opened` is genuinely unknown rather than "no": the
            // field is only written once the tracking pixel has been asked
            // about, so treating absence as not-opened would report every
            // fresh send as ignored. See OpenState.
            state = when (opened) {
                true -> OpenState.Opened
                false -> OpenState.NotOpened
                null -> OpenState.Unknown
            },
            openedAt = openedAt.toEpochMillisOrNull(),
            openCount = openCount ?: 0,
        )
    }
}

@Serializable
internal data class PresetDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("recipients") val recipients: List<RecipientDto> = emptyList(),
) {
    fun toDomain(): DistributionList? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return DistributionList(
            id = identifier,
            name = name.orEmpty().ifBlank { "Untitled list" },
            recipients = recipients.mapNotNull { it.toRecipient() },
        )
    }
}

@Serializable
internal data class ContactDto(
    @SerialName("email") val email: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("job") val job: String? = null,
) {
    fun toDomain(): Contact? {
        val address = email?.takeIf { it.isNotBlank() } ?: return null
        return Contact(email = address, name = name.orEmpty(), jobTitle = job.orEmpty())
    }
}

@Serializable
internal data class TemplateDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("subject") val subject: String? = null,
    @SerialName("body") val body: String? = null,
) {
    fun toDomain(): EmailTemplate? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return EmailTemplate(
            id = identifier,
            name = name.orEmpty().ifBlank { "Untitled template" },
            subject = subject.orEmpty(),
            bodyHtml = body.orEmpty(),
        )
    }
}

@Serializable
internal data class AttachmentSummaryDto(
    @SerialName("name") val name: String? = null,
)

@Serializable
internal data class PresetUsedDto(
    @SerialName("name") val name: String? = null,
)

@Serializable
internal data class DistributionDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("subject") val subject: String? = null,
    /**
     * `created`, and it may be a number or a string.
     *
     * Not `created_at` — that key does not exist here and the Sent column was
     * empty on every row until this was corrected (verified live 2026-08-11).
     */
    @SerialName("created") val created: JsonPrimitive? = null,
    @SerialName("sent_by_name") val sentByName: String? = null,
    @SerialName("recipients") val recipients: List<RecipientDto> = emptyList(),
    @SerialName("attachments") val attachments: List<AttachmentSummaryDto> = emptyList(),
    @SerialName("presets_used") val presetsUsed: List<PresetUsedDto> = emptyList(),
) {
    fun toDomain(): Distribution? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return Distribution(
            id = identifier,
            subject = subject.orEmpty().ifBlank { "(no subject)" },
            sentAt = created?.content.toEpochMillisOrNull(),
            sentByName = sentByName.orEmpty(),
            recipients = recipients.mapNotNull { it.toDelivery() },
            attachmentNames = attachments.mapNotNull { it.name?.takeIf(String::isNotBlank) },
            listsUsed = presetsUsed.mapNotNull { it.name?.takeIf(String::isNotBlank) },
        )
    }
}

/**
 * The history listing, which answers two shapes.
 *
 * Paged (`{ distributions, total }`) when the caller sends page/limit/q, and a
 * bare array of the newest 200 otherwise — the same route, two shapes, exactly
 * the trap `zillit-service-host-routing` records for payroll. This client
 * always pages, so the paged shape is the one declared; the flat form is read
 * by the fallback in `DocDistRepositoryImpl.history`.
 */
@Serializable
internal data class DistributionPageDto(
    @SerialName("distributions") val distributions: List<DistributionDto> = emptyList(),
    @SerialName("total") val total: Int? = null,
)

@Serializable
internal data class PublicationCategoryDto(
    @SerialName("value") val value: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("republishable") val republishable: Boolean? = null,
) {
    fun toDomain(): PublicationCategory? {
        val id = (value ?: identifier)?.takeIf { it.isNotBlank() } ?: return null
        return PublicationCategory(
            identifier = id,
            label = label.orEmpty().ifBlank { id.humanised() },
            // The server does not flag this; the two re-publishable categories
            // are fixed and named in the web's PublishModal.
            republishable = republishable ?: (id in REPUBLISHABLE),
        )
    }
}

@Serializable
internal data class PublishedFileDto(
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("published_at") val publishedAt: Long? = null,
) {
    fun toDomain(): PublishedFile? {
        val identifier = chatId?.takeIf { it.isNotBlank() } ?: return null
        return PublishedFile(
            chatId = identifier,
            name = name.orEmpty().ifBlank { "Published file" },
            publishedAt = publishedAt?.takeIf { it > 0 },
        )
    }
}

/** The open-status reply from the **email** service, not this one. */
@Serializable
internal data class OpenStatusDto(
    @SerialName("unique_id") val uniqueId: String? = null,
    @SerialName("found") val found: Boolean? = null,
    @SerialName("opened") val opened: Boolean? = null,
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("open_count") val openCount: Int? = null,
)

@Serializable
internal data class OpenStatusEnvelopeDto(
    @SerialName("emails") val emails: List<OpenStatusDto> = emptyList(),
)

/** A URL-only reply, as the download and raw routes send. */
@Serializable
internal data class UrlDto(
    @SerialName("url") val url: String? = null,
    @SerialName("signed_url") val signedUrl: String? = null,
) {
    val value: String? get() = (url ?: signedUrl)?.takeIf { it.isNotBlank() }
}

/** `call_sheet_unit` → "Call Sheet", for a category the server names but does not label. */
private fun String.humanised(): String =
    split('_').filter { it.isNotBlank() }.joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }

/** The two categories a second publish can replace rather than add to. */
private val REPUBLISHABLE = setOf("call_sheet_unit", "production_report")

private const val ISO_DATE_LENGTH = 10

/**
 * Decoders from raw JSON, for `DocDistWireShapeTest`.
 *
 * These exist so the shapes can be pinned against *captured responses* rather
 * than against this file's own declarations — a test asserting the DTO matches
 * itself passes happily on a field read under the wrong name, which is exactly
 * how three blank columns shipped. Internal, and used by nothing in
 * production: the repository decodes through `ApiClient`, which applies the
 * same serializers.
 */
internal fun decodeLibraryPage(json: String): LibraryPage =
    docDistWireJson.decodeFromString(DocumentPageDto.serializer(), json).toDomain()

internal fun decodeFolders(json: String): List<LibraryFolder> =
    docDistWireJson.decodeFromString(ListSerializer(FolderDto.serializer()), json)
        .mapNotNull { it.toDomain() }

internal fun decodeDistributions(json: String): List<Distribution> =
    docDistWireJson.decodeFromString(ListSerializer(DistributionDto.serializer()), json)
        .mapNotNull { it.toDomain() }

/** Matches `HttpClientFactory.json` — lenient about keys this client does not read. */
private val docDistWireJson = Json { ignoreUnknownKeys = true }
