package com.zillit.desktop.feature.esignature.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeScope
import com.zillit.desktop.feature.esignature.domain.EsignRepository
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.NewField
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.core.socket.SocketEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The envelope service (`esignatureapi`), routes under `api/v2/docusign`.
 *
 * Transcription notes, each verified against the web client's own source:
 *
 *  - **create is `send_now:false` + a separate send call** — the combined
 *    form exists but the web never uses it;
 *  - compose-time fields carry `recipient_index`, never a recipient id:
 *    the backend mints recipient ids during create and rejects anything
 *    that is not a 24-hex ObjectId;
 *  - `signed_fields` entries key by the tab's own `_id` (`tab_id`) —
 *    positional indexes are rejected — and a mark's value is the stored
 *    image descriptor itself;
 *  - checkbox values are the **strings** `true`/`false`;
 *  - list answers arrive bare or wrapped in `{items: []}`.
 */
class EsignRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Today, for `dateSigned` defaults — injected for tests. */
    private val today: () -> String,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
) : EsignRepository {

    private val base = "${config.apiV2(ZillitService.ESignature).trimEnd('/')}/docusign"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * See [EsignRepository.refreshes]. Conflated: the backend fires several
     * of these per state change (a completing signature emits `signed`,
     * `updated` and `completed` back to back) and one refetch answers all.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(ESIGN_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()

    override suspend fun envelopes(
        scope: EnvelopeScope,
        bucket: String,
        userId: String?,
    ): ZillitResult<List<Envelope>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/envelopes",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = buildMap {
            put("scope", scope.wire)
            put("bucket", bucket)
            userId?.takeIf { it.isNotBlank() }?.let { put("user_id", it) }
        },
    ).map { element -> envelopeList(element) }

    private fun envelopeList(element: JsonElement): List<Envelope> {
        val array: JsonArray = when {
            element is JsonArray -> element
            element is JsonObject && element["items"] is JsonArray -> element["items"]!!.jsonArray
            else -> return emptyList()
        }
        return array.mapNotNull { row ->
            runCatching {
                json.decodeFromJsonElement(EnvelopeDto.serializer(), row.jsonObject)
            }.getOrNull()?.toDomain()
        }
    }

    override suspend fun envelope(id: String): ZillitResult<Envelope> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/envelopes/$id",
        serializer = EnvelopeDto.serializer(),
        module = RequestModule.ProjectUser,
    ).flatMap { dto ->
        dto.toDomain()
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Validation("The envelope came back without an id."))
    }

    @Suppress("LongMethod") // One JSON body, spelled out key by key.
    override suspend fun create(
        title: String,
        description: String,
        document: StoredFile,
        recipients: List<EnvelopeRecipient>,
        fields: List<NewField>,
        initialsOnAllPages: Boolean,
        reminderCadenceDays: Int?,
    ): ZillitResult<Envelope> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/envelopes",
        serializer = EnvelopeDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("title", title)
            put("description", description)
            put("attachment", document.toWire(includePageCount = true))
            // The phones' newer envelope options (Android `CreateDocuSignEnvelopeRequest`).
            put("initials_on_all_pages", initialsOnAllPages)
            reminderCadenceDays?.let { put("reminder_cadence_days", it) }
            put(
                "recipients",
                buildJsonArray {
                    recipients.forEach { recipient ->
                        add(
                            buildJsonObject {
                                put("name", recipient.name)
                                put("email", recipient.email)
                                put("role", recipient.role)
                                put("routing_order", recipient.routingOrder)
                                put("user_id", recipient.userId)
                                put("status", "created")
                                put("is_external", recipient.isExternal)
                            },
                        )
                    }
                },
            )
            put(
                "tabs",
                buildJsonArray {
                    fields.forEach { field ->
                        add(
                            buildJsonObject {
                                put("type", field.type.wire)
                                put("page", field.page)
                                put("x", field.x)
                                put("y", field.y)
                                put("width", field.width)
                                put("height", field.height)
                                put("recipient_index", field.recipientIndex)
                                put("placement_mode", "coordinate")
                                put("document_index", 0)
                            },
                        )
                    }
                },
            )
            put(
                "settings",
                buildJsonObject {
                    put("email_subject", title)
                    put("email_body", description)
                    put("enable_reminders", true)
                },
            )
            put("send_now", false)
        },
    ).flatMap { dto ->
        dto.toDomain()
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Validation("Create answered without an envelope."))
    }

    override suspend fun send(envelopeId: String): ZillitResult<Unit> =
        post("$base/envelopes/$envelopeId/send", buildJsonObject { })

    override suspend fun deleteDraft(envelopeId: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "$base/envelopes/$envelopeId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { }

    override suspend fun remind(envelopeId: String, recipientId: String?): ZillitResult<Unit> =
        post(
            "$base/envelopes/$envelopeId/resend",
            buildJsonObject { recipientId?.let { put("recipient_id", it) } },
        )

    override suspend fun markViewed(envelopeId: String): ZillitResult<Unit> =
        post("$base/envelopes/$envelopeId/mark-viewed", body = null)

    override suspend fun sign(
        envelopeId: String,
        answers: List<SignedField>,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Patch,
        url = "$base/envelopes/$envelopeId/recipient-status",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("status", "signed")
            put("signed_fields", buildJsonArray { answers.forEach { add(it.toWire()) } })
        },
    ).map { }

    override suspend fun decline(envelopeId: String, reason: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Patch,
            url = "$base/envelopes/$envelopeId/recipient-status",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("status", "declined")
                put(
                    "declined_reason",
                    reason.ifBlank { "Recipient declined digital signature" },
                )
            },
        ).map { }

    override suspend fun auditTrail(envelopeId: String): ZillitResult<List<AuditEntry>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/envelopes/$envelopeId/audit-trail",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { element -> auditEntries(element) }

    private fun auditEntries(element: JsonElement): List<AuditEntry> {
        // `trail`, `auditTrail`, `audit_trail`, `items`, or a bare array —
        // the web's own fallback chain, plus the list wrapper.
        val array: JsonArray = when {
            element is JsonArray -> element
            element is JsonObject -> sequenceOf("trail", "auditTrail", "audit_trail", "items")
                .mapNotNull { key -> element[key] as? JsonArray }
                .firstOrNull() ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { row ->
            runCatching {
                json.decodeFromJsonElement(AuditEntryDto.serializer(), row.jsonObject)
            }.getOrNull()?.toDomain()
        }
    }

    override suspend fun savedSignatures(): ZillitResult<List<SavedSignature>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/saved-signatures",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element ->
        val array: JsonArray = when {
            element is JsonArray -> element
            element is JsonObject && element["items"] is JsonArray -> element["items"]!!.jsonArray
            else -> return@map emptyList()
        }
        array.mapNotNull { row ->
            runCatching {
                json.decodeFromJsonElement(SavedSignatureDto.serializer(), row.jsonObject)
            }.getOrNull()?.toDomain()
        }
    }

    override suspend fun saveSignature(
        isSignature: Boolean,
        image: StoredFile,
    ): ZillitResult<Unit> = post(
        "$base/saved-signatures",
        buildJsonObject {
            put("kind", if (isSignature) "signature" else "initial")
            put("image", image.toWire())
        },
    )

    override suspend fun deleteSavedSignature(id: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "$base/saved-signatures/$id",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { }

    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = url,
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = body,
        ).map { }

    private fun SignedField.toWire(): JsonObject = signedFieldWire(this, today())
}

/** The bucket vocabulary per scope, as the web fetches its lists. */
object EnvelopeBuckets {
    val sent: List<String> = listOf("draft", "sent", "completed", "rejected")
    val received: List<String> = listOf("received", "completed", "rejected")
}
