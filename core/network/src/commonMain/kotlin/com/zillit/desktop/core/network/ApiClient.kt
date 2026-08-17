package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The two things a call can say about itself that most calls never need to.
 *
 * Separated from the call's own parameters because they are properties of *this
 * invocation*, not of the endpoint — the same URL is fetched both ways.
 */
data class CallOptions(
    /**
     * False for one caller: the check that asks whether this device is still
     * registered. That call must not raise `onUnauthorized`, because it is the
     * thing answering it — reporting would make the question re-ask itself
     * forever.
     */
    val reportUnauthorized: Boolean = true,

    /**
     * The production this call is about, when that is not the open one.
     *
     * Joining is the case that needs it: the request has to be scoped to a
     * production the user is asking to enter, and moving the whole app onto it
     * first would clear caches and open a socket for a production they have
     * not been admitted to.
     */
    val projectId: String? = null,
)

/**
 * One entry point for every REST call.
 *
 * The Android client has eleven near-identical repository methods
 * (`getRequest`, `getRequestWithParam`, `putRequest` ×2, `deleteRequestWithParam`
 * ×2 …) that differ only in verb and payload shape, each repeating the same
 * error handling. This collapses them into [request], with the differences
 * expressed as parameters.
 */
class ApiClient(
    private val httpClient: HttpClient,
    private val headerProvider: RequestHeaderProvider,
    /**
     * Called whenever the server rejects a call as unauthenticated.
     *
     * Raised here because this is the one place a 401 is recognised — leaving
     * it to callers would mean every repository carrying its own copy of "and
     * if this was a 401, the session is gone".
     *
     * This reports the fact, not what to do about it. Signing in produces 401s
     * too (a wrong code, an unregistered device), so whether one means the
     * session expired depends on state this class does not have.
     */
    private val onUnauthorized: () -> Unit = {},
) {

    /**
     * Performs a call and decodes the envelope's `data` with [serializer].
     *
     * Failures are returned as [ZillitError], never thrown — except
     * [CancellationException], which must propagate so coroutine cancellation
     * keeps working.
     */
    suspend fun <T> request(
        verb: HttpVerb,
        url: String,
        serializer: KSerializer<T>,
        module: RequestModule = RequestModule.Default,
        body: JsonElement? = null,
        queryParameters: Map<String, Any?> = emptyMap(),
        options: CallOptions = CallOptions(),
    ): ZillitResult<T> =
        envelope(verb, url, module, body, queryParameters, options).flatMapEnvelope { data ->
            decode(data, serializer)
        }

    /**
     * As [request], but where the resource legitimately may not exist.
     *
     * Several endpoints answer `200` with `"data": null` to mean "there isn't
     * one" — a crew member's deal before it is drafted, the current week before
     * a timecard is opened. Through [request] that reads as a decode failure
     * and the screen shows an error for what is an ordinary, expected state, so
     * those callers ask for this instead and get a null they can render.
     *
     * Only an absent `data` becomes null. A `data` that is present but does not
     * decode is still a failure — that one really is a bug.
     */
    suspend fun <T : Any> requestOrNull(
        verb: HttpVerb,
        url: String,
        serializer: KSerializer<T>,
        module: RequestModule = RequestModule.Default,
        body: JsonElement? = null,
        queryParameters: Map<String, Any?> = emptyMap(),
        options: CallOptions = CallOptions(),
    ): ZillitResult<T?> =
        envelope(verb, url, module, body, queryParameters, options).flatMapEnvelope { data ->
            if (data == null || data is JsonNull) {
                ZillitResult.Success(null)
            } else {
                decode(data, serializer)
            }
        }

    /**
     * Performs a call and returns the raw envelope, for endpoints with no `data`.
     *
     * See [CallOptions] for the two things a call can say about itself that
     * most calls never need to.
     */
    suspend fun envelope(
        verb: HttpVerb,
        url: String,
        module: RequestModule = RequestModule.Default,
        body: JsonElement? = null,
        queryParameters: Map<String, Any?> = emptyMap(),
        options: CallOptions = CallOptions(),
    ): ZillitResult<ApiEnvelope> = try {
        // The body is serialised up front because `bodyhash` is a digest over
        // it — headers cannot be built before the body exists. Ktor would
        // otherwise serialise it later, inside the builder.
        val bodyJson = body?.let { HttpClientFactory.json.encodeToString(JsonElement.serializer(), it) }

        // Headers are resolved before the request builder runs: building them
        // can suspend (the provider may need to derive an encrypted header or
        // refresh a token) and the builder lambda is not a suspend context.
        val resolvedHeaders = headerProvider.headersFor(module, bodyJson, options.projectId)

        val response = httpClient.request(url) {
            method = verb.toKtor()
            resolvedHeaders.forEach { (name, value) -> headers.append(name, value) }
            queryParameters.forEach { (key, value) -> value?.let { parameter(key, it) } }
            if (bodyJson != null) {
                contentType(ContentType.Application.Json)
                // The already-serialised string, so the bytes on the wire are
                // byte-identical to what `bodyhash` was computed over. Letting
                // Ktor re-serialise could reorder keys and invalidate the hash.
                setBody(bodyJson)
            }
        }
        response.toEnvelope(options.reportUnauthorized)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        ZillitResult.Failure(throwable.toZillitError())
    }

    private suspend fun HttpResponse.toEnvelope(reportUnauthorized: Boolean): ZillitResult<ApiEnvelope> {
        if (!status.isSuccess()) {
            val raw = runCatching { bodyAsText() }.getOrNull()
            // Decoded once, for both halves: the message names the failure and
            // `messageElements` fills the blanks in it. Reading only the first
            // leaves a reader looking at `{{status}}`.
            val failure = raw?.let { text ->
                runCatching { HttpClientFactory.json.decodeFromString(ApiEnvelope.serializer(), text) }
                    .getOrNull()
            }
            val serverMessage = failure?.message
            // Reached via `call` rather than the `HttpResponse.request`
            // extension, which the imported `HttpClient.request` shadows here.
            val path = call.request.url.encodedPath
            ZillitLog.w(TAG) { "${status.value} $path" }
            if (status.value == STATUS_UNAUTHORIZED && reportUnauthorized) onUnauthorized()

            return ZillitResult.Failure(
                when (status.value) {
                    STATUS_UNAUTHORIZED -> ZillitError.Unauthorized(serverMessage)
                    STATUS_FORBIDDEN -> ZillitError.Forbidden(serverMessage)
                    else -> ZillitError.Http(
                        status = status.value,
                        serverMessage = serverMessage,
                        messageElements = failure?.messageElements.orEmpty(),
                    )
                },
            )
        }

        return try {
            ZillitResult.Success(body<ApiEnvelope>())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitResult.Failure(ZillitError.Serialization(throwable.message))
        }
    }

    private fun <T> decode(data: JsonElement?, serializer: KSerializer<T>): ZillitResult<T> {
        if (data == null) {
            return ZillitResult.Failure(ZillitError.Serialization("response had no data field"))
        }
        return try {
            ZillitResult.Success(HttpClientFactory.json.decodeFromJsonElement(serializer, data))
        } catch (serialization: SerializationException) {
            ZillitLog.e(TAG, serialization) { "decode failed" }
            ZillitResult.Failure(ZillitError.Serialization(serialization.message))
        } catch (illegalArgument: IllegalArgumentException) {
            ZillitResult.Failure(ZillitError.Serialization(illegalArgument.message))
        }
    }

    private inline fun <T> ZillitResult<ApiEnvelope>.flatMapEnvelope(
        transform: (JsonElement?) -> ZillitResult<T>,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Success -> transform(data.data)
        is ZillitResult.Failure -> this
    }

    private fun HttpVerb.toKtor(): HttpMethod = when (this) {
        HttpVerb.Get -> HttpMethod.Get
        HttpVerb.Post -> HttpMethod.Post
        HttpVerb.Put -> HttpMethod.Put
        HttpVerb.Patch -> HttpMethod.Patch
        HttpVerb.Delete -> HttpMethod.Delete
    }

    private companion object {
        const val TAG = "ApiClient"
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_FORBIDDEN = 403
    }
}

/**
 * Converts a request body to the [JsonElement] [ApiClient] takes.
 *
 * Bodies are pre-encoded rather than handed to Ktor as arbitrary objects
 * because the `bodyhash` header is a digest over the body: the exact bytes must
 * exist before headers are built, and the same bytes must go on the wire. An
 * inline reified helper keeps that compile-time checked — no reflection, and a
 * body that is not serialisable fails to compile rather than at runtime.
 */
inline fun <reified T> jsonBody(value: T): JsonElement =
    HttpClientFactory.json.encodeToJsonElement(value)
