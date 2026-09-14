package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import com.zillit.desktop.feature.email.domain.StoredFile

/**
 * Saved, unsent messages.
 *
 * A different API generation from the rest of mail: `email-draft` is Zillit's
 * own store, paged by timestamp, and nothing here goes near IMAP. Its own class
 * for the same reason it is its own port.
 */
class DraftRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** Which mailbox's drafts — the shared Accounts mailbox keeps its own. */
    private val scope: MailboxScope = MailboxScope.Personal,
    /** The `From:` the draft is saved with; see `EmailRepositoryImpl`. */
    private val fromHeader: () -> String = { "" },
) : DraftRepository {

    private val api get() = config.apiV2(ZillitService.Email)
    //
    // A different API generation from everything above: `email-draft` is
    // Zillit's own store, paged by timestamp, and nothing here goes near IMAP.

    override suspend fun drafts(beforeMillis: Long): ZillitResult<List<EmailDraft>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}email-draft/$beforeMillis/previous",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            // Only ever the newest page, from "now": one name, so the Drafts
            // folder still shows offline what it showed last time.
            options = CallOptions(cacheAs = "${api}email-draft/newest${scope.cacheSuffix()}"),
        ).map { payload ->
            payload.draftRows()
                .mapNotNull(::readDraft)
                .sortedByDescending { it.updatedAtMillis }
        }

    override suspend fun saveDraft(message: OutgoingEmail, uniqueId: String): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}email-draft",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            // `unique_id` makes the create idempotent: the same key twice
            // answers with the record the first one made, not a second one.
            // The key goes only on a create — an update names its draft in
            // the URL, and the server ignores a key on a PUT.
            body = jsonBody(scope.body(message.toDraftPayload(fromHeader(), uniqueId))),
        ).map { payload ->
            // The id is what makes the next autosave an update rather than a
            // second draft, so a response without one is worth noticing.
            val row = payload as? JsonObject
            val id = row?.let { it.str("_id") ?: it.str("id") }
            if (id == null) ZillitLog.w(TAG) { "draft saved but the server returned no id" }
            // A service without the idempotent create echoes no key. Worth a
            // line in the log, because that service will still duplicate on
            // a retry and nothing else would say so.
            val echoed = row?.str("unique_id")
            if (echoed != uniqueId) ZillitLog.w(TAG) { "draft create did not echo unique_id (got $echoed)" }
            id.orEmpty()
        }

    override suspend fun updateDraft(draftId: String, message: OutgoingEmail): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "${api}email-draft/$draftId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(scope.body(message.toDraftPayload(fromHeader()))),
        ).map { }

    override suspend fun deleteDrafts(draftIds: List<String>): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "${api}email-draft",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("draft_ids", buildJsonArray { draftIds.forEach { add(JsonPrimitive(it)) } })
                    },
                ),
            ),
        ).map { }
}

/**
 * The draft payload.
 *
 * Same shape as a send minus `email_draft_id` — the id goes in the URL on an
 * update, and a new draft does not have one yet. [uniqueId] is set on a create
 * only; see [DraftRepository.saveDraft].
 */
private fun OutgoingEmail.toDraftPayload(from: String, uniqueId: String? = null): JsonObject = buildJsonObject {
    if (uniqueId != null) put("unique_id", uniqueId)
    put("to", addressArray(to))
    put("cc", addressArray(cc))
    put("bcc", addressArray(bcc))
    if (from.isNotBlank()) put("from", from)
    put("subject", subject)
    put("body", body)
    put("references", references.joinToString(" "))
    // Both file lists ride the draft, as on the web, so a reopened draft
    // still carries what was attached — and the original's files on a
    // half-written reply.
    put("attachments", buildJsonArray { attachments.forEach { add(it.toWire()) } })
    put("ingrained_attachment", buildJsonArray { forwarded.forEach { add(it.toWire()) } })
}

/** The uploaded file as the send payload names it; shared with `imap-send`. */
internal fun StoredFile.toWire(): JsonObject = buildJsonObject {
    // `media` is the object key. The name is historical and both other
    // clients send it, so it stays.
    put("media", media)
    put("bucket", bucket)
    put("region", region)
    put("name", fileName)
    put("content_type", contentType)
    put("content_length", sizeBytes)
    put("content_disposition", "attachment")
    put("size", sizeBytes)
}

/** Drafts come back as `{"drafts": [...]}`; a bare array is accepted too. */
private fun JsonElement.draftRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["drafts"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: emptyList()
    else -> emptyList()
}

private const val TAG = "Email"
