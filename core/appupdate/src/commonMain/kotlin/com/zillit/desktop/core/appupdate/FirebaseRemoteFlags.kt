package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.FirebaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The phones' Firebase Remote Config, read the only way a client without the
 * SDK can: the client-fetch REST call the update checker already makes.
 *
 * One fetch returns every key the console publishes; this hands the map to
 * whoever asks so a second flag does not mean a second request. Held rather
 * than cached across sessions — a flag is a decision the console can change
 * between two launches, and reading a stale one is how a feature stays on
 * after it was switched off.
 */
class FirebaseRemoteFlags(
    private val httpClient: HttpClient,
    private val firebase: FirebaseConfig?,
    private val instanceId: suspend () -> String,
    private val host: String = FIREBASE_REMOTE_CONFIG_HOST,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val fetching = Mutex()

    @Volatile private var held: Map<String, String>? = null

    @Volatile private var lastAttempt: TimeSource.Monotonic.ValueTimeMark? = null

    /** Every published entry, fetched once and then remembered; null when unreachable or unconfigured. */
    suspend fun entries(): Map<String, String>? {
        held?.let { return it }
        val config = firebase ?: return null
        val appId = config.appId?.takeIf { it.isNotBlank() } ?: return null
        return fetch(config, appId)?.also { held = it }
    }

    /**
     * Fetches again unless the last attempt is younger than [minInterval],
     * and answers what the console last said — the previous answer when this
     * fetch fails, so being offline never takes a flag away. Null only when
     * no fetch has succeeded yet.
     */
    suspend fun refresh(minInterval: Duration = Duration.ZERO): Map<String, String>? = fetching.withLock {
        val config = firebase ?: return@withLock null
        val appId = config.appId?.takeIf { it.isNotBlank() } ?: return@withLock null
        val since = lastAttempt?.elapsedNow()
        // Throttled on the attempt, not the success: offline, every ask would otherwise be a request.
        if (since != null && since < minInterval) return@withLock held
        lastAttempt = TimeSource.Monotonic.markNow()
        fetch(config, appId)?.also { held = it } ?: held
    }

    /** Whether [refresh] would fetch now rather than answer from memory. */
    fun isStale(minInterval: Duration): Boolean = lastAttempt?.let { it.elapsedNow() >= minInterval } ?: true

    /** One key, by name; null when the console does not publish it. */
    suspend fun value(key: String): String? = entries()?.get(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** Forgets what was read, so the next ask fetches again — sign-out, or a project switch. */
    fun clear() {
        held = null
    }

    private suspend fun fetch(config: FirebaseConfig, appId: String): Map<String, String>? = try {
        val response = httpClient.post("$host/v1/projects/${config.projectId}/namespaces/firebase:fetch") {
            parameter("key", config.apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    FetchRequest.serializer(),
                    FetchRequest(appId = appId, appInstanceId = instanceId(), languageCode = "en"),
                ),
            )
        }
        if (response.status.isSuccess()) {
            parseEntries(response.bodyAsText())
        } else {
            ZillitLog.d(TAG) { "remote flags fetch returned ${response.status.value}" }
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
        ZillitLog.d(TAG) { "remote flags unreachable" }
        null
    }

    private companion object {
        const val TAG = "RemoteFlags"
    }
}
