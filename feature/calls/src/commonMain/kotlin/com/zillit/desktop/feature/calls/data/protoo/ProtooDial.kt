package com.zillit.desktop.feature.calls.data.protoo

/**
 * Where to dial, and when to stop dialling.
 *
 * Small pure rules that the socket layer applies. They live apart from it
 * because each one is a decision the phones got wrong at least once, and none
 * of them can be exercised through a real WebSocket in a test.
 */

/**
 * Strips a URL back to the bare host the dial URL needs.
 *
 * The server sometimes elects a host with a scheme on it and sometimes without,
 * and has been seen to send `wss://https://…`. Any leading scheme goes, the
 * path goes, and the port stays — a host with an explicit port is a real
 * deployment, not a mistake.
 */
private const val SCHEME_MARKER = "://"

fun normaliseSfuHost(raw: String): String {
    var host = raw.trim()
    // Repeatedly, because the doubled-scheme case is real.
    while (true) {
        val marker = host.indexOf(SCHEME_MARKER)
        if (marker < 0) break
        host = host.substring(marker + SCHEME_MARKER.length)
    }
    return host.substringBefore('/').trim()
}

/**
 * The protoo dial URL.
 *
 * `peerId` is not percent-encoded: it carries a colon by design
 * (`userId:deviceId`) and the phones send it raw, so encoding it would address
 * a different peer than every other client.
 */
fun protooDialUrl(host: String, roomId: String, peerId: String, sfuToken: String = ""): String {
    val clean = normaliseSfuHost(host)
    val base = "wss://$clean/?roomId=$roomId&peerId=$peerId"
    // An empty token is the tokenless dial this backend still accepts, not a
    // missing credential — appending `&token=` would be a different request.
    return if (sfuToken.isBlank()) base else "$base&token=$sfuToken"
}

/**
 * Whether a close ends the session for good rather than inviting a reconnect.
 *
 * ONE code and one phrase. 4409 means this user's *other* device took the
 * call: reconnecting would fight it in a loop neither device wins, and the
 * phrase is carried as well as the code because some platforms normalise
 * custom 4000-range codes away.
 *
 * 4000 used to be in here and was wrong. protoo-server sends it whenever it
 * closes a peer deliberately — a rolling deploy, a session it is recycling —
 * and iOS is explicit that a bare 4000 is never another device (a production
 * room where treating it as one tore down live calls). It belongs in
 * [isRecoverableProtooClose], where a fresh peer id gets the call back.
 */
fun isTerminalProtooClose(code: Int, reason: String): Boolean =
    code == CLOSE_REPLACED_BY_OTHER_DEVICE ||
        REPLACED_BY_OTHER_DEVICE in reason.lowercase()

/**
 * A close the server made on purpose that we are meant to come back from.
 *
 * Distinct from an ordinary clean close, which stays final: a server saying
 * goodbye during a deploy should not have every client stampede back at once,
 * and iOS treats a plain close frame as terminal too. These particular
 * closes are protoo recycling one peer, and the way back is a redial with a
 * fresh peer id — which every dial already mints.
 */
fun isRecoverableProtooClose(code: Int, reason: String): Boolean {
    if (isTerminalProtooClose(code, reason)) return false
    if (code == CLOSE_SERVER_SHUTDOWN) return true
    val text = reason.lowercase()
    return RECOVERABLE_CLOSE_REASONS.any { it in text }
}

/** protoo-server closing us deliberately. Recoverable, not terminal. */
const val CLOSE_SERVER_SHUTDOWN = 4000

/** This user's other device joined and evicted this one. */
const val CLOSE_REPLACED_BY_OTHER_DEVICE = 4409

private const val REPLACED_BY_OTHER_DEVICE = "replaced-by-other-device"

private val RECOVERABLE_CLOSE_REASONS = listOf(
    "closed by protoo-server",
    "session replaced",
    "duplicate session",
    "peer reconnected",
)

/**
 * How long to wait before the next dial.
 *
 * Doubling from 800ms to a 4s ceiling, and it does not give up for a very long
 * time — a call is worth far more than the handful of sockets it costs to keep
 * trying, and the phones' first attempt at this exhausted in about seventy
 * seconds, which was shorter than their own call-drop windows and so ended
 * calls that would have recovered.
 */
fun protooRetryDelayMillis(attempt: Int): Long {
    if (attempt <= 0) return RETRY_MIN_MILLIS
    val doubled = RETRY_MIN_MILLIS shl attempt.coerceAtMost(RETRY_SHIFT_CAP)
    return doubled.coerceAtMost(RETRY_MAX_MILLIS)
}

const val RETRY_MIN_MILLIS = 800L
const val RETRY_MAX_MILLIS = 4_000L

/** The point past which doubling would overflow long before it mattered. */
private const val RETRY_SHIFT_CAP = 16

/** Give-up count. Effectively "not until the call itself is over". */
const val PROTOO_MAX_RETRIES = 600

/** Half-open sockets are invisible without this; see the transport. */
const val PROTOO_PING_INTERVAL_MILLIS = 20_000L
