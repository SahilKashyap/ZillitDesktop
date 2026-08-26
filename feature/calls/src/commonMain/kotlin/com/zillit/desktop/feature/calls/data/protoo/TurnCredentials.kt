package com.zillit.desktop.feature.calls.data.protoo

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * One relay the SFU transports may use to get through a hostile network.
 *
 * [urls] is always a list. The WebRTC spec allows a bare string and the
 * backend has never sent one, so the list form is the only shape handled — an
 * assumption worth stating because a silent parse failure here shows up as a
 * call that works everywhere except on cellular.
 */
data class IceServer(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
)

/**
 * The relays for this call, and how long they are good for.
 *
 * These must be in hand BEFORE the transports are created: a transport's ICE
 * policy is fixed at construction, so credentials that arrive afterwards do
 * nothing for the call that needed them. That ordering was a production
 * cellular failure on the phones, not a theoretical one.
 */
data class TurnCredentials(
    val iceServers: List<IceServer>,
    val ttlSeconds: Int,
) {
    val isEmpty: Boolean get() = iceServers.isEmpty()

    /** The shape `RTCPeerConnection` wants, for handing to the page. */
    fun toJson(): JsonArray = buildJsonArray {
        iceServers.forEach { server ->
            add(
                buildJsonObject {
                    put("urls", buildJsonArray { server.urls.forEach { add(JsonPrimitive(it)) } })
                    if (server.username.isNotBlank()) put("username", server.username)
                    if (server.credential.isNotBlank()) put("credential", server.credential)
                },
            )
        }
    }

    companion object {
        /**
         * What to assume when the server does not say.
         *
         * Deliberately short. Over-estimating a TTL means dialling with
         * credentials the relay has already forgotten, which fails at exactly
         * the moment relaying was needed.
         */
        const val DEFAULT_TTL_SECONDS = 600
    }
}

/** The endpoint, on the calling service's v2 base. */
const val TURN_CREDENTIALS_PATH = "webrtc/turn-credentials"

/**
 * Reads the credentials envelope.
 *
 * Returns an empty set rather than null on anything unexpected: a call with no
 * relays still connects for most people on most networks, and refusing to dial
 * because the relay list was malformed would be a worse failure than the one
 * it is guarding against.
 */
fun readTurnCredentials(payload: JsonObject?): TurnCredentials {
    val data = payload?.get("data") as? JsonObject ?: payload
    val servers = data?.get("iceServers") as? JsonArray
    if (servers == null) {
        ZillitLog.w(TAG) { "no iceServers in the turn response; dialling without relays" }
        return TurnCredentials(emptyList(), TurnCredentials.DEFAULT_TTL_SECONDS)
    }

    val parsed = servers.mapNotNull { entry ->
        val server = entry as? JsonObject ?: return@mapNotNull null
        val urls = (server["urls"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (urls.isEmpty()) return@mapNotNull null
        IceServer(
            urls = urls,
            username = (server["username"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            credential = (server["credential"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    val ttl = (data["ttl"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        ?: TurnCredentials.DEFAULT_TTL_SECONDS
    return TurnCredentials(parsed, ttl)
}

private const val TAG = "TurnCredentials"
