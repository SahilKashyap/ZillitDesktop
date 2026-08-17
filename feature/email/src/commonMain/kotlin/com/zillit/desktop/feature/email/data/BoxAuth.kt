package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mints Box upload tokens. */
fun interface BoxTokenSource {
    suspend fun token(enterpriseClientId: String): ZillitResult<String>
}

/**
 * `POST media/v2/box/auth`, which exchanges the production's Box tenant for a
 * short-lived upload token.
 *
 * Not cached. Box access tokens expire in about an hour and there is nothing in
 * the response saying when — so caching would mean holding one until an upload
 * failed, which is exactly when the user least wants to find out.
 */
class BoxAuthSource(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : BoxTokenSource {

    override suspend fun token(enterpriseClientId: String): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${config.apiV2(ZillitService.Media)}box/auth",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(buildJsonObject { put("enterprise_client_id", enterpriseClientId) }),
        ).map { payload -> (payload as? JsonObject)?.str("access_token").orEmpty() }
            .flatMapBlank()
}

/** A blank token is a failure, not a token. */
private fun ZillitResult<String>.flatMapBlank(): ZillitResult<String> = when (this) {
    is ZillitResult.Failure -> this
    is ZillitResult.Success -> if (data.isBlank()) {
        ZillitResult.Failure(
            ZillitError.Storage(
                technical = "box/auth returned no access_token",
                userMessage = "Could not reach this production's file storage.",
            ),
        )
    } else {
        this
    }
}
