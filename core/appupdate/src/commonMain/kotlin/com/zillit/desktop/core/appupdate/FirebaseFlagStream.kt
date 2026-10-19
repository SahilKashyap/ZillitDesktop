package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.FirebaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Remote Config's realtime invalidations, read the only way a client without
 * the SDK can.
 *
 * Android's `addOnConfigUpdateListener` is not magic. The SDK holds one
 * long-lived HTTP POST open against `firebaseremoteconfigrealtime`, and the
 * server writes a small object into it each time the console publishes —
 * `{"latestTemplateVersionNumber":"42"}` — with keep-alives in between. It
 * then does an ordinary fetch. None of that needs an SDK, so this speaks it
 * directly: [invalidations] emits each newly published template version and
 * the collector refetches through [FirebaseRemoteFlags], which is the SDK's
 * own sequence.
 *
 * ## What this is not
 *
 * **Not a documented API.** `firebase:fetch` is published REST that Google
 * supports; the stream beside it is what the SDKs talk and what Google
 * documents nowhere. It can change, or start refusing us, without notice.
 *
 * It does work today. Probed against the production project on 2026-10-19:
 * `POST …/v1/projects/901169402613/namespaces/firebase:streamFetchInvalidations`
 * with `lastKnownVersionNumber: "1"` answered `200` and pushed
 * `{"latestTemplateVersionNumber": "1083"}` at once, then held the connection
 * open in silence. Two things that probe settles, both of which a phone does
 * differently:
 *
 *  - **no Firebase Installations token is needed.** We never register with FIS
 *    — `AppGraph.updateInstanceId` mints a UUID and keeps it — while the
 *    Android SDK sends an installations auth header on the fetch and on the
 *    stream. Neither call minds.
 *  - **no `X-Android-Package`/`X-Android-Cert` pair is needed**, which it
 *    could not have been given: this is not an Android app.
 *
 * The reply is also pretty-printed across several lines inside a streamed JSON
 * array, which is why messages are brace-counted out of a buffer ([nextObject])
 * rather than read a line at a time. A line-based reader works against a
 * hand-written fixture and fails against the real server.
 *
 * Even so, **nothing here is load-bearing.** `CallingLinesGate`'s poll is
 * unchanged; this only shortens the wait from minutes to seconds. A project
 * that refuses the stream, or has Realtime switched off, latches it off for
 * the life of the process rather than reconnecting for ever, and the poll
 * carries on alone.
 */
