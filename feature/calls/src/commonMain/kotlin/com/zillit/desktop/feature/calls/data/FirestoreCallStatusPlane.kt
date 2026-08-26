package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The status plane over Firestore's REST surface.
 *
 * Android and web use the Firebase SDKs; no client SDK exists for a JVM
 * desktop, and the documents involved are two small maps — so this speaks the
 * REST API directly. Live listeners are an SDK luxury: this polls instead,
 * every [pollMillis] while a call is active, which is the only time the
 * documents change. A call's whole Firestore footprint is a handful of
 * kilobytes; polling it for the duration of a ring costs less than the ring
 * tone.
 *
 * Failure posture: this plane is a mirror, never the primary. Every error is
 * logged and swallowed — a production whose Firestore rules refuse us (App
 * Check enforcement, rule changes) degrades to socket-only calling rather
 * than broken calling. [disabled] latches after an auth refusal so a denied
 * project is not re-asked every two seconds.
 */
class FirestoreCallStatusPlane(
    private val httpClient: HttpClient,
    private val projectId: String,
    private val apiKey: String,
    private val selfDeviceId: () -> String?,
    private val pollMillis: Long = POLL_MS,
) : CallStatusPlane {

    private var disabled = false

    private val documentsBase =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

    override suspend fun announceSelf(
        session: CallSession,
        status: CallStatus,
        extra: Map<String, Any>,
    ) {
        val deviceId = selfDeviceId().orEmpty()
        if (deviceId.isEmpty() || session.callUuid.isEmpty()) return
        val fields = buildMap<String, Any> {
            put(FIELD_STATUS, status.wire)
            // Identity on the row itself, not only in its document id.
            // [readCallUsers] drops any row without `device_id`, and iOS reads
            // the same field — so a desktop row that omitted it was invisible
            // to every reader including this one, and a desktop participant
            // simply never appeared in anybody's roster.
            put(FIELD_DEVICE_ID, deviceId)
            session.selfUserId.takeIf(String::isNotBlank)?.let { put(FIELD_USER_ID, it) }
            putAll(extra)
            put(FIELD_UPDATED_FROM, PLATFORM)
        }
        patch("calls/${session.callUuid}/call_users/$deviceId", fields)
    }

    override suspend fun announceCallEnded(session: CallSession) {
        if (session.callUuid.isEmpty()) return
        patch("calls/${session.callUuid}", mapOf(FIELD_CALL_STATUS to CallStatus.Ended.wire))
    }

    override suspend fun updateUserFields(
        session: CallSession,
        deviceId: String,
        fields: Map<String, Any>,
    ) {
        if (deviceId.isBlank() || session.callUuid.isEmpty() || fields.isEmpty()) return
        patch("calls/${session.callUuid}/call_users/$deviceId", fields)
    }

    override fun watch(session: CallSession): Flow<PlaneEvent> = flow {
        var lastStatuses = emptyMap<String, String>()
        var lastFlags = emptyMap<String, String>()
        var endedSeen = false
        while (true) {
            if (disabled) return@flow

            if (!endedSeen && callHasEnded(session.callUuid)) {
                endedSeen = true
                emit(PlaneEvent.Ended(session.callUuid))
            }

            readCallUsers(session.callUuid).forEach { row ->
                val deviceId = row.value(FIELD_DEVICE_ID) ?: return@forEach
                val status = row.value(FIELD_STATUS) ?: return@forEach
                if (lastStatuses[deviceId] != status) {
                    emit(
                        PlaneEvent.UserStatus(
                            deviceId = deviceId,
                            userId = row.value(FIELD_USER_ID).orEmpty(),
                            status = CallStatus.ofWire(status),
                            updatedFrom = row.value(FIELD_UPDATED_FROM).orEmpty(),
                        ),
                    )
                    lastStatuses = lastStatuses + (deviceId to status)
                }

                // Sharing and raised hands move without the status moving, so
                // they carry their own change detector.
                val flags = row.flags()
                if (lastFlags[deviceId] != flags.key) {
                    lastFlags = lastFlags + (deviceId to flags.key)
                    emit(
                        PlaneEvent.UserFlags(
                            deviceId = deviceId,
                            userId = row.value(FIELD_USER_ID).orEmpty(),
                            agoraUid = flags.agoraUid,
                            sharing = flags.sharing,
                            handRaised = flags.handRaised,
                            recording = flags.recording,
                            userName = flags.userName,
                            muted = flags.muted,
                            hasVideo = flags.hasVideo,
                        ),
                    )
                }
            }

            delay(pollMillis)
        }
    }

    private suspend fun callHasEnded(callUuid: String): Boolean =
        readCallDocument(callUuid)?.value(FIELD_CALL_STATUS) == CallStatus.Ended.wire

    /**
     * The per-person state that moves without the call status moving.
     *
     * `screen_share` is the web's spelling of `screenShare`; the phones read
     * both, so a desktop that read only one would miss half the fleet's
     * sharing. [key] is what the poller diffs against — a value, so the
     * comparison cannot accidentally be an identity check.
     */
    private data class UserFlags(
        val sharing: Boolean,
        val handRaised: Boolean,
        val agoraUid: Int,
        val recording: Boolean,
        val userName: String,
        val muted: Boolean,
        val hasVideo: Boolean,
    ) {
        val key: String
            get() = "$sharing/$handRaised/$agoraUid/$recording/$userName/$muted/$hasVideo"
    }

    private fun JsonObject.flags(): UserFlags = UserFlags(
        sharing = value(FIELD_SHARING)?.toBoolean() ?: value(FIELD_SHARING_LEGACY)?.toBoolean() ?: false,
        handRaised = value(FIELD_HAND)?.toBoolean() ?: false,
        agoraUid = value(FIELD_AGORA_UID)?.toIntOrNull() ?: 0,
        recording = value(FIELD_IS_RECORDING)?.toBoolean() ?: false,
        userName = value(FIELD_USER_NAME).orEmpty(),
        muted = value(FIELD_IS_MUTE)?.toBoolean() ?: false,
        hasVideo = value(FIELD_HAS_VIDEO)?.toBoolean() ?: false,
    )

    // ── Firestore REST ──────────────────────────────────────────────────

    private suspend fun readCallDocument(callUuid: String): JsonObject? =
        request("get calls/$callUuid") {
            httpClient.get("$documentsBase/calls/$callUuid") { parameter("key", apiKey) }
        }?.let { body ->
            (json.parseToJsonElement(body) as? JsonObject)?.get("fields") as? JsonObject
        }

    private suspend fun readCallUsers(callUuid: String): List<JsonObject> =
        request("list call_users") {
            httpClient.get("$documentsBase/calls/$callUuid/call_users") {
                parameter("key", apiKey)
                parameter("pageSize", CALL_USERS_PAGE)
            }
        }?.let { body ->
            val root = json.parseToJsonElement(body) as? JsonObject
            (root?.get("documents") as? JsonArray).orEmpty().mapNotNull { entry ->
                (entry as? JsonObject)?.get("fields") as? JsonObject
            }
        }.orEmpty()

    /**
     * A field-masked merge, the REST spelling of the SDK's `set(…, merge)`.
     *
     * Merge rather than update: Android's `update()` fails when the row does
     * not exist yet, and web grew `upsertUserFields` for exactly that hole —
     * a device declining a call whose row the backend has not written loses
     * the decline. Merge is the version of this that cannot lose.
     */
    private suspend fun patch(path: String, fields: Map<String, Any>) {
        if (disabled) return
        val ok = request("patch $path") {
            httpClient.patch("$documentsBase/$path") {
                parameter("key", apiKey)
                fields.keys.forEach { parameter("updateMask.fieldPaths", it) }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), encodeFirestoreFields(fields)))
            }
        }
        if (ok != null) ZillitLog.i(TAG) { "mirrored $path" }
    }

    /**
     * One place for the plane's failure posture: log, degrade, never throw.
     * The label deliberately names the operation and not the URL — the URL
     * carries the API key as a query parameter.
     */
    private suspend fun request(label: String, block: suspend () -> HttpResponse): String? = try {
        val response = block()
        when {
            response.status.isSuccess() -> response.bodyAsText()
            response.status == HttpStatusCode.NotFound -> null
            response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.Unauthorized -> {
                if (!disabled) {
                    disabled = true
                    ZillitLog.w(TAG) {
                        "Firestore refused ($label, ${response.status.value}) — " +
                            "status plane off for this session"
                    }
                }
                null
            }
            else -> {
                ZillitLog.w(TAG) { "$label failed: ${response.status.value}" }
                null
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
        ZillitLog.w(TAG) { "$label failed: ${t::class.simpleName}" }
        null
    }

    // ── Firestore's typed-value encoding ────────────────────────────────

    /** A typed Firestore value, flattened back to the string it holds. */
    private fun JsonObject.value(name: String): String? {
        val wrapper = this[name] as? JsonObject ?: return null
        return (wrapper["stringValue"] as? JsonPrimitive)?.contentOrNull
            ?: (wrapper["integerValue"] as? JsonPrimitive)?.contentOrNull
            ?: (wrapper["booleanValue"] as? JsonPrimitive)?.booleanOrNull?.toString()
    }

    internal companion object {
        const val TAG = "CallStatusPlane"

        /** What this client writes into `updated_from`, beside `Android`/`web`. */
        const val PLATFORM = "Desktop"

        const val FIELD_STATUS = "current_status"
        /** What iOS and the web write and read. Canonical. */
        const val FIELD_SHARING = "screen_share"

        /** Tolerated on read only — an older desktop wrote this spelling. */
        const val FIELD_SHARING_LEGACY = "screenShare"

        const val FIELD_HAND = "raise_hand"
        const val FIELD_AGORA_UID = "agora_uid"

        /** The recorder's own row says so; iOS reads and writes this key. */
        const val FIELD_IS_RECORDING = "isRecording"

        /** The self-healing display name every platform stamps on its row. */
        const val FIELD_USER_NAME = "user_name"

        /**
         * Mute and camera, as the row records them.
         *
         * Read because on the Agora line the row is the fleet's only mute
         * channel: iOS has no engine-level mute signal there at all, and this
         * client's own signal is `user-published`/`user-unpublished`, which
         * fires on a change and never on the state that was already true. So
         * someone who muted before this device joined was shown unmuted until
         * they happened to toggle it. A seed and a fallback — the engine's own
         * events still win whenever they arrive.
         */
        const val FIELD_IS_MUTE = "isMute"
        const val FIELD_HAS_VIDEO = "has_video"
        const val FIELD_CALL_STATUS = "status"
        const val FIELD_DEVICE_ID = "device_id"
        const val FIELD_USER_ID = "user_id"
        const val FIELD_UPDATED_FROM = "updated_from"

        const val POLL_MS = 2_000L
        const val CALL_USERS_PAGE = 50

        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Kotlin values as Firestore REST typed values.
 *
 * The type chosen here is a wire contract, not an implementation detail: iOS
 * decodes each row with a strict `JSONDecoder`, so a value of the wrong JSON
 * type throws `typeMismatch` and discards the entire row rather than that one
 * field. See CallRowWireTest.
 */
internal fun encodeFirestoreFields(fields: Map<String, Any>): JsonObject = buildJsonObject {
    putJsonObject("fields") {
        fields.forEach { (name, value) ->
            putJsonObject(name) {
                when (value) {
                    is Boolean -> put("booleanValue", value)
                    is Int -> put("integerValue", value.toString())
                    is Long -> put("integerValue", value.toString())
                    else -> put("stringValue", value.toString())
                }
            }
        }
    }
}
