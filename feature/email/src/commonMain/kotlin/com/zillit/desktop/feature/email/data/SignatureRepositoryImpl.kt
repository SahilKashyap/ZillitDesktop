package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
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
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.SignatureRepository

/** Sign-offs appended to outgoing mail. */
class SignatureRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** The shared Accounts mailbox keeps its own sign-offs. */
    private val scope: MailboxScope = MailboxScope.Personal,
) : SignatureRepository {

    private val api get() = config.apiV2(ZillitService.Email)

    override suspend fun signatures(): ZillitResult<List<EmailSignature>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}email-signature",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
        ).map { payload -> payload.signatureRows().mapNotNull(::readSignature) }

    override suspend fun create(title: String, body: String): ZillitResult<EmailSignature> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}email-signature/create",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("signature_title", title)
                        put("signature_body", body)
                        // A new signature is not made automatic. Silently taking
                        // over someone's mail because they added a second one is
                        // the kind of surprise nobody wants from a sign-off.
                        put("use_for_new_email", false)
                        put("use_for_reply_and_forward", false)
                    },
                ),
            ),
        ).map { payload ->
            readSignature(payload) ?: EmailSignature(id = "", title = title, body = body)
        }

    override suspend fun update(id: String, title: String, body: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "${api}email-signature/update",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("_id", id)
                        put("signature_title", title)
                        put("signature_body", body)
                    },
                ),
            ),
        ).map { }

    override suspend fun setUsage(
        id: String,
        useForNew: Boolean,
        useForReply: Boolean,
    ): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "${api}email-signature/update-usage-flags",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("_id", id)
                        put("use_for_new_email", useForNew)
                        put("use_for_reply_and_forward", useForReply)
                    },
                ),
            ),
        ).map { }

    override suspend fun delete(id: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "${api}email-signature/$id",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = scope.flagBody()?.let(::jsonBody),
        ).map { }
}

/** Signatures come back as `{"signatures": [...]}`, or as a bare array. */
private fun JsonElement.signatureRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject ->
        (this["signatures"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: listOf(this)
    else -> emptyList()
}