class FirebaseFlagStream(
    private val httpClient: HttpClient,
    private val firebase: FirebaseConfig?,
    private val instanceId: suspend () -> String,
    /**
     * The template version in hand — [FirebaseRemoteFlags.templateVersion].
     *
     * Read fresh on each use, not captured: it is what the server compares
     * against to decide whether we are behind, and what suppresses the echo of
     * an invalidation we have already answered.
     */
    private val lastKnownVersion: () -> Long,
    private val host: String = FIREBASE_REALTIME_HOST,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Each newly published template version, for as long as it is collected.
     *
     * Holds a connection open while collected, and reopens it when the server
     * closes it — which is normal, and not a fault. Completes only when there
     * is nothing to listen to: no Firebase configured, no app id, or a project
     * that has told us Realtime is off.
     */
    fun invalidations(): Flow<Long> = flow {
        val config = firebase ?: return@flow
        val appId = config.appId?.takeIf { it.isNotBlank() } ?: return@flow
        val project = projectNumber(appId) ?: run {
            ZillitLog.d(TAG) { "app id carries no project number: realtime flags off" }
            return@flow
        }

        var failures = 0
        while (true) {
            when (connect(config, appId, project)) {
                // The connection did its job and ended. Reopen promptly: a
                // window closing with nothing published is the common case.
                Outcome.Closed -> failures = 0
                Outcome.Failed -> failures++
                Outcome.Disabled -> {
                    ZillitLog.i(TAG) { "realtime flags unavailable for this project; the poll decides alone" }
                    return@flow
                }
            }
            delay(if (failures == 0) REOPEN_MILLIS else backoffMillis(failures))
        }
    }

    /** One connection, from the POST to the close. */
    private suspend fun FlowCollector<Long>.connect(
        config: FirebaseConfig,
        appId: String,
        project: String,
    ): Outcome = try {
        httpClient.preparePost("$host/$STREAM_PATH_PREFIX/$project/$STREAM_PATH_SUFFIX") {
            parameter("key", config.apiKey)
            header(API_KEY_HEADER, config.apiKey)
            // The exchange must have no deadline — it is meant to last hours —
            // and the read window has to outlast the server's keep-alives.
            // `storageClient` is shared and caps reads at a minute, which
            // would otherwise tear this down on the minute, every minute.
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = READ_WINDOW_MILLIS
            }
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    StreamRequest.serializer(),
                    StreamRequest(
                        project = project,
                        namespace = NAMESPACE,
                        lastKnownVersionNumber = lastKnownVersion().toString(),
                        appId = appId,
                        appInstanceId = instanceId(),
                    ),
                ),
            )
        }.execute { response ->
            if (response.status.isSuccess()) {
                readUntilClosed(response.bodyAsChannel())
            } else {
                ZillitLog.d(TAG) { "realtime stream refused: ${response.status.value}" }
                if (response.status.value in PERMANENT_REFUSALS) Outcome.Disabled else Outcome.Failed
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
        // Could not be opened at all: offline, DNS, TLS, a proxy in the way.
        ZillitLog.d(TAG) { "realtime stream unreachable" }
        Outcome.Failed
    }

    /**
     * Reads messages until the stream ends.
     *
     * Its own error handling, so that the read window expiring — the ordinary
     * way a connection with nothing to say ends — is [Outcome.Closed] rather
     * than a failure that would push the backoff up. Only a connection that
     * never opened counts against us.
     */
    private suspend fun FlowCollector<Long>.readUntilClosed(channel: ByteReadChannel): Outcome = try {
        read(channel)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
        ZillitLog.d(TAG) { "realtime stream closed" }
        Outcome.Closed
    }

    private suspend fun FlowCollector<Long>.read(channel: ByteReadChannel): Outcome {
        val buffered = StringBuilder()
        while (true) {
            // The server frames its messages as a JSON array written
            // incrementally and flushes them mid-line, so lines are joined and
            // objects taken out whole rather than parsed one line at a time.
            val line = channel.readUTF8Line() ?: return Outcome.Closed
            buffered.append(line)
            forward(buffered)?.let { return it }
        }
    }

    /**
     * Forwards every message now complete in [buffered].
     *
     * Null to carry on reading; anything else is how this connection ends.
     */
    private suspend fun FlowCollector<Long>.forward(buffered: StringBuilder): Outcome? {
        while (true) {
            val message = nextObject(buffered) ?: return null
            when (val invalidation = parseInvalidation(message)) {
                // Only forward what we are actually behind: the server answers
                // our opening `lastKnownVersionNumber` at once, so the first
                // message of a reconnect is often one we already have.
                is Invalidation.Published ->
                    if (invalidation.version > lastKnownVersion()) emit(invalidation.version)
                Invalidation.Disabled -> return Outcome.Disabled
                Invalidation.Broken -> return Outcome.Failed
                // A keep-alive. Nothing published; the connection is alive.
                null -> Unit
            }
        }
    }

    /**
     * Exponential backoff with jitter, for connections that never opened.
     *
     * `core:socket`'s `ReconnectPolicy` is the same arithmetic, better tested,
     * and deliberately out of reach: this module may see `core:common`,
     * `core:config` and `core:network` and nothing else (see its
     * `build.gradle.kts`). Jitter is still the part that matters — every
     * desktop that was open when Google blipped would otherwise retry in step.
     */
    private fun backoffMillis(failures: Int): Long {
        val exponential = BACKOFF_START_MILLIS * BACKOFF_MULTIPLIER.pow(failures - 1)
        val capped = min(exponential, BACKOFF_MAX_MILLIS.toDouble())
        val jitter = capped * BACKOFF_JITTER
        return Random.nextDouble(capped - jitter, capped + jitter).toLong().coerceAtLeast(1)
    }

    private companion object {
        const val TAG = "RemoteFlags"
        const val NAMESPACE = "firebase"
        const val STREAM_PATH_PREFIX = "v1/projects"
        const val STREAM_PATH_SUFFIX = "namespaces/firebase:streamFetchInvalidations"

        /** The key travels as a header as well as a query parameter, as the SDKs send it. */
        const val API_KEY_HEADER = "X-Goog-Api-Key"

        /**
         * How long a silent connection is given before it is reopened.
         *
         * Longer than the server's keep-alive interval and shorter than
         * forever: a connection dropped by something in the middle looks
         * exactly like a quiet one, and this is how long a publish could go
         * unnoticed in that case.
         */
        const val READ_WINDOW_MILLIS = 300_000L

        /** Reopening after a clean close: long enough not to hammer, short enough to feel live. */
        const val REOPEN_MILLIS = 1_000L

        const val BACKOFF_START_MILLIS = 5_000L
        const val BACKOFF_MAX_MILLIS = 600_000L
        const val BACKOFF_MULTIPLIER = 2.0
        const val BACKOFF_JITTER = 0.25

        /**
         * Answers that asking again will not change.
         *
         * Every 4xx but 429: a rejected body, a key this surface will not
         * take, a project without Realtime. Retrying those for the life of the
         * process is noise against Google and a connection held for nothing.
         */
        val PERMANENT_REFUSALS = (400..499).toSet() - 429
    }
}

