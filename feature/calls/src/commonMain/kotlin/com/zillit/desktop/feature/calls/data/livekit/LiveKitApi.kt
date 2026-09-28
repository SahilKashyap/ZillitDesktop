package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A signed call to Line 3's REST backend, answered as plain JSON.
 *
 * The calling backend does not wrap its answers in Zillit's `{status, data}`
 * envelope — `POST /v1/calls` answers `{callId, wsUrl, token, livekit}`
 * directly — so `ApiClient` cannot read it. The host implements this over
 * its HTTP client and header provider: the same `moduledata` /
 * `deviceInfo` / `bodyhash` headers every Zillit request carries, built for
 * `RequestModule.LiveKit`, and for the production the call is about.
 */
fun interface SignedJsonHttp {
    /**
     * Sends and reads. A non-2xx answer is a [ZillitError.Http] whose
     * `serverMessage` is the body's `error` word (`caller_busy`,
     * `not_auth`) — the backend's convention, and what the UI maps to text.
     * [projectId] and [userId] name the call's production and the caller's
     * id there, or null for the ambient pair.
     */
    suspend fun call(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
        projectId: String?,
        userId: String?,
    ): ZillitResult<JsonElement?>
}

/** What a create or accept hands back: the call, and the room to join it in. */
data class LiveKitCallCredentials(
    val callId: String,
    val livekit: LiveKitCredentials?,
    /** Set when a call already exists for this callee or room — join it instead of dialling. */
    val switchToCallId: String? = null,
)

/**
 * Line 3's REST client — the phones' `livekit/api/ApiClient.kt` and the web's
 * `lineTwo/api.ts`, on `<env>-calls.zillit.com/api`.
 *
 * Every route is `/v1/…`. The socket is the first choice for anything that
 * changes a call (it carries presence with it); these are the fallbacks the
 * phones use when the socket is down, plus the two things only REST does —
 * creating a call and minting a room token.
 */
