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
     * `POST /v1/calls`. With callees this both creates and rings (the web's
     * `startCallRest`); with none it only creates, and `startCall` over the
     * socket does the ringing.
     */
    @Suppress("LongParameterList") // One parameter per field the endpoint takes; a wrapper would only rename them.
    suspend fun createCall(
        callerId: String,
        callerName: String,
        callMode: CallMode,
        callType: CallType,
        calleeIds: List<String>,
        chatRoomId: String?,
        projectId: String?,
        projectName: String?,
    ): ZillitResult<LiveKitCallCredentials> = http.call(
        HttpVerb.Post,
        "$baseUrl/v1/calls",
        buildJsonObject {
            put("callerId", JsonPrimitive(callerId))
            put("callerName", JsonPrimitive(callerName))
            put("type", JsonPrimitive(callMode.wire))
            put("callMode", JsonPrimitive(callMode.wire))
            put("callType", JsonPrimitive(callType.wire))
            put("calleeIds", JsonArray(calleeIds.map(::JsonPrimitive)))
            chatRoomId?.takeIf { it.isNotBlank() }?.let { put("chatRoomId", JsonPrimitive(it)) }
            projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", JsonPrimitive(it)) }
            projectName?.takeIf { it.isNotBlank() }?.let { put("projectName", JsonPrimitive(it)) }
        },
        projectId,
        callerId,
    ).reading("a call") { it.readCredentials() }

    /**
     * `POST /v1/calls` with nobody to ring — the web's `createCall`, and what
     * the socket-first flow mints its call id with. The body says `group` and
     * names no callees whatever the call will be; the socket's `startCall`
     * carries the real mode, type and callees a moment later. Observed on
     * prod (2026-09-05): the same body with `type: private` and no callees is
     * refused with 400 `bad_request`, so the mint must not describe the call.
     */
    suspend fun mintCall(
        callerId: String,
        callerName: String,
        projectId: String?,
    ): ZillitResult<LiveKitCallCredentials> =
        http.call(
            HttpVerb.Post,
            "$baseUrl/v1/calls",
            buildJsonObject {
                put("callerId", JsonPrimitive(callerId))
                put("callerName", JsonPrimitive(callerName))
                put("type", JsonPrimitive(CallMode.Group.wire))
                put("callMode", JsonPrimitive(CallMode.Group.wire))
                put("calleeIds", JsonArray(emptyList()))
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

    /** `POST /v1/calls/{id}/ringing` — the socket-down way to say the popup is up. */
    suspend fun ackRinging(callId: String, projectId: String?, userId: String?): ZillitResult<Unit> =
        bare("$baseUrl/v1/calls/${callId.encoded()}/ringing", projectId, userId)

    /** `POST /v1/calls/{id}/hangup` — decline, cancel or leave when the socket cannot carry it. */
    suspend fun hangup(callId: String, projectId: String?, userId: String?): ZillitResult<Unit> =
        bare("$baseUrl/v1/calls/${callId.encoded()}/hangup", projectId, userId)

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

    /** A path segment, percent-encoded — call ids are UUIDs today, but a segment is never trusted to be. */
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

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

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
