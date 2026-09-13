@file:Suppress("TooManyFunctions") // One function per route the web speaks.

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
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeDraft
import com.zillit.desktop.feature.esignature.domain.EnvelopeScope
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.EsignRepository
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.domain.TemplateDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The envelope service, routes under `api/v2/docusign` — the web's
 * `docusignApi.real.js`, call for call.
 *
 * Transcription notes, each verified against the web client's own source:
 *
 *  - **create is `send_now:false` + a separate send call** — the combined
 *    form exists but the web never uses it;
 *  - compose-time fields carry `recipient_index`, never a client-minted
 *    recipient id: the backend mints recipient ids during create and
 *    rejects anything that is not a 24-hex ObjectId;
 *  - `signed_fields` entries key by the tab's own `_id` (`tab_id`) —
 *    positional indexes are rejected — and a mark's value is the stored
 *    image descriptor itself;
 *  - checkbox values are the **strings** `true`/`false`;
 *  - list answers arrive bare or wrapped in `{items: []}`;
 *  - consent carries the disclosure version accepted, so the audit trail
 *    can name the exact wording the signer saw.
 */
class EsignRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Today, for `dateSigned` defaults — injected for tests. */
    private val today: () -> String,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    /**
     * A signed GET read as bytes, for the audit-trail PDF: the envelope
     * client only speaks JSON. Null answers that download with a refusal.
     */
    private val rawGet: (suspend (url: String) -> ZillitResult<ByteArray>)? = null,
) : EsignRepository {

    private val base = "${config.apiV2(ZillitService.ESignature).trimEnd('/')}/docusign"

    /**
     * See [EsignRepository.refreshes]. Conflated: the backend fires several
     * of these per state change (a completing signature emits `signed`,
     * `updated` and `completed` back to back) and one refetch answers all.
     */
    override val refreshes: Flow<Unit> =
        bus?.onAny(ESIGN_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()

    // ---------------------------------------------------------------- envelopes

    override suspend fun envelopes(
        scope: EnvelopeScope,
        bucket: String,
        userId: String?,
    ): ZillitResult<List<Envelope>> = get(
        "$base/envelopes",
        JsonElement.serializer(),
        queryParameters = buildMap {
            put("scope", scope.wire)
            put("bucket", bucket)
            userId?.takeIf { it.isNotBlank() }?.let { put("user_id", it) }
        },
    ).map { element -> element.rows("envelopes").decodeRows(EnvelopeDto.serializer()).mapNotNull { it.toDomain() } }

    override suspend fun envelope(id: String): ZillitResult<Envelope> =
        get("$base/envelopes/$id", EnvelopeDto.serializer()).asEnvelope("The envelope came back without an id.")

    override suspend fun create(draft: EnvelopeDraft): ZillitResult<Envelope> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/envelopes",
        serializer = EnvelopeDto.serializer(),
        module = RequestModule.ProjectUser,
        body = draft.toWire(),
    ).asEnvelope("Create answered without an envelope.")

    override suspend fun update(envelopeId: String, draft: EnvelopeDraft): ZillitResult<Envelope> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "$base/envelopes/$envelopeId",
            serializer = EnvelopeDto.serializer(),
            module = RequestModule.ProjectUser,
            body = draft.toWire(),
        ).asEnvelope("Update answered without an envelope.")

    override suspend fun send(envelopeId: String): ZillitResult<Unit> =
        post("$base/envelopes/$envelopeId/send", buildJsonObject { })

    override suspend fun deleteDraft(envelopeId: String): ZillitResult<Unit> =
        delete("$base/envelopes/$envelopeId")

    override suspend fun remind(envelopeId: String, recipientId: String?): ZillitResult<Unit> =
        post(
            "$base/envelopes/$envelopeId/resend",
            buildJsonObject { recipientId?.let { put("recipient_id", it) } },
        )

    override suspend fun markViewed(envelopeId: String): ZillitResult<Unit> =
        post("$base/envelopes/$envelopeId/mark-viewed", body = null)

    // The recipient is read from the session server-side; the body names
    // which disclosure wording was accepted (the web's consentDisclosure.js).
    override suspend fun acceptTerms(envelopeId: String): ZillitResult<Unit> =
        post(
            "$base/envelopes/$envelopeId/accept-terms",
            buildJsonObject {
                put("disclosure_version", CONSENT_DISCLOSURE_VERSION)
                put("disclosure_lang", CONSENT_DISCLOSURE_LANG)
            },
        )

    override suspend fun voidEnvelope(envelopeId: String, reason: String): ZillitResult<Unit> =
        post("$base/envelopes/$envelopeId/void", buildJsonObject { put("reason", reason) })

    override suspend fun sign(envelopeId: String, answers: List<SignedField>): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Patch,
            url = "$base/envelopes/$envelopeId/recipient-status",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("status", "signed")
                put("signed_fields", buildJsonArray { answers.forEach { add(signedFieldWire(it, today())) } })
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
                put("declined_reason", reason.ifBlank { "Recipient declined digital signature" })
            },
        ).map { }

    override suspend fun auditTrail(envelopeId: String): ZillitResult<List<AuditEntry>> =
        get("$base/envelopes/$envelopeId/audit-trail", JsonElement.serializer()).map { element ->
            // `trail`, `auditTrail`, `audit_trail`, `events`, `items`, or a bare
            // array — the web's own fallback chain, plus the list wrapper.
            element.rows("trail", "auditTrail", "audit_trail", "events")
                .decodeRows(AuditEntryDto.serializer())
                .map { it.toDomain() }
        }

    override suspend fun auditTrailPdf(envelopeId: String): ZillitResult<ByteArray> =
        rawGet?.invoke("$base/envelopes/$envelopeId/audit-trail/pdf")
            ?: ZillitResult.Failure(ZillitError.Validation("The audit trail PDF is not available here."))

    // ---------------------------------------------------------------- marks

    override suspend fun savedSignatures(): ZillitResult<List<SavedSignature>> =
        get("$base/saved-signatures", JsonElement.serializer()).map { element ->
            element.rows("signatures").decodeRows(SavedSignatureDto.serializer()).mapNotNull { it.toDomain() }
        }

    override suspend fun saveSignature(isSignature: Boolean, image: StoredFile): ZillitResult<Unit> = post(
        "$base/saved-signatures",
        buildJsonObject {
            put("kind", if (isSignature) "signature" else "initial")
            put("image", image.toWire())
        },
    )

    override suspend fun deleteSavedSignature(id: String): ZillitResult<Unit> =
        delete("$base/saved-signatures/$id")

    // ---------------------------------------------------------------- templates

    override suspend fun templates(): ZillitResult<List<EnvelopeTemplate>> =
        get(
            "$base/templates",
            JsonElement.serializer(),
            // Page 0, not 1: the service's first page (FE_INTEGRATION_GUIDE).
            queryParameters = mapOf("page" to 0, "limit" to TEMPLATE_PAGE_SIZE),
        ).map { element ->
            element.rows("templates").decodeRows(TemplateDto.serializer()).mapNotNull { it.toDomain() }
        }

    override suspend fun templateCategories(): ZillitResult<List<String>> =
        get("$base/templates/categories", JsonElement.serializer()).map { element ->
            element.rowsOf("categories").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
        }

    override suspend fun createTemplate(draft: TemplateDraft): ZillitResult<EnvelopeTemplate> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/templates",
            serializer = TemplateDto.serializer(),
            module = RequestModule.ProjectUser,
            body = draft.toWire(),
        ).asTemplate()

    override suspend fun updateTemplate(templateId: String, draft: TemplateDraft): ZillitResult<EnvelopeTemplate> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "$base/templates/$templateId",
            serializer = TemplateDto.serializer(),
            module = RequestModule.ProjectUser,
            body = draft.toWire(),
        ).asTemplate()

    override suspend fun deleteTemplate(templateId: String): ZillitResult<Unit> =
        delete("$base/templates/$templateId")

    // ---------------------------------------------------------------- bulk send

    override suspend fun startBulkSend(templateId: String, csvText: String, name: String?): ZillitResult<BulkJob> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/bulk-send",
            serializer = BulkJobDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("template_id", templateId)
                put("csv_text", csvText)
                put("send_immediately", true)
                name?.trim()?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            },
        ).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Validation("The bulk send answered without a job id."))
        }

    override suspend fun bulkJobs(): ZillitResult<List<BulkJob>> =
        get("$base/bulk-jobs", JsonElement.serializer()).map { element ->
            element.rows("jobs", "bulk_jobs").decodeRows(BulkJobDto.serializer()).mapNotNull { it.toDomain() }
        }

    override suspend fun bulkJob(jobId: String): ZillitResult<BulkJob> =
        get("$base/bulk-jobs/$jobId", BulkJobDto.serializer()).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Validation("The job came back without an id."))
        }

    override suspend fun retryFailedRows(jobId: String): ZillitResult<Unit> =
        post("$base/bulk-jobs/$jobId/retry-failed", body = null)

    override suspend fun remindOutstanding(jobId: String): ZillitResult<Int> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/bulk-jobs/$jobId/remind-outstanding",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = null,
        ).map { element -> ((element as? JsonObject)?.get("reminded") as? JsonPrimitive)?.intOrNull ?: 0 }

    // ---------------------------------------------------------------- plumbing

    private suspend fun <T> get(
        url: String,
        serializer: KSerializer<T>,
        queryParameters: Map<String, Any?> = emptyMap(),
    ): ZillitResult<T> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = serializer,
        module = RequestModule.ProjectUser,
        queryParameters = queryParameters,
    )

    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = url,
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = body,
        ).map { }

    private suspend fun delete(url: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = url,
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { }

    private fun ZillitResult<EnvelopeDto>.asEnvelope(orElse: String): ZillitResult<Envelope> = flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) } ?: ZillitResult.Failure(ZillitError.Validation(orElse))
    }

    private fun ZillitResult<TemplateDto>.asTemplate(): ZillitResult<EnvelopeTemplate> = flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Validation("The template came back without an id."))
    }

    private companion object {
        const val TEMPLATE_PAGE_SIZE = 100
        const val CONSENT_DISCLOSURE_VERSION = "zillit-esign-consent-v1"
        const val CONSENT_DISCLOSURE_LANG = "en"
    }
}

/** The bucket vocabulary per scope, as the web fetches its lists. */
object EnvelopeBuckets {
    val sent: List<String> = listOf("draft", "sent", "completed", "rejected")
    val received: List<String> = listOf("received", "completed", "rejected")
}
