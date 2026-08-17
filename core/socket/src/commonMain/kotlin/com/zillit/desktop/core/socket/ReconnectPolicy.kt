package com.zillit.desktop.core.socket

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter.
 *
 * Socket.IO's own reconnect is disabled (as it is on Android, which sets
 * `reconnection = false`) because the auth header must be rebuilt for each
 * attempt — something the library cannot do for us.
 *
 * **Jitter is the part that matters.** Without it, every client that was online
 * when a server restarted retries on the same schedule, and the resulting
 * synchronised waves can keep a recovering server down. The Android client
 * retries on a fixed delay, so a production-wide reconnect is a thundering herd.
 *
 * Pure and separately testable — timing bugs found by reading are cheaper than
 * timing bugs found in production.
 */
data class ReconnectPolicy(
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 60_000,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.25,
    /** Null means retry forever — correct for a desktop app left open overnight. */
    val maxAttempts: Int? = null,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within 0..1" }
    }

    fun shouldRetry(attempt: Int): Boolean = maxAttempts == null || attempt <= maxAttempts

    /**
     * Delay before [attempt] (1-based), jittered.
     *
     * [random] is injectable so tests can assert the bounds deterministically.
     */
    fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        val exponential = initialDelayMillis * multiplier.pow(attempt - 1)
        val capped = min(exponential, maxDelayMillis.toDouble())

        // Zero jitter is a legitimate configuration (tests, and anyone who
        // wants deterministic timing). `nextDouble(from, until)` requires
        // from < until, so it has to be short-circuited rather than passed
        // equal bounds.
        val jitter = capped * jitterRatio
        if (jitter <= 0.0) return capped.toLong().coerceAtLeast(1)

        // Symmetric jitter around the capped delay, floored at 1ms so a retry
        // never becomes a busy loop.
        val low = (capped - jitter).coerceAtLeast(1.0)
        val high = capped + jitter
        return random.nextDouble(low, high).toLong().coerceAtLeast(1)
    }
}
