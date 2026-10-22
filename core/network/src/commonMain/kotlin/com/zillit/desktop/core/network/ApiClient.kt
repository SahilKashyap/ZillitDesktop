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
 * A request body that is not JSON, with the content type it goes out as.
 *
 * One route needs this: Drive saves a text file by PUTting the file's own text
 * as `text/markdown` (`saveFileTextForDrive`), not a JSON object wrapping it.
 * Everything else on every service speaks JSON, which is why that is still the
 * default and this has to be asked for.
 */
data class TextBody(val text: String, val contentType: String)

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
@Suppress("LongParameterList") // One collaborator per parameter, all defaulted, and every call site names them.
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
     * Suspends until a production is open, bounded; true when one arrived.
     *
     * Switching productions is two legs — nothing open, then the new one —
     * and anything in flight or fired between them is a project-scoped call
     * with no project, which [refusedWithNoProject] drops where it stands.
     * That is right on the production list, where no production is coming,
     * and wrong in the middle of a switch, where one is about to: a real
     * switch refused sixteen reads across Drive, the Account Hub, Payroll,
     * Card Expenses, Document Distribution and the mailbox in one second,
     * none of which was retried, so those screens came up empty and the
     * mailbox stopped syncing until the app was restarted (2026-10-21 log).
     *
     * Waiting costs the production list a bounded delay on calls it was
     * going to refuse anyway, behind a screen that is not showing them.
     *
     * Reads only — see [settleProjectScope]. Defaults to false at once,
     * which is the behaviour before this existed.
     */
    private val awaitProjectOpen: suspend () -> Boolean = { false },
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
    @Suppress("LongParameterList") // One parameter per part of a request; see [TextBody].
    suspend fun <T> request(
        verb: HttpVerb,
        url: String,
        serializer: KSerializer<T>,
        module: RequestModule = RequestModule.Default,
        body: JsonElement? = null,
        queryParameters: Map<String, Any?> = emptyMap(),
        options: CallOptions = CallOptions(),
        textBody: TextBody? = null,
    ): ZillitResult<T> =
        envelope(verb, url, module, body, queryParameters, options, textBody).flatMapEnvelope { data ->
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
    @Suppress("LongParameterList") // One parameter per part of a request; see [TextBody].
    suspend fun envelope(
        verb: HttpVerb,
        url: String,
        module: RequestModule = RequestModule.Default,
        body: JsonElement? = null,
        queryParameters: Map<String, Any?> = emptyMap(),
        options: CallOptions = CallOptions(),
        textBody: TextBody? = null,
    ): ZillitResult<ApiEnvelope> {
        // Before the key below, not merely before the request: the key is the
        // scope as it stands, so settling after it would file the answer under
        // the very blank production this is waiting out.
        settleProjectScope(module, verb, url, options)

        // Whose answer this is is settled as it is asked, by the same identity
        // that signs it. Worked out when the answer landed, a read still out
        // across a production switch was kept under the next production — or,
        // mid-switch, under nobody (seen on a real cache: a Home board page
        // filed under a blank user and production).
        val kept = readCache?.let { keptUnder(verb, url, module, queryParameters, options) }
        val outcome = send(verb, url, module, body, queryParameters, options, textBody)
        onOutcome((outcome as? ZillitResult.Failure)?.error)
        (outcome as? ZillitResult.Failure)?.let { onFailure(ApiFailure(verb.wire, url, it.error)) }
        return remembered(outcome, kept, url)
    }

    /**
     * The read cache, around a GET.
     *
     * A good answer is kept under (user, production, url, query) — [kept],
     * taken when the call was made. When the same read cannot reach the
     * server — no connection, a timeout — the kept answer stands in. A refusal
     * (4xx/5xx) is *not* offline and is returned as is: showing a saved list
     * when the server just said "forbidden" would be a lie about rights.
     */
    private suspend fun remembered(
        outcome: ZillitResult<ApiEnvelope>,
        kept: KeptRead?,
        url: String,
    ): ZillitResult<ApiEnvelope> {
        val cache = readCache ?: return outcome
        if (kept == null) return outcome
        return when (outcome) {
            is ZillitResult.Success -> {
                keep(cache, kept.key, kept.scope, outcome.data)
                outcome
            }

            is ZillitResult.Failure ->
                if (outcome.error.isUnreachable()) recall(cache, kept.key, url) ?: outcome else outcome
        }
    }

    /**
     * The kept answer to a read, without going near the network.
     *
     * [remembered] above is the offline fallback: it only reaches for the
     * cache once a call has already failed to reach the server. This is the
     * other way to use the same rows — ask what we already know, draw it, and
     * fire the real read behind it. A screen that does both shows its content
     * at once instead of a spinner, which is the whole reason the rows are
     * kept at a stable [CallOptions.cacheAs] name.
     *
     * Null means "nothing usable": no cache wired, nothing kept under this
     * question, or a kept body that will not decode into [serializer] any more
     * — a shape that changed under a row is a cache miss, not a failure worth
     * showing anybody.
     *
     * The arguments must match the [request] this is the cache of, exactly, or
     * the key will not be the one that was written. [keptUnder] is shared with
     * [envelope], which keys the write, so the two cannot drift.
     */
    suspend fun <T> peekCached(
        url: String,
        serializer: KSerializer<T>,
        module: RequestModule = RequestModule.Default,
        queryParameters: Map<String, Any?> = emptyMap(),
        options: CallOptions = CallOptions(),
    ): T? {
        val cache = readCache ?: return null
        // Get, for both a real GET and a POST that named itself a read: the
        // key spells the verb `GET` either way (see `readKey`).
        val kept = keptUnder(HttpVerb.Get, url, module, queryParameters, options) ?: return null
        val envelope = recall(cache, kept.key, url) ?: return null
        return (envelope.flatMapEnvelope { data -> decode(data, serializer) } as? ZillitResult.Success)?.data
    }

    /** Where this read's answer lives, or null when it is not cached at all. */
    private fun keptUnder(
        verb: HttpVerb,
        url: String,
        module: RequestModule,
        queryParameters: Map<String, Any?>,
        options: CallOptions,
    ): KeptRead? {
        val scope = readScope() ?: return null
        if (!options.isRead(verb) || module !in CACHEABLE_MODULES) return null
        val projectId = options.projectId ?: scope.projectId
        val name = options.cacheAs
        val key = if (name != null) {
            readKey(scope, projectId, name, emptyMap())
        } else {
            readKey(scope, projectId, url, queryParameters)
        }
        return KeptRead(key, scope.copy(projectId = projectId))
    }

    /** A GET, or a POST that named itself a read — and not opted out. */
    private fun CallOptions.isRead(verb: HttpVerb): Boolean =
        readCache && (verb == HttpVerb.Get || (verb == HttpVerb.Post && cacheAs != null))

    /**
     * Keeping an answer never fails the call that answered.
     *
     * The row is a convenience and the server's answer is already in hand, on
     * its way to the caller. A store that cannot take it must not turn a
     * successful read into a thrown exception, because the throw lands on
     * whichever coroutine made the request — and screens read on the main one.
     * One did: a SQLite I/O error on the write below reached
     * `AWT-EventQueue-0` as an uncaught exception, over a row nobody would
     * have missed.
     */
    private suspend fun keep(cache: ReadCache, key: String, scope: ReadScope, envelope: ApiEnvelope) {
        bestEffort("keep") {
            val json = HttpClientFactory.json.encodeToString(ApiEnvelope.serializer(), envelope)
            if (json.length <= MAX_CACHED_BODY_CHARS) cache.put(key, scope, json, nowMillis())
        }
    }

    private suspend fun recall(cache: ReadCache, key: String, url: String): ZillitResult<ApiEnvelope>? {
        val kept = bestEffort("recall") { cache.get(key) } ?: return null
        val envelope = runCatching {
            HttpClientFactory.json.decodeFromString(ApiEnvelope.serializer(), kept.body)
        }.getOrNull() ?: return null
        ZillitLog.d(TAG) { "served from cache: ${url.substringAfter("//").substringAfter('/')}" }
        return ZillitResult.Success(envelope)
    }

    /**
     * One reach into the store, as the broken thing it is allowed to be. Null
     * on anything thrown — [CancellationException] excepted, which must
     * propagate so coroutine cancellation keeps working.
     *
     * The guarantee lives here rather than in each [ReadCache]: this class is
     * the only thing that reaches a store, so holding it once holds it for
     * every implementation, including the ones written next.
     */
    private suspend fun <T> bestEffort(what: String, access: suspend () -> T): T? =
        try {
            access()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.w(TAG) { "read cache $what failed: ${throwable.message}" }
            null
        }

    @Suppress("LongParameterList") // The request's parts, as [envelope] received them.
    private suspend fun send(
        verb: HttpVerb,
        url: String,
        module: RequestModule,
        body: JsonElement?,
        queryParameters: Map<String, Any?>,
        options: CallOptions,
        textBody: TextBody? = null,
    ): ZillitResult<ApiEnvelope> = try {
        // The body is serialised up front because `bodyhash` is a digest over
        // it — headers cannot be built before the body exists. Ktor would
        // otherwise serialise it later, inside the builder.
        val bodyJson = body?.let { HttpClientFactory.json.encodeToString(JsonElement.serializer(), it) }
        // A body that is not JSON is hashed and signed as it goes on the wire,
        // exactly as the web hashes `_zRawData` — the string itself.
        val payload = textBody ?: bodyJson?.let { TextBody(it, ContentType.Application.Json.toString()) }
        val hashed = textBody?.text ?: bodyJson

        // Headers are resolved before the request builder runs: building them
        // can suspend (the provider may need to derive an encrypted header or
        // refresh a token) and the builder lambda is not a suspend context.
        //
        // Token mode rides a Bearer and drops the encrypted header — the
        // server reads one or the other, never both. Legacy mode, and any
        // call the authenticator declines, carries `moduledata` as before.
        val project = projectForAuth(options)

        // A production's route with no production open: refused here rather
        // than sent. The device token is refused on it and the legacy header
        // is refused too, so the round trip can only end one way — and ending
        // it in a 401 also spends a token refresh on a call that was never
        // going to be answered. The web refuses the same calls in its request
        // interceptor, off the same two lists.
        if (authenticator?.refusesWithNoProject(module, project, verb, url) == true) {
            return refusedWithNoProject(verb, url)
        }

        val token = authenticator?.bearerFor(module, project, url)
        val resolvedHeaders = if (token == null) {
            headerProvider.headersFor(module, hashed, options.projectId, options.userId)
        } else {
            headerProvider.plainHeaders()
        }

        var response = perform(verb, url, resolvedHeaders, token, queryParameters, payload)
        // The credential the reported answer actually came back from, which a
        // heal below replaces. Its scope is the same either way, but the log
        // should describe what went out rather than what was first built.
        var sent = token
        if (token != null && response.status.value == STATUS_UNAUTHORIZED) {
            // An expired token heals here, once, and the retry's answer is the
            // one reported. A second 401 is the session really being gone.
            val renewed = authenticator?.recoverFromUnauthorized(module, project, url, token)
            if (renewed != null && renewed != token) {
                sent = renewed
                response = perform(verb, url, resolvedHeaders, renewed, queryParameters, payload)
            }
        }
        // The credential travels to the log with the answer: which one went
        // out is only known here, and on a refusal it is the first thing a
        // reader needs. See [credentialNote].
        response.toEnvelope(options.reportUnauthorized, sent, project)
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
        payload: TextBody?,
    ): HttpResponse = httpClient.request(url) {
        method = verb.toKtor()
        plain.forEach { (name, value) -> headers.append(name, value) }
        bearer?.let { headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
        queryParameters.forEach { (key, value) -> value?.let { parameter(key, it) } }
        if (payload != null) {
            contentType(ContentType.parse(payload.contentType))
            // The already-serialised string, so the bytes on the wire are
            // byte-identical to what `bodyhash` was computed over. Letting
            // Ktor re-serialise could reorder keys and invalidate the hash.
            setBody(payload.text)
        }
    }

    /**
     * The answer a production's route gets with no production open.
     *
     * Deliberately the very failure the server would have sent — the same
     * status and the same `message` key — so every caller behaves exactly as
     * it does today, including whatever it renders. What changes is that no
     * request leaves, no token is refreshed over it, and `onUnauthorized` is
     * never reached, so a refusal here cannot be mistaken for a dead session.
     *
     * [settleProjectScope] has already waited by the time this is reached, so
     * the line below now means "and none opened while we waited" — not
     * "a production happened to be between its two legs".
     *
     * Named once per route, as the web names it: a device-level route missing
     * from [com.zillit.desktop.core.network.tokenauth.isDeviceRoute] would
     * otherwise just stop working with no trace. Once, because the callers
     * that do this poll.
     */
    private fun refusedWithNoProject(verb: HttpVerb, url: String): ZillitResult<ApiEnvelope> {
        val route = "${verb.wire} ${url.substringBefore('?')}"
        if (refusedRoutes.add(route)) {
            ZillitLog.w(TAG) { "refused with no production open: $route" }
        }
        return ZillitResult.Failure(ZillitError.Http(status = STATUS_UNAUTHORIZED, serverMessage = NO_PROJECT_KEY))
    }

    /** The production a call rides, or null for the device's own credential. */
    private fun projectForAuth(options: CallOptions): String? =
        options.projectId ?: readScope()?.projectId?.takeIf { it.isNotBlank() }

    /**
     * Waits out the blank production in the middle of a production switch.
     *
     * Only for a call that would otherwise be dropped: a project-scoped route
     * with nothing open. A device-level route works with no production open
     * and must not wait, which is what asking the authenticator settles — the
     * same question [send] asks before refusing, so the two cannot drift.
     *
     * A call naming its own production ([CallOptions.projectId]) has nothing
     * to wait for, and is also the one call that must NOT be re-pointed at
     * whatever opens next.
     *
     * **Reads only.** A call that names no production is asking about "the
     * production that is open", so re-pointing a GET at the one that opens is
     * the answer to the question it asked — and it mutates nothing either
     * way. A write is not like that: a notice posted into the production
     * being left would land in the production being entered, which is worse
     * than the refusal it gets today. The same goes for the mailbox's
     * POST-shaped reads, whose sync loop writes what it fetches into the
     * production it was started for; those keep the refusal until that loop
     * is told about switches.
     *
     * Named once per route, as [refusedWithNoProject] names its own: the
     * callers caught by this poll, and a line per poll would say nothing a
     * line per route does not.
     */
    private suspend fun settleProjectScope(
        module: RequestModule,
        verb: HttpVerb,
        url: String,
        options: CallOptions,
    ) {
        val authenticator = this.authenticator ?: return

        // One exit for "there is nothing to wait for" — a write, a production
        // already open, or a route that reaches the server without one. Four
        // `return`s put this over detekt's ReturnCount, and the four say one
        // thing, not four. The authenticator is asked last because it is the
        // only suspending half.
        val project = projectForAuth(options)
        val waitable = verb == HttpVerb.Get &&
            project.isNullOrBlank() &&
            authenticator.refusesWithNoProject(module, project, verb, url)
        if (!waitable || !awaitProjectOpen()) return

        val route = "${verb.wire} ${url.substringBefore('?')}"
        if (settledRoutes.add(route)) {
            ZillitLog.i(TAG) { "sent after waiting for a production to open: $route" }
        }
    }

    private suspend fun HttpResponse.toEnvelope(
        reportUnauthorized: Boolean,
        token: String? = null,
        projectId: String? = null,
    ): ZillitResult<ApiEnvelope> {
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
            // The server's own word for the failure, which this line used to
            // drop: a log of bare `406 /api/v2/configuration` says a call was
            // refused and nothing about why, and the refusals that matter are
            // told apart only by this key (`libs_module_data_invalid` is an
            // identity the blob did not carry, `libs_invalid_token_scope` is a
            // token the route will not take). It is a key, not content — the
            // same one `TokenSession` already logs.
            ZillitLog.w(TAG) {
                val why = serverMessage?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
                "${status.value} $path$why" +
                    envelopeNote(serverMessage, raw) +
                    blankPairNote(status.value, readScope()) +
                    credentialNote(status.value, token, projectId)
            }
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

    /** Routes [refusedWithNoProject] has already named. */
    private val refusedRoutes = mutableSetOf<String>()

    /** Routes [settleProjectScope] has already named. */
    private val settledRoutes = mutableSetOf<String>()

    private companion object {
        const val TAG = "ApiClient"

        /**
         * The server's own key for "a device token on a production's route",
         * reused for the refusal so a caller cannot tell the two apart.
         */
        const val NO_PROJECT_KEY = "libs_invalid_token_scope"

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

/** Where one read's answer is kept: the key, and the scope it belongs to. */
private data class KeptRead(val key: String, val scope: ReadScope)

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

/**
 * What this client can already tell about a 406, said on the line that reports
 * it. Empty for every other status, and for a 406 this cannot explain.
 *
 * A 406 is the server refusing a `moduledata` whose project-scoped pair is
 * missing or belongs to another production. Both other clients say so: iOS
 * calls an empty pair "a guaranteed 406" and substitutes a ringing call's
 * identity rather than send one, and this client's own
 * [RequestHeaderProvider.headersFor] warns that another production's user id
 * is a call "the server answers for the wrong person (or 406s)". The pair is
 * taken from the ambient context, so which half was blank is already known
 * where the refusal arrives, and costs a few words to say.
 *
 * Worth those words because of what the field sent instead: thirty-three lines
 * of `406 /api/v2/configuration` naming the symptom and nothing else, in a
 * production log with no INFO in it to cross-reference (2026-10-12). No id is
 * logged — that a production or a person is missing is the finding; which one
 * they are is content.
 */
internal fun blankPairNote(status: Int, scope: ReadScope?): String {
    if (status != STATUS_NOT_ACCEPTABLE) return ""
    if (scope == null) return " — no session context to send"
    val missing = listOfNotNull(
        "production".takeIf { scope.projectId.isBlank() },
        "person".takeIf { scope.userId.isBlank() },
    )
    return if (missing.isEmpty()) "" else " — moduledata named no ${missing.joinToString(" and no ")}"
}

/** The server's answer to a `moduledata` it will not accept. */
private const val STATUS_NOT_ACCEPTABLE = 406

/**
 * Which credential a refused call actually rode, said on the line that
 * reports it. Empty for every status where the credential is not the
 * question.
 *
 * `401 libs_invalid_token_scope` is the server saying "this credential does
 * not reach this route", and the one thing that tells a reader *why* is which
 * credential went out: the device token, which no production's route accepts,
 * or a production's token, which reaches only that production. The line said
 * neither, so the two could not be told apart in a field log at all.
 *
 * What that cost: a week of those refusals (2026-10-21) read as a token minted
 * for one production while the request named another, because the only other
 * thing in the log was `TokenSession`'s mint lines and the nearest one named a
 * different production. It was neither — the mints belonged to the badge
 * seed, which names the *last* production on purpose while none is open
 * (`seedFromLastProduction`), and the refused reads were project-scoped reads
 * firing with nothing open at all, on the device token. Settling that took a
 * version timeline and three source files; this line would have said it.
 *
 * The production is named by its tail only, the convention `TokenSession`
 * already logs ids under: enough to tell two apart, not a record of which
 * productions somebody is on.
 */
internal fun credentialNote(status: Int, token: String?, projectId: String?): String = when {
    status !in CREDENTIAL_STATUSES -> ""
    token == null -> " — on moduledata"
    projectId.isNullOrBlank() -> " — on the device token, which no production's route accepts"
    else -> " — on the token for production ${projectId.idTail()}"
}

/**
 * Whether the refusal even came from this API, said on the line that reports
 * it. Empty whenever the server named its own failure.
 *
 * A refusal with no `message` is not this backend refusing a call — every
 * route of it answers the envelope, and the key in that envelope is the whole
 * of the diagnosis. A refusal without one came from something in front of the
 * app, or from a route that does not speak this shape, and the two want
 * completely different people looking at them.
 *
 * What the gap cost: 155 bare `401 /api/v2/chat-room` and
 * `401 /api/v2/chat/users-group` across two machines and six days
 * (2026-10-14 to 10-19), always as a pair and never with any other route
 * beside them. Read against the refusals that *do* carry a key, they looked
 * like the same fault and were not, and there was no way to tell from the log
 * which of the two it was.
 *
 * The size only, never the body: a refusal body can carry a session, a crew
 * name or a sign-in page. That it did not parse, and roughly how much of it
 * there was, is the finding.
 */
internal fun envelopeNote(serverMessage: String?, raw: String?): String = when {
    !serverMessage.isNullOrBlank() -> ""
    raw == null -> " — the answer could not be read at all"
    raw.isBlank() -> " — an empty answer, with no envelope in it"
    else -> " — ${raw.length} bytes that are not this API's envelope"
}

/**
 * The last few characters of an id — enough to tell two apart in a log, not
 * enough to be a record of what somebody is on. iOS prints the same line the
 * same way (`projectId.suffix(6)`).
 */
internal fun String.idTail(): String = if (length <= ID_TAIL) this else "…" + takeLast(ID_TAIL)

private const val ID_TAIL = 6

/** The statuses where which credential went out is the first thing a reader needs. */
private val CREDENTIAL_STATUSES = setOf(401, 403)
