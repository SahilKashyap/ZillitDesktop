package com.zillit.desktop.core.sync

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlin.math.min
import kotlin.random.Random

/**
 * When to try again, and how long to wait.
 *
 * ## Which failures time fixes
 *
 * Only the transport ones — no connection, a timeout — and a server that fell
 * over (5xx). Everything else is the *content* being refused: a 4xx, a
 * validation error, a certificate problem. Retrying those quietly would hide
 * a refusal from the user for as long as the app runs, so they park the
 * operation instead.
 *
 * ## The wait
 *
 * Exponential from [baseMillis], capped at [capMillis], with ±20% jitter so a
 * crew all coming back online in the same tent do not hammer the server in
 * lockstep. The socket reconnect and every successful request also wake the
 * engine early, so the cap only matters when nothing else is happening.
 */
class RetryPolicy(
    private val baseMillis: Long = DEFAULT_BASE_MILLIS,
    private val capMillis: Long = DEFAULT_CAP_MILLIS,
    private val random: Random = Random.Default,
) {

    fun isRetryable(error: ZillitError): Boolean = when (error) {
        is ZillitError.NoConnection, is ZillitError.Timeout -> true
        is ZillitError.Http -> error.status >= FIRST_SERVER_ERROR
        else -> false
    }

    /** How long after the [attempts]th failure to wait, in millis. */
    fun delayFor(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, MAX_EXPONENT)
        val nominal = min(baseMillis shl exponent, capMillis)
        val jitter = (nominal * JITTER * (random.nextDouble() * 2 - 1)).toLong()
        return (nominal + jitter).coerceIn(0, capMillis)
    }

    /** The outcome a failure of [error] deserves under this policy. */
    fun outcomeFor(error: ZillitError): SyncOutcome =
        if (isRetryable(error)) SyncOutcome.RetryLater(error) else SyncOutcome.Failed(error)

    companion object {
        const val DEFAULT_BASE_MILLIS = 5_000L
        const val DEFAULT_CAP_MILLIS = 5 * 60_000L
        private const val MAX_EXPONENT = 16
        private const val JITTER = 0.2
        private const val FIRST_SERVER_ERROR = 500
    }
}

/**
 * The common case for a handler: one repository call decides the outcome.
 * [result] turns a success into what dependents may need — a server id.
 */
fun <T> ZillitResult<T>.toSyncOutcome(
    policy: RetryPolicy = RetryPolicy(),
    result: (T) -> String? = { null },
): SyncOutcome = when (this) {
    is ZillitResult.Success -> SyncOutcome.Done(result(data))
    is ZillitResult.Failure -> policy.outcomeFor(error)
}
