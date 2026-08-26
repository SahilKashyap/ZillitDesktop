package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.CLOSE_REPLACED_BY_OTHER_DEVICE
import com.zillit.desktop.feature.calls.data.protoo.CLOSE_SERVER_SHUTDOWN
import com.zillit.desktop.feature.calls.data.protoo.ProtooNextStep
import com.zillit.desktop.feature.calls.data.protoo.ProtooReconnect
import com.zillit.desktop.feature.calls.data.protoo.RETRY_MAX_MILLIS
import com.zillit.desktop.feature.calls.data.protoo.RETRY_MIN_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When a dropped signalling socket is worth redialling.
 *
 * Getting this wrong is not subtle in effect even though it is subtle in code:
 * too eager and two of a user's own devices evict each other forever; too
 * timid and a call that would have recovered from a lift ends instead.
 */
class ProtooReconnectTest {

    @Test
    fun `an abrupt drop retries, climbing to the ceiling`() {
        val policy = ProtooReconnect()

        val first = policy.onAbruptClose(1006, "abnormal closure")
        assertEquals(ProtooNextStep.Retry(1, RETRY_MIN_MILLIS), first)
        assertEquals(ProtooNextStep.Retry(2, 1_600L), policy.onAbruptClose(1006, ""))
        assertEquals(ProtooNextStep.Retry(3, 3_200L), policy.onAbruptClose(1006, ""))
        assertEquals(ProtooNextStep.Retry(4, RETRY_MAX_MILLIS), policy.onAbruptClose(1006, ""))
        assertEquals(ProtooNextStep.Retry(5, RETRY_MAX_MILLIS), policy.onAbruptClose(1006, ""))
    }

    @Test
    fun `a socket that comes up resets the ladder`() {
        val policy = ProtooReconnect()
        repeat(4) { policy.onAbruptClose(1006, "") }

        policy.onConnected()

        assertEquals(
            ProtooNextStep.Retry(1, RETRY_MIN_MILLIS),
            policy.onAbruptClose(1006, ""),
            "a good connection means the next outage starts from the bottom again",
        )
    }

    /**
     * The one that matters most. 4409 means this user's OTHER device answered;
     * redialling turns that into two devices evicting each other in a loop.
     */
    @Test
    fun `being replaced by the user's other device is terminal`() {
        val byCode = ProtooReconnect().onAbruptClose(CLOSE_REPLACED_BY_OTHER_DEVICE, "")
        assertTrue(byCode is ProtooNextStep.Stop, "4409 must never retry, got $byCode")

        // The code does not always survive; the reason has to carry it too.
        val byReason = ProtooReconnect().onAbruptClose(1006, "replaced-by-other-device")
        assertTrue(byReason is ProtooNextStep.Stop, "the reason alone must stop it, got $byReason")
    }

    /**
     * Reverses an earlier rule that ended the call here.
     *
     * protoo-server sends 4000 whenever it closes a peer on purpose, including
     * during a deploy. It is not the other-device eviction — iOS is explicit
     * about that, from a production room where confusing the two tore down
     * live calls — and the way back is the redial, which already mints a fresh
     * peer id.
     */
    @Test
    fun `a deliberate server shutdown is recovered from, not fatal`() {
        val abrupt = ProtooReconnect().onAbruptClose(CLOSE_SERVER_SHUTDOWN, "closed by protoo-server")
        assertTrue(abrupt is ProtooNextStep.Retry, "got $abrupt")

        // And as a clean close frame, which is how it actually arrives.
        val clean = ProtooReconnect().onCleanClose(CLOSE_SERVER_SHUTDOWN, "closed by protoo-server")
        assertTrue(clean is ProtooNextStep.Retry, "got $clean")
    }

    /**
     * An ordinary clean close is still the server's decision. Matching the
     * phones here is deliberate: a rolling deploy that closes cleanly should
     * not be met with every client stampeding back at once. Only the protoo
     * closes that name a recycled peer are exempt.
     */
    @Test
    fun `an ordinary clean close still never retries`() {
        for (code in listOf(1000, 1001, CLOSE_REPLACED_BY_OTHER_DEVICE)) {
            val step = ProtooReconnect().onCleanClose(code, "")
            assertTrue(step is ProtooNextStep.Stop, "clean close $code must not retry, got $step")
        }
    }

    @Test
    fun `a recovered close still gives up eventually`() {
        // Recovery must not become an infinite redial against a server that
        // keeps recycling us.
        val policy = ProtooReconnect(maxRetries = 2)
        assertTrue(policy.onCleanClose(CLOSE_SERVER_SHUTDOWN, "") is ProtooNextStep.Retry)
        assertTrue(policy.onCleanClose(CLOSE_SERVER_SHUTDOWN, "") is ProtooNextStep.Retry)
        assertTrue(
            policy.onCleanClose(CLOSE_SERVER_SHUTDOWN, "") is ProtooNextStep.Stop,
            "a server recycling us forever must not be redialled forever",
        )
    }

    @Test
    fun `the reason survives into the stop so the call can say why it ended`() {
        val step = ProtooReconnect()
            .onAbruptClose(1006, "replaced-by-other-device") as ProtooNextStep.Stop
        assertEquals("replaced-by-other-device", step.reason)

        val blank = ProtooReconnect().onCleanClose(1001, "") as ProtooNextStep.Stop
        assertTrue(blank.reason.isNotBlank(), "an unexplained close still needs something in the log")
    }

    @Test
    fun `retrying does eventually give up, rather than never`() {
        val policy = ProtooReconnect(maxRetries = 3)
        repeat(3) { assertTrue(policy.onAbruptClose(1006, "") is ProtooNextStep.Retry) }

        val step = policy.onAbruptClose(1006, "")
        assertTrue(step is ProtooNextStep.Stop, "got $step")
        assertTrue("gave up" in step.reason, step.reason)
    }
}
