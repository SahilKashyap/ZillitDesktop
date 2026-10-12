package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestHeaderProvider
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.network.tokenauth.TokenScope
import com.zillit.desktop.core.network.tokenauth.TokenSessionManager
import com.zillit.desktop.feature.calls.data.livekit.CallBearer
import com.zillit.desktop.feature.calls.data.livekit.LiveKitLine
import com.zillit.desktop.feature.calls.data.livekit.SignedJsonHttp
import com.zillit.desktop.feature.calls.data.livekit.liveKitHttpError
import com.zillit.desktop.feature.calls.data.livekit.signed
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * [SignedJsonHttp] over the app's HTTP client and header provider.
 *
 * The same headers every Zillit request carries — `moduledata` with device,
 * project and user, `deviceInfo`, and the `bodyhash` over the serialised
 * body — built for [RequestModule.LiveKit], which the header provider already
 * knows. What differs from `ApiClient` is only the answer: read as the JSON
 * the calling backend actually sends, not as an envelope.
 *
 * In token mode the production's Bearer rides ALONGSIDE them ([signed]), as
 * Android's `ModuleDataInterceptor` sends it: the calling backend's `/v1`
 * routes take the project token, and one not yet migrated still reads the
 * legacy header. `ApiClient` sends one or the other because the core API
 * never decrypts `moduledata` on its token path; the calling backend is a
 * different server, and the phones send both to it.
 */
internal class LiveKitHttp(
    private val httpClient: HttpClient,
    private val headerProvider: RequestHeaderProvider,
    /** The project token, when token mode has one. Null keeps every request on `moduledata` alone. */
    private val bearer: CallBearer? = null,
    /** The open production, for a request that names none — whose token it then rides. */
    private val ambientProjectId: () -> String? = { null },
) : SignedJsonHttp {

    override suspend fun call(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
        projectId: String?,
        userId: String?,
    ): ZillitResult<JsonElement?> {
        val bodyJson = body?.let { HttpClientFactory.json.encodeToString(JsonElement.serializer(), it) }
        val headers = headerProvider.headersFor(RequestModule.LiveKit, bodyJson, projectId, userId)
        val production = projectId?.takeIf { it.isNotBlank() } ?: ambientProjectId()
        return try {
            val response = bearer.signed(production, statusOf = { it.status.value }) { token ->
                perform(verb, url, headers, token, bodyJson)
            }
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                ZillitResult.Success(
                    text.takeIf { it.isNotBlank() }?.let { HttpClientFactory.json.parseToJsonElement(it) },
                )
            } else {
                ZillitResult.Failure(liveKitHttpError(response.status.value, text))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            ZillitResult.Failure(
                ZillitError.NoConnection(technical = "call-api ${thrown::class.simpleName}: ${thrown.message}"),
            )
        }
    }

    private suspend fun perform(
        verb: HttpVerb,
        url: String,
        headers: Map<String, String>,
        token: String?,
        bodyJson: String?,
    ): HttpResponse = httpClient.request(url) {
        method = when (verb) {
            HttpVerb.Get -> HttpMethod.Get
            HttpVerb.Post -> HttpMethod.Post
            HttpVerb.Put -> HttpMethod.Put
            HttpVerb.Patch -> HttpMethod.Patch
            HttpVerb.Delete -> HttpMethod.Delete
        }
        headers.forEach { (name, value) -> this.headers.append(name, value) }
        // What the calling backend's ops dashboard reports this client as.
        this.headers.append(LiveKitLine.HEADER_PLATFORM, LiveKitLine.PLATFORM)
        token?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
        if (bodyJson != null) {
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }
    }
}

/**
 * The calling backend's Bearer from the app's token session: the PROJECT
 * token, only in token mode, and never the device token, which `/v1` refuses.
 */
internal fun TokenSessionManager.callBearer(): CallBearer = object : CallBearer {
    override suspend fun tokenFor(projectId: String): String? =
        if (tokenMode) bearerTokenFor(TokenScope.Project(projectId)) else null

    override suspend fun renew(projectId: String, failed: String): String? =
        recoverFromUnauthorized(TokenScope.Project(projectId), failed)
}
