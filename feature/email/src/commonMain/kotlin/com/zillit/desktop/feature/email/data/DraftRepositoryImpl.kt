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
import com.zillit.desktop.feature.email.domain.OutgoingEmail

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
            // Only ever the newest page, from "now": one name, so the Drafts
            // folder still shows offline what it showed last time.
            options = CallOptions(cacheAs = "${api}email-draft/newest"),
        ).map { payload ->
            payload.draftRows()
                .mapNotNull(::readDraft)
                .sortedByDescending { it.updatedAtMillis }
        }

    override suspend fun saveDraft(message: OutgoingEmail): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}email-draft",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(message.toDraftPayload()),
        ).map { payload ->
            // The id is what makes the next autosave an update rather than a
            // second draft, so a response without one is worth noticing.
            val id = (payload as? JsonObject)?.let { it.str("_id") ?: it.str("id") }
            if (id == null) ZillitLog.w(TAG) { "draft saved but the server returned no id" }
            id.orEmpty()
        }

    override suspend fun updateDraft(draftId: String, message: OutgoingEmail): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "${api}email-draft/$draftId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(message.toDraftPayload()),
        ).map { }

    override suspend fun deleteDrafts(draftIds: List<String>): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "${api}email-draft",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(
                buildJsonObject {
                    put("draft_ids", buildJsonArray { draftIds.forEach { add(JsonPrimitive(it)) } })
                },
            ),
        ).map { }
}

/**
 * The draft payload.
 *
 * Same shape as a send minus `email_draft_id` — the id goes in the URL on an
 * update, and a new draft does not have one yet.
 */
private fun OutgoingEmail.toDraftPayload(): JsonObject = buildJsonObject {
    put("to", addressArray(to))
    put("cc", addressArray(cc))
    put("bcc", addressArray(bcc))
    put("subject", subject)
    put("body", body)
    put("references", references.joinToString(" "))
}

/** Drafts come back as `{"drafts": [...]}`; a bare array is accepted too. */
private fun JsonElement.draftRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["drafts"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: emptyList()
    else -> emptyList()
}

private const val TAG = "Email"
