package com.zillit.desktop.feature.calls.data.protoo

/** What the socket should do next, once it is no longer connected. */
sealed interface ProtooNextStep {
    /** Dial again after [delayMillis]. */
    data class Retry(val attempt: Int, val delayMillis: Long) : ProtooNextStep

    /** Do not dial again. [reason] is for the log and for the call's ending. */
    data class Stop(val reason: String) : ProtooNextStep
}

/**
 * Whether a dropped signalling socket should be dialled again, and when.
 *
 * A pure state machine, apart from the socket, because every rule in it is a
 * decision about somebody's live call and none of them can be exercised
 * through a real WebSocket in a test.
 *
 * Three rules, each one learned the hard way by the phones:
 *
 * 1. A *clean* close is the server's decision and is final. Reconnecting into
 *    one produces a client that fights a deliberate shutdown.
 * 2. Two close codes are terminal whatever else is true — and 4409 especially,
 *    because it means this user's other device took the call. Redialling there
 *    is a loop where two of the user's own devices evict each other.
 * 3. Everything else — a failed read, a dead write, a keepalive that found the
 *    socket half-open — is a network event, and network events are worth
 *    retrying for far longer than feels reasonable. A call is worth more than
 *    the sockets it costs.
 */
class ProtooReconnect(
    private val maxRetries: Int = PROTOO_MAX_RETRIES,
) {
    private var attempt = 0

    /** A socket came up. The ladder starts again from the bottom next time. */
    fun onConnected() {
        attempt = 0
    }

    /**
     * The socket closed with a close frame — the server said goodbye.
     *
     * Always final, whatever the code. This is deliberate and matches the
     * phones: a server that closes cleanly during a rolling deploy ends the
     * call rather than having every client stampede back at once.
     */
    fun onCleanClose(code: Int, reason: String): ProtooNextStep =
        ProtooNextStep.Stop(
            if (isTerminalProtooClose(code, reason)) reason.ifBlank { "closed by server ($code)" }
            else "closed by server ($code)",
        )

    /**
     * The socket died without a close frame, or a close frame we can still
     * read a terminal verdict out of.
     */
    fun onAbruptClose(code: Int, reason: String): ProtooNextStep {
        if (isTerminalProtooClose(code, reason)) {
            return ProtooNextStep.Stop(reason.ifBlank { "terminal close ($code)" })
        }
        if (attempt >= maxRetries) {
            return ProtooNextStep.Stop("gave up after $maxRetries attempts")
        }
        val delay = protooRetryDelayMillis(attempt)
        attempt++
        return ProtooNextStep.Retry(attempt, delay)
    }
}
