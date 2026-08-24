package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Whether one crew member is online right now, from Firebase Realtime Database.
 *
 * The green "Online" in a chat header is not a socket feature on any Zillit
 * client: the backend answers each device's `mark_online` / `mark_offline` /
 * disconnect by writing `devices/{deviceId}/{projectId} = {is_online,
 * update_time}` to RTDB, and iOS (`ChatingViewController.addObserver`) and the
 * web (`UserStatusComponent` via `getOnlineAndOfflineStatus`) both read that
 * node live. The phones use the Firebase SDK; no JVM desktop SDK exists, so —
 * like the Firestore call-status plane — this reads the REST surface, whose
 * rules allow it (verified: both environments answer an unauthenticated GET).
 *
 * Polled rather than streamed, and only while a 1:1 thread is open — the same
 * trade the call plane documents: one small GET on a quiet cadence against a
 * node that only changes when somebody opens or closes an app.
 *
 * Failure posture: a mirror, never the primary. Errors log once per streak and
 * emit nothing — a chat with no presence dot degrades to exactly the chat the
 * desktop shipped yesterday.
 */
/** The seam tests fake: one flow of online/offline for one device on one production. */
interface PresenceSource {
    fun watch(deviceId: String, projectId: String): Flow<Boolean>
}

class DevicePresenceSource(
    private val httpClient: HttpClient,
    /** `https://<firebase-project>-default-rtdb.firebaseio.com`, no trailing slash. */
    private val databaseUrl: String,
    private val pollMillis: Long = POLL_MS,
) : PresenceSource {

    /** True/false as the node reports it; unreadable polls emit nothing. */
    override fun watch(deviceId: String, projectId: String): Flow<Boolean> = flow {
        var failedLastPoll = false
        while (true) {
            when (val online = read(deviceId, projectId)) {
                null -> {
                    if (!failedLastPoll) ZillitLog.d(TAG) { "presence unreadable; staying quiet" }
                    failedLastPoll = true
                }
                else -> {
                    failedLastPoll = false
                    emit(online)
                }
            }
            delay(pollMillis)
        }
    }

    private suspend fun read(deviceId: String, projectId: String): Boolean? = try {
        val response = httpClient.get("$databaseUrl/devices/$deviceId/$projectId.json")
        if (!response.status.isSuccess()) {
            null
        } else {
            // `null` body = the backend has never written this device: offline.
            val node = json.parseToJsonElement(response.bodyAsText())
            (node as? JsonObject)?.let {
                (it[FIELD_ONLINE] as? JsonPrimitive)?.booleanOrNull ?: false
            } ?: false
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
        null
    }

    private companion object {
        const val TAG = "Presence"
        const val FIELD_ONLINE = "is_online"

        /**
         * Snappier than the roster needs, calmer than the call plane's two
         * seconds — presence changes when an app opens or closes, not per
         * frame, and iOS's own backgrounding grace is three minutes.
         */
        const val POLL_MS = 5_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
}
