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

    /**
     * The caller's id on [projectId], for calls scoped to another production
     * (`ProjectUser` headers carry a per-production user id). Ignored when
     * [projectId] is not set.
     */
    val userId: String? = null,

    /**
     * Whether a GET's answer may be kept and, with the network gone, shown
     * again. On by default: the point of the read cache is that every screen
     * shows what it showed last time. Off for the few reads whose stale
     * answer would mislead — a device or session check must never come from
     * disk.
     */
    val readCache: Boolean = true,

    /**
     * What this read is a read *of*, when the URL carries something that is
     * not part of the question — a "now" timestamp, a nonce. The Home board's
     * newest page is `chat/<unit>/<now>/previous`: a different URL every time,
     * the same question every time. Without this the cache would keep an
     * answer nobody ever asks for again. Scoped like any other key; the URL
     * and query are ignored when it is set.
     *
     * Also the one way a **POST** is kept: a POST that is really a read (the
     * mail server's `get-emails` takes its message ids in the body) says so by
     * naming itself, and the name must carry everything the body asks for.
     */
    val cacheAs: String? = null,
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
    /**
     * Told how every call ended — null for an answer, the error otherwise.
     *
     * The app's only honest "are we online?" signal: a transport failure here
     * means the network is gone, any answer at all means it is back. Reported
     * from the one place every call passes through, so no caller has to
     * remember to.
     */
    private val onOutcome: (ZillitError?) -> Unit = {},
    /**
     * Where GET answers are kept for offline viewing; null keeps every read
     * live-only, as before the cache existed.
     */
    private val readCache: ReadCache? = null,
    /** Who is asking right now — the key's first two parts. Null before sign-in. */
    private val readScope: () -> ReadScope? = { null },
    private val nowMillis: () -> Long = { 0L },
    /**
     * The Bearer credential in token mode, or null to stay on `moduledata`
     * for every call — see [RequestAuthenticator].
     */
    private val authenticator: RequestAuthenticator? = null,
    /**
     * Told about every call that failed — the error log (`location/log`)
     * records these, as the phones do from their own network layer.
     */
    private val onFailure: (ApiFailure) -> Unit = {},
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
    ): ZillitResult<ApiEnvelope> {
        val outcome = send(verb, url, module, body, queryParameters, options)
        onOutcome((outcome as? ZillitResult.Failure)?.error)
        (outcome as? ZillitResult.Failure)?.let { onFailure(ApiFailure(verb.wire, url, it.error)) }
        return remembered(outcome, verb, url, module, queryParameters, options)
    }

    /**
     * The read cache, around a GET.
     *
     * A good answer is kept under (user, production, url, query). When the
     * same read cannot reach the server — no connection, a timeout — the kept
     * answer stands in. A refusal (4xx/5xx) is *not* offline and is returned
     * as is: showing a saved list when the server just said "forbidden" would
     * be a lie about rights.
     */
    private suspend fun remembered(
        outcome: ZillitResult<ApiEnvelope>,
        verb: HttpVerb,
        url: String,
        module: RequestModule,
        queryParameters: Map<String, Any?>,
        options: CallOptions,
    ): ZillitResult<ApiEnvelope> {
        val cache = readCache ?: return outcome
        val scope = readScope() ?: return outcome
        if (!options.isRead(verb) || module !in CACHEABLE_MODULES) return outcome
        val projectId = options.projectId ?: scope.projectId
        val name = options.cacheAs
        val key = if (name != null) {
            readKey(scope, projectId, name, emptyMap())
        } else {
            readKey(scope, projectId, url, queryParameters)
        }
        return when (outcome) {
            is ZillitResult.Success -> {
                keep(cache, key, scope.copy(projectId = projectId), outcome.data)
                outcome
            }

            is ZillitResult.Failure ->
                if (outcome.error.isUnreachable()) recall(cache, key, url) ?: outcome else outcome
        }
    }

    /** A GET, or a POST that named itself a read — and not opted out. */
    private fun CallOptions.isRead(verb: HttpVerb): Boolean =
        readCache && (verb == HttpVerb.Get || (verb == HttpVerb.Post && cacheAs != null))

    private suspend fun keep(cache: ReadCache, key: String, scope: ReadScope, envelope: ApiEnvelope) {
        val json = HttpClientFactory.json.encodeToString(ApiEnvelope.serializer(), envelope)
        if (json.length <= MAX_CACHED_BODY_CHARS) cache.put(key, scope, json, nowMillis())
    }

    private suspend fun recall(cache: ReadCache, key: String, url: String): ZillitResult<ApiEnvelope>? {
        val kept = cache.get(key) ?: return null
        val envelope = runCatching {
            HttpClientFactory.json.decodeFromString(ApiEnvelope.serializer(), kept.body)
        }.getOrNull() ?: return null
        ZillitLog.d(TAG) { "served from cache: ${url.substringAfter("//").substringAfter('/')}" }
        return ZillitResult.Success(envelope)
    }

    private suspend fun send(
        verb: HttpVerb,
        url: String,
        module: RequestModule,
        body: JsonElement?,
        queryParameters: Map<String, Any?>,
        options: CallOptions,
    ): ZillitResult<ApiEnvelope> = try {
        // The body is serialised up front because `bodyhash` is a digest over
        // it — headers cannot be built before the body exists. Ktor would
        // otherwise serialise it later, inside the builder.
        val bodyJson = body?.let { HttpClientFactory.json.encodeToString(JsonElement.serializer(), it) }

        // Headers are resolved before the request builder runs: building them
        // can suspend (the provider may need to derive an encrypted header or
        // refresh a token) and the builder lambda is not a suspend context.
        //
        // Token mode rides a Bearer and drops the encrypted header — the
        // server reads one or the other, never both. Legacy mode, and any
        // call the authenticator declines, carries `moduledata` as before.
        val projectForAuth = options.projectId ?: readScope()?.projectId?.takeIf { it.isNotBlank() }
        val token = authenticator?.bearerFor(module, projectForAuth)
        val resolvedHeaders = if (token == null) {
            headerProvider.headersFor(module, bodyJson, options.projectId, options.userId)
        } else {
            headerProvider.plainHeaders()
        }

        var response = perform(verb, url, resolvedHeaders, token, queryParameters, bodyJson)
        if (token != null && response.status.value == STATUS_UNAUTHORIZED) {
            // An expired token heals here, once, and the retry's answer is the
            // one reported. A second 401 is the session really being gone.
            val renewed = authenticator?.recoverFromUnauthorized(module, projectForAuth, token)
            if (renewed != null && renewed != token) {
                response = perform(verb, url, resolvedHeaders, renewed, queryParameters, bodyJson)
            }
        }
        response.toEnvelope(options.reportUnauthorized)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        ZillitResult.Failure(throwable.toZillitError())
    }

    @Suppress("LongParameterList") // The request's parts, already resolved; a wrapper would only rename them.
    private suspend fun perform(
        verb: HttpVerb,
        url: String,
        plain: Map<String, String>,
        bearer: String?,
        queryParameters: Map<String, Any?>,
        bodyJson: String?,
    ): HttpResponse = httpClient.request(url) {
        method = verb.toKtor()
        plain.forEach { (name, value) -> headers.append(name, value) }
        bearer?.let { headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
        queryParameters.forEach { (key, value) -> value?.let { parameter(key, it) } }
        if (bodyJson != null) {
            contentType(ContentType.Application.Json)
            // The already-serialised string, so the bytes on the wire are
            // byte-identical to what `bodyhash` was computed over. Letting
            // Ktor re-serialise could reorder keys and invalidate the hash.
            setBody(bodyJson)
        }
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
            val envelope = body<ApiEnvelope>()
            // Some routes answer 200 with `{data: …}` and no `status` at all —
            // the invoices analytics pair among them. Every caller reads
            // `status == 1`, so an absent status read as a refusal: the screen
            // showed "Something went wrong (200)" over a perfectly good body.
            // The web tolerates the same shape (`json.data || json`).
            ZillitResult.Success(if (envelope.status == null) envelope.copy(status = STATUS_OK) else envelope)
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

    private val HttpVerb.wire: String get() = toKtor().value

    private fun HttpVerb.toKtor(): HttpMethod = when (this) {
        HttpVerb.Get -> HttpMethod.Get
        HttpVerb.Post -> HttpMethod.Post
        HttpVerb.Put -> HttpMethod.Put
        HttpVerb.Patch -> HttpMethod.Patch
        HttpVerb.Delete -> HttpMethod.Delete
    }

    private companion object {
        const val TAG = "ApiClient"
        /** What this backend calls success in the envelope. */
        const val STATUS_OK = 1
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_FORBIDDEN = 403

        /**
         * The reads worth keeping: everything a production screen shows.
         * Not device/session checks (a stale one could revive a revoked
         * device), not the credential bundle (secrets stay in the keychain,
         * never on disk), not socket/notification bookkeeping.
         */
        private val CACHEABLE_MODULES = setOf(
            RequestModule.Default,
            RequestModule.Project,
            RequestModule.Chat,
            RequestModule.Media,
            RequestModule.ProjectUser,
        )

        /** Bigger than any list a screen draws; guards the store against a runaway payload. */
        private const val MAX_CACHED_BODY_CHARS = 4 * 1024 * 1024
    }
}

/** A call that did not succeed, as the error log records it. */
data class ApiFailure(val method: String, val url: String, val error: ZillitError) {
    /** The HTTP status, when the server answered at all. */
    val status: Int?
        get() = when (error) {
            is ZillitError.Http -> error.status
            is ZillitError.Unauthorized -> 401
            is ZillitError.Forbidden -> 403
            else -> null
        }

    /** What went wrong, in the server's words where it gave any. */
    val message: String
        get() = (error as? ZillitError.Http)?.serverMessage
            ?: error.technical
            ?: status?.let { "HTTP $it" }
            ?: error::class.simpleName.orEmpty()
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

/** The cache key: who, which production, and exactly what was asked. */
internal fun readKey(scope: ReadScope, projectId: String, url: String, query: Map<String, Any?>): String {
    val sortedQuery = query.entries
        .filter { it.value != null }
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value}" }
    return "${scope.userId}|$projectId|GET|$url|$sortedQuery"
}

/** The failures that mean "could not reach the server", as opposed to "the server said no". */
internal fun ZillitError.isUnreachable(): Boolean = this is ZillitError.NoConnection || this is ZillitError.Timeout
