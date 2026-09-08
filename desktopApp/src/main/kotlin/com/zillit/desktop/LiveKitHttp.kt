package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestHeaderProvider
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.calls.data.livekit.SignedJsonHttp
import com.zillit.desktop.feature.calls.data.livekit.liveKitHttpError
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
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
 */
internal class LiveKitHttp(
    private val httpClient: HttpClient,
    private val headerProvider: RequestHeaderProvider,
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
        return try {
            val response = httpClient.request(url) {
                method = when (verb) {
                    HttpVerb.Get -> HttpMethod.Get
                    HttpVerb.Post -> HttpMethod.Post
                    HttpVerb.Put -> HttpMethod.Put
                    HttpVerb.Patch -> HttpMethod.Patch
                    HttpVerb.Delete -> HttpMethod.Delete
                }
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                if (bodyJson != null) {
                    contentType(ContentType.Application.Json)
                    setBody(bodyJson)
                }
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
}
