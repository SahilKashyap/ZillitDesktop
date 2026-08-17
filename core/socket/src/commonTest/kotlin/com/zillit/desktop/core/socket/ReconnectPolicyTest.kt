package com.zillit.desktop.core.socket

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectPolicyTest {

    @Test
    fun `delay grows exponentially`() {
        val policy = ReconnectPolicy(initialDelayMillis = 1_000, multiplier = 2.0, jitterRatio = 0.0)

        assertEquals(1_000, policy.delayFor(1))
        assertEquals(2_000, policy.delayFor(2))
        assertEquals(4_000, policy.delayFor(3))
        assertEquals(8_000, policy.delayFor(4))
    }

    @Test
    fun `delay is capped`() {
        val policy = ReconnectPolicy(initialDelayMillis = 1_000, maxDelayMillis = 5_000, jitterRatio = 0.0)

        assertEquals(5_000, policy.delayFor(10))
        assertEquals(5_000, policy.delayFor(100))
    }

    @Test
    fun `jitter spreads retries around the nominal delay`() {
        // Without this, every client that was online when the server restarted
        // retries on the same schedule, and the synchronised waves can keep a
        // recovering server down. The Android client retries on a fixed delay.
        val policy = ReconnectPolicy(initialDelayMillis = 1_000, jitterRatio = 0.25, multiplier = 1.0)
        val random = Random(seed = 99)

        val delays = List(200) { policy.delayFor(1, random) }

        assertTrue(delays.distinct().size > 100, "delays are not being jittered")
        assertTrue(
            delays.all { it in 750..1_250 },
            "jitter escaped its bounds: ${delays.minOrNull()}..${delays.maxOrNull()}",
        )
    }

    @Test
    fun `jitter never produces a zero delay`() {
        // A zero delay turns reconnect into a busy loop against a server that is
        // already struggling.
        val policy = ReconnectPolicy(initialDelayMillis = 1, jitterRatio = 1.0, multiplier = 1.0)
        val random = Random(seed = 7)

        assertTrue(List(500) { policy.delayFor(1, random) }.all { it >= 1 })
    }

    @Test
    fun `retries forever by default`() {
        // Correct for a desktop app left open overnight: the laptop sleeps, the
        // VPN drops, and the app must still be connected in the morning.
        val policy = ReconnectPolicy()

        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(10_000))
    }

    @Test
    fun `respects a retry cap when one is set`() {
        val policy = ReconnectPolicy(maxAttempts = 3)

        assertTrue(policy.shouldRetry(3))
        assertFalse(policy.shouldRetry(4))
    }

    @Test
    fun `rejects nonsensical configuration at construction`() {
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(initialDelayMillis = 0) }
        assertFailsWith<IllegalArgumentException> {
            ReconnectPolicy(initialDelayMillis = 5_000, maxDelayMillis = 1_000)
        }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(jitterRatio = 2.0) }
    }

    @Test
    fun `attempt numbering is one-based`() {
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy().delayFor(0) }
    }
}
