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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
/**
 * One reading of a device's node: online now, and when the backend last
 * flipped it. The header says "Online" for the first and, offline, prints the
 * second as the web's last-seen stamp (`UserStatusComponent.getOfflineTime`).
 */
data class DevicePresence(
    val online: Boolean,
    /** The node's `update_time`, epoch millis; null when the node never had one. */
    val updatedMillis: Long? = null,
)

/** The seam tests fake: one flow of presence readings for one device on one production. */
interface PresenceSource {
    fun watch(deviceId: String, projectId: String): Flow<DevicePresence>
}

class DevicePresenceSource(
    private val httpClient: HttpClient,
    /** `https://<firebase-project>-default-rtdb.firebaseio.com`, no trailing slash. */
    private val databaseUrl: String,
    private val pollMillis: Long = POLL_MS,
) : PresenceSource {

    /** The node as it reads; unreadable polls emit nothing. */
    override fun watch(deviceId: String, projectId: String): Flow<DevicePresence> = flow {
        var failedLastPoll = false
        while (true) {
            when (val reading = read(deviceId, projectId)) {
                null -> {
                    if (!failedLastPoll) ZillitLog.d(TAG) { "presence unreadable; staying quiet" }
                    failedLastPoll = true
                }
                else -> {
                    failedLastPoll = false
                    emit(reading)
                }
            }
            delay(pollMillis)
        }
    }

    private suspend fun read(deviceId: String, projectId: String): DevicePresence? = try {
        val response = httpClient.get("$databaseUrl/devices/$deviceId/$projectId.json")
        if (!response.status.isSuccess()) {
            null
        } else {
            // `null` body = the backend has never written this device: offline.
            val node = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            DevicePresence(
                online = (node?.get(FIELD_ONLINE) as? JsonPrimitive)?.booleanOrNull ?: false,
                // Millis on the wire (`1779864035058`), as the web reads it.
                updatedMillis = (node?.get(FIELD_UPDATED) as? JsonPrimitive)
                    ?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
                    ?.takeIf { it > 0 },
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
        null
    }

    private companion object {
        const val TAG = "Presence"
        const val FIELD_ONLINE = "is_online"
        const val FIELD_UPDATED = "update_time"

        /**
         * Snappier than the roster needs, calmer than the call plane's two
         * seconds — presence changes when an app opens or closes, not per
         * frame, and iOS's own backgrounding grace is three minutes.
         */
        const val POLL_MS = 5_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
}