/** `https://firebaseremoteconfigrealtime.googleapis.com` — no trailing slash. */
const val FIREBASE_REALTIME_HOST = "https://firebaseremoteconfigrealtime.googleapis.com"

/** How a connection ended, which decides how soon the next one opens. */
private enum class Outcome { Closed, Failed, Disabled }

/** What one message on the stream means. */
internal sealed interface Invalidation {

    /** The console published [version]. */
    data class Published(val version: Long) : Invalidation

    /** Realtime is off for this project, or this client may not have it. */
    data object Disabled : Invalidation

    /** The server reported an error on the open stream; reopen it. */
    data object Broken : Invalidation
}

/**
 * The stream request body, as the SDKs send it.
 *
 * `lastKnownVersionNumber` is what makes a reconnect catch up rather than only
 * watch: the server compares it with the live template and invalidates
 * immediately when we are behind, so a publish missed while the machine slept
 * is not a publish missed.
 */
@Serializable
internal data class StreamRequest(
    @SerialName("project") val project: String,
    @SerialName("namespace") val namespace: String,
    @SerialName("lastKnownVersionNumber") val lastKnownVersionNumber: String,
    @SerialName("appId") val appId: String,
    @SerialName("appInstanceId") val appInstanceId: String,
)

/**
 * The project number out of a `mobilesdk_app_id` — `1:1234567890:android:ab`.
 *
 * The realtime surface is addressed by project *number* while the fetch is
 * addressed by project *id*; the number is the app id's second field, which is
 * why this needs no new configuration. Null when the app id is not that shape,
 * which switches the stream off rather than guessing.
 */
internal fun projectNumber(appId: String): String? =
    appId.split(':').getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

/**
 * Takes the next complete `{...}` out of [buffered], or null while one is
 * still arriving.
 *
 * Brace-counting rather than line-reading because the framing is a JSON array
 * written incrementally: `[`, an object, `,`, another object — flushed in
 * whatever pieces the network gives us. Text before the first `{` is array
 * punctuation and whitespace, and is dropped.
 */
internal fun nextObject(buffered: StringBuilder): String? {
    val start = buffered.indexOf("{")
    if (start < 0) {
        buffered.setLength(0)
        return null
    }

    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until buffered.length) {
        val char = buffered[index]
        when {
            escaped -> escaped = false
            inString && char == '\\' -> escaped = true
            char == '"' -> inString = !inString
            inString -> Unit
            char == '{' -> depth++
            char == '}' -> {
                depth--
                if (depth == 0) {
                    val message = buffered.substring(start, index + 1)
                    buffered.deleteRange(0, index + 1)
                    return message
                }
            }
        }
    }
    return null
}

/**
 * Reads one message.
 *
 * Null is a keep-alive — an object with nothing in it for us — which is most
 * of what this stream carries and must never be mistaken for a publish.
 */
internal fun parseInvalidation(message: String): Invalidation? {
    val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
    if ((root["featureDisabled"] as? JsonPrimitive)?.booleanOrNull == true) return Invalidation.Disabled
    if (root["error"] != null) return Invalidation.Broken

    // A quoted number on the wire; read as the primitive's content so a bare
    // one parses too.
    val version = (root["latestTemplateVersionNumber"] as? JsonPrimitive)?.content?.toLongOrNull()
    return version?.let { Invalidation.Published(it) }
}

/**
 * The template version out of a client-fetch response.
 *
 * `{"entries": {…}, "state": "UPDATE", "templateVersion": "42"}` — a field the
 * ordinary fetch has always answered with and nothing read until there was a
 * stream to tell. Null when it is absent, which leaves the version in hand
 * alone rather than resetting it to zero and inviting an invalidation for a
 * template we already have.
 */
internal fun parseTemplateVersion(payload: String): Long? {
    val root = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
    return (root["templateVersion"] as? JsonPrimitive)?.content?.toLongOrNull()
}