class LiveKitApi(
    private val http: SignedJsonHttp,
    /** `https://<env>-calls.zillit.com/api`, no trailing slash. */
    private val baseUrl: String,
) {
    /**
     * `POST /v1/calls` — creates the call AND rings the callees, which is what
     * the web's `startCallRest` is: the socket-down fallback, and the only REST
     * path left on the way out now that a socket-first `startCall` mints its own
     * call id.
     *
     * `type` is NOT `callMode` — see the body. Observed on prod (2026-09-05): a
     * body saying `type: private` comes back 400 `bad_request`, which is why
     * this path had never started a one-to-one call.
     */
    @Suppress("LongParameterList") // One parameter per field the endpoint takes; a wrapper would only rename them.
    suspend fun createCall(
        callerId: String,
        callerName: String,
        callMode: CallMode,
        callType: CallType,
        calleeIds: List<String>,
        chatRoomId: String?,
        chatRoomName: String?,
        projectId: String?,
        projectName: String?,
    ): ZillitResult<LiveKitCallCredentials> = http.call(
        HttpVerb.Post,
        "$baseUrl/v1/calls",
        buildJsonObject {
            put("callerId", JsonPrimitive(callerId))
            put("callerName", JsonPrimitive(callerName))
            // `type` is the endpoint's OWN vocabulary — `direct` or `group` —
            // and `callMode` is the call flow's (`private` or `group`). They
            // share the word "group" and nothing else, which is how sending
            // `callMode.wire` for both went unnoticed: group calls worked by
            // coincidence and this fallback never once started a one-to-one,
            // because the endpoint refuses a `type` it does not recognise. The
            // web fixed the same bug with the same two lines.
            put("type", JsonPrimitive(if (callMode == CallMode.Group) "group" else "direct"))
            put("callMode", JsonPrimitive(callMode.wire))
            put("callType", JsonPrimitive(callType.wire))
            put("calleeIds", JsonArray(calleeIds.map(::JsonPrimitive)))
            chatRoomId?.takeIf { it.isNotBlank() }?.let { put("chatRoomId", JsonPrimitive(it)) }
            // A group call is server-authoritative — we send NO member list —
            // so the room's name is the only label the server can put on the
            // ring. The endpoint accepts it; we simply never sent it, and the
            // callee's card read as an unnamed group.
            chatRoomName?.takeIf { it.isNotBlank() }?.let { put("chatRoomName", JsonPrimitive(it)) }
            projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", JsonPrimitive(it)) }
            projectName?.takeIf { it.isNotBlank() }?.let { put("projectName", JsonPrimitive(it)) }
        },
        projectId,
        callerId,
    ).reading("a call") { it.readCredentials() }

    /** `POST /v1/calls/{id}/accept` — the callee's own credentials for the room. */
    suspend fun acceptCall(
        callId: String,
        userId: String,
        displayName: String,
        projectId: String?,
    ): ZillitResult<LiveKitCallCredentials> = http.call(
        HttpVerb.Post,
        "$baseUrl/v1/calls/${callId.encoded()}/accept",
        buildJsonObject {
            put("userId", JsonPrimitive(userId))
            put("displayName", JsonPrimitive(displayName))
            put("toUserId", JsonPrimitive(userId))
        },
        projectId,
        userId,
    ).reading("a call") { it.readCredentials() }

    /** `POST /v1/livekit/token` — a fresh room token, and the region's public URL with it. */
    suspend fun mintToken(
        callId: String,
        userId: String,
        displayName: String,
        projectId: String?,
    ): ZillitResult<LiveKitCredentials> = http.call(
        HttpVerb.Post,
        "$baseUrl/v1/livekit/token",
        buildJsonObject {
            put("callId", JsonPrimitive(callId))
            put("userId", JsonPrimitive(userId))
            put("displayName", JsonPrimitive(displayName))
        },
        projectId,
        userId,
    ).reading("a token") { (it as? JsonObject)?.readLiveKitCredentials() }

    /**
     * `GET /v1/calls/{id}/roster?userId=` — who is on the call, over HTTP.
     *
     * The socket's `getCallRoster` by another door, and the only door there is
     * while the presence socket is down: the web's `getCallRosterRest`, added
     * for exactly that case. Answers at once and re-seeds the add-user list in
     * the background, so this is the one to read with, not [refreshRoster].
     *
     * Answered raw, not parsed here: `readLiveKitRosterSnapshot` is the one
     * parser and it serves both transports, which is what keeps the socket and
     * the HTTP answer from drifting apart.
     */
    suspend fun roster(callId: String, userId: String, projectId: String?): ZillitResult<JsonElement?> = http.call(
        HttpVerb.Get,
        "$baseUrl/v1/calls/${callId.encoded()}/roster?userId=${userId.encoded()}",
        null,
        projectId,
        userId,
    )

    /**
     * `POST /v1/calls/{id}/roster/refresh` — re-seed from project membership
     * AND reconcile against the live media room, then answer.
     *
     * Slower by design (the web says so outright): for the Refresh button and
     * after a reconnect, never for polling.
     */
    suspend fun refreshRoster(callId: String, userId: String, projectId: String?): ZillitResult<JsonElement?> =
        http.call(
            HttpVerb.Post,
            "$baseUrl/v1/calls/${callId.encoded()}/roster/refresh",
            buildJsonObject { put("userId", JsonPrimitive(userId)) },
            projectId,
            userId,
        )

    /**
     * `POST /v1/client-log` — one line of per-call diagnostics, when the
     * presence socket could not carry it.
     *
     * Never both transports for one line: see `CallDiagnostics`. The body is
     * the phones' (`ApiClient.clientLog`), `data` omitted when empty, and the
     * answer is ignored — a dropped diagnostic is a non-event.
     */
    @Suppress("LongParameterList") // One parameter per field the endpoint takes.
    suspend fun clientLog(
        userId: String,
        deviceId: String,
        callId: String,
        event: String,
        data: JsonObject,
        projectId: String?,
    ): ZillitResult<Unit> = http.call(
        HttpVerb.Post,
        "$baseUrl/v1/client-log",
        buildJsonObject {
            if (userId.isNotBlank()) put("userId", JsonPrimitive(userId))
            if (deviceId.isNotBlank()) put("device_id", JsonPrimitive(deviceId))
            put("callId", JsonPrimitive(callId))
            put("event", JsonPrimitive(event))
            if (data.isNotEmpty()) put("data", data)
        },
        projectId,
        userId,
    ).map { }

    /** `POST /v1/calls/{id}/ringing` — the socket-down way to say the popup is up. */
    suspend fun ackRinging(callId: String, projectId: String?, userId: String?): ZillitResult<Unit> =
        bare("$baseUrl/v1/calls/${callId.encoded()}/ringing", projectId, userId)

    /** `POST /v1/calls/{id}/hangup` — decline, cancel or leave when the socket cannot carry it. */
    suspend fun hangup(callId: String, projectId: String?, userId: String?): ZillitResult<Unit> =
        bare("$baseUrl/v1/calls/${callId.encoded()}/hangup", projectId, userId)

    /** `POST /v1/calls/{id}/react` — the socket-down way to throw an emoji; the server floats it back. */
    suspend fun react(callId: String, emoji: String, projectId: String?, userId: String?): ZillitResult<Unit> =
        http.call(
            HttpVerb.Post,
            "$baseUrl/v1/calls/${callId.encoded()}/react",
            buildJsonObject { put("emoji", JsonPrimitive(emoji)) },
            projectId,
            userId,
        ).map { }

    /** `POST /v1/calls/{id}/hold` and `/resume` — the socket-down twins of `holdCall` / `resumeCall`. */
    suspend fun hold(callId: String, on: Boolean, projectId: String?, userId: String?): ZillitResult<Unit> =
        bare("$baseUrl/v1/calls/${callId.encoded()}/${if (on) "hold" else "resume"}", projectId, userId)

    /**
     * `POST /v1/livekit/mute` — the host has the SFU mute someone's
     * microphone or camera (`source`). Server-enforced; the room's own
     * TrackMuted then tells everyone, so nothing is patched locally.
     */
    @Suppress("LongParameterList") // One parameter per field the endpoint takes.
    suspend fun mute(
        callId: String,
        targetUserId: String,
        source: String,
        byIdentity: String,
        projectId: String?,
        userId: String?,
    ): ZillitResult<Unit> = http.call(
        HttpVerb.Post,
        "$baseUrl/v1/livekit/mute",
        buildJsonObject {
            put("callId", JsonPrimitive(callId))
            put("targetUserId", JsonPrimitive(targetUserId))
            put("source", JsonPrimitive(source))
            put("byIdentity", JsonPrimitive(byIdentity))
        },
        projectId,
        userId,
    ).map { }

    /**
     * `POST /v1/livekit/recording/mark-started|mark-stopped` — the client
     * records; the server only tracks who, broadcasting `recordingBy` in the
     * room's metadata so everyone sees the REC pill. A second starter is
     * refused 409 `already_recording`, which is the failure the caller reads.
     */
    suspend fun markRecording(
        callId: String,
        userId: String,
        on: Boolean,
        projectId: String?,
    ): ZillitResult<Unit> = http.call(
        HttpVerb.Post,
        "$baseUrl/v1/livekit/recording/${if (on) "mark-started" else "mark-stopped"}",
        buildJsonObject {
            put("callId", JsonPrimitive(callId))
            put("userId", JsonPrimitive(userId))
        },
        projectId,
        userId,
    ).map { }

    /** `POST /v1/region/warm` — pre-picks the media region for this device; fire and forget. */
    suspend fun warmRegion(projectId: String?, userId: String?): ZillitResult<Unit> =
        bare("$baseUrl/v1/region/warm", projectId, userId)

    /** `GET /v1/calls/{id}/active` — whether the server still has the call. */
    suspend fun isCallActive(callId: String, projectId: String?, userId: String?): ZillitResult<Boolean> =
        http.call(HttpVerb.Get, "$baseUrl/v1/calls/${callId.encoded()}/active", null, projectId, userId)
            .map { body ->
                val obj = body as? JsonObject
                obj?.bool("active") == true || obj?.bool("ongoing") == true
            }

    private suspend fun bare(url: String, projectId: String?, userId: String?): ZillitResult<Unit> =
        http.call(HttpVerb.Post, url, JsonObject(emptyMap()), projectId, userId).map { }

    /** A body that does not carry [what] is the server's failure, reported, not a crash. */
    private fun <T> ZillitResult<JsonElement?>.reading(
        what: String,
        read: (JsonElement?) -> T?,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> this
        is ZillitResult.Success -> read(data)?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("the calling backend answered without $what"))
    }

    private fun JsonElement?.readCredentials(): LiveKitCallCredentials? {
        val obj = this as? JsonObject ?: return null
        val callId = obj.text("callId") ?: return null
        return LiveKitCallCredentials(
            callId = callId,
            livekit = (obj["livekit"] as? JsonObject)?.readLiveKitCredentials(),
            switchToCallId = (obj["switchTo"] as? JsonObject)?.text("callId"),
        )
    }

    /**
     * A path segment or query value, percent-encoded — call ids are UUIDs
     * today, but neither a segment nor a user id is ever trusted to be.
     */
    private fun String.encoded(): String = buildString {
        for (byte in this@encoded.encodeToByteArray()) {
            val ch = byte.toInt().toChar()
            if (ch.isLetterOrDigit() || ch in UNRESERVED) {
                append(ch)
            } else {
                val value = byte.toInt() and BYTE_MASK
                append('%').append(HEX_DIGITS[value shr HEX_SHIFT]).append(HEX_DIGITS[value and HEX_MASK])
            }
        }
    }

    private companion object {
        const val UNRESERVED = "-_.~"
        const val HEX_DIGITS = "0123456789ABCDEF"
        const val BYTE_MASK = 0xFF
        const val HEX_SHIFT = 4
        const val HEX_MASK = 0xF
    }
}

private const val ERROR_BODY_EXCERPT = 300

/** The phones' `ApiException` as an error: the body's `error` word is the message. */
fun liveKitHttpError(status: Int, body: String?): ZillitError {
    val word = body?.let { raw ->
        runCatching { LIVEKIT_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?.let { it.text("error") ?: it.text("message") }
    }
    // The body verbatim (bounded), because `bad_request` alone says nothing about which field.
    val excerpt = body?.replace(Regex("\\s+"), " ")?.take(ERROR_BODY_EXCERPT).orEmpty()
    return ZillitError.Http(status = status, serverMessage = word, technical = "call-api $status $excerpt")
}
