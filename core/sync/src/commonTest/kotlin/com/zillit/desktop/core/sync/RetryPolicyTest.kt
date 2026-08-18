package com.zillit.desktop.core.sync

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetryPolicyTest {

    private val policy = RetryPolicy(baseMillis = 1_000, capMillis = 60_000, random = Random(7))

    @Test
    fun `only transport failures and server errors are retried`() {
        assertTrue(policy.isRetryable(ZillitError.NoConnection()))
        assertTrue(policy.isRetryable(ZillitError.Timeout()))
        assertTrue(policy.isRetryable(ZillitError.Http(status = 503)))
        assertFalse(policy.isRetryable(ZillitError.Http(status = 400)))
        assertFalse(policy.isRetryable(ZillitError.Forbidden()))
        assertFalse(policy.isRetryable(ZillitError.Validation("no")))
        assertFalse(policy.isRetryable(ZillitError.TlsFailure()), "a certificate problem is never retried quietly")
    }

    @Test
    fun `the wait grows and is capped`() {
        val first = policy.delayFor(1)
        val fourth = policy.delayFor(4)
        val huge = policy.delayFor(40)
        assertTrue(first in 800..1_200, "±20% of the base: $first")
        assertTrue(fourth in 6_400..9_600, "±20% of 8s: $fourth")
        assertTrue(huge <= 60_000, "never past the cap: $huge")
    }

    @Test
    fun `a result becomes the outcome its error deserves`() {
        assertIs<SyncOutcome.Done>(ZillitResult.Success("srv-1").toSyncOutcome(policy) { it }).let {
            assertEquals("srv-1", it.result)
        }
        assertIs<SyncOutcome.RetryLater>(ZillitResult.Failure(ZillitError.NoConnection()).toSyncOutcome(policy))
        assertIs<SyncOutcome.Failed>(ZillitResult.Failure(ZillitError.Http(status = 422)).toSyncOutcome(policy))
    }
}
