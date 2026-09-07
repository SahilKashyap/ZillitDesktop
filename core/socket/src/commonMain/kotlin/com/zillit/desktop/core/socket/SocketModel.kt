package com.zillit.desktop.core.socket

import com.zillit.desktop.core.common.ZillitError
import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonElement

/**
 * An event name on the wire.
 *
 * A value class rather than a bare `String` so a subscriber cannot accidentally
 * pass a payload where a name belongs — the two are both strings and the
 * compiler would not otherwise care.
 */
@JvmInline
value class SocketEventName(val value: String)

/** One inbound message. */
data class SocketMessage(
    val event: SocketEventName,
    val payload: JsonElement?,
)

/**
 * Connection lifecycle.
 *
 * Modelled explicitly because the UI needs to distinguish these — the status bar
 * shows "connecting", "reconnecting in 4s" and "offline" differently, and a
 * boolean `isConnected` (what the Android client tracks) cannot express the
 * middle states.
 */
sealed interface SocketConnectionState {

    data object Disconnected : SocketConnectionState

    data object Connecting : SocketConnectionState

    data class Connected(val socketId: String?) : SocketConnectionState

    /** Waiting to retry. [attempt] is 1-based; [delayMillis] is what was scheduled. */
    data class Reconnecting(val attempt: Int, val delayMillis: Long) : SocketConnectionState

    /**
     * Gave up, or failed in a way retrying cannot fix.
     *
     * [ZillitError.Unauthorized] here means the device was revoked and the app
     * must sign out rather than keep retrying.
     */
    data class Failed(val error: ZillitError) : SocketConnectionState

    val isConnected: Boolean get() = this is Connected
    val isTransient: Boolean get() = this is Connecting || this is Reconnecting
}

/**
 * Whether a handshake error means the server refused to authenticate us.
 *
 * Read out of the error text because that is all the transport gives us — the
 * client library reports a rejected upgrade as a string, not a status code.
 *
 * Matched on a standalone `401` rather than those digits appearing anywhere:
 * this answer stops the reconnect loop *and* signs the user out, so a port
 * number, a byte count or an id that happens to contain them must not be enough
 * to end someone's session mid-shoot.
 */
fun isHandshakeUnauthorized(detail: String): Boolean =
    UNAUTHORIZED_STATUS.containsMatchIn(detail) || detail.contains("unauthor", ignoreCase = true)

private val UNAUTHORIZED_STATUS = Regex("(?<!\\d)401(?!\\d)")

/** The token verdict of the socket contract: the credential, not the device, was refused. */
fun isSocketTokenRejected(detail: String): Boolean = detail.contains("invalid_token", ignoreCase = true)

/**
 * How to connect.
 *
 * [authHeaders] is a **function**, not a value: the encrypted `moduledata`
 * header has to be rebuilt for every attempt. The Android client builds it once
 * in `initializeSocket()` and reuses it for the life of the process, so a
 * reconnect after the header goes stale fails in a way that looks like a network
 * problem.
 */
data class SocketConfig(
    val url: String,
    val authHeaders: suspend () -> Map<String, String>,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    /**
     * The handshake refused the token it was shown (`libs_invalid_token`).
     * Not a revoked device — the reconnect goes on — but the next
     * [authHeaders] must not present the same token; the host swaps it for
     * a fresh one, or for `moduledata`.
     */
    val onAuthRejected: ((detail: String) -> Unit)? = null,
) {
    init {
        require(url.startsWith("wss://") || url.startsWith("https://")) {
            "socket url must be TLS — cleartext transport is not permitted (plan §8.2)"
        }
    }
}
