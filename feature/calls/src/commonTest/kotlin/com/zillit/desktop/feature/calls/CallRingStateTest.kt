package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallRingState
import com.zillit.desktop.feature.calls.domain.CallStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outgoing screen's status line, the web's `RING_TEXT` / `bestRing`.
 *
 * The thing worth pinning is that it is MONOTONIC. A group ring has several
 * callees moving independently, and a line that went "Ringing…" → "Calling…"
 * because a second device was slower to acknowledge reads as the call going
 * backwards.
 */
class CallRingStateTest {

    @Test
    fun `the wire's verdicts map onto what the caller is told`() {
        assertEquals(CallRingState.Ringing, CallRingState.of(CallStatus.Ringing))
        assertEquals(CallRingState.Accepted, CallRingState.of(CallStatus.InCall))
        assertEquals(CallRingState.Accepted, CallRingState.of(CallStatus.Caller))
        assertEquals(CallRingState.Declined, CallRingState.of(CallStatus.Declined))
        assertEquals(CallRingState.NoAnswer, CallRingState.of(CallStatus.NotAnswered))
        // The two [CallStatus] cannot spell, which is why they travel as flags.
        assertEquals(CallRingState.Busy, CallRingState.of(CallStatus.Declined, busy = true))
        assertEquals(
            CallRingState.Unavailable,
            CallRingState.of(CallStatus.NotAnswered, unreachable = true),
            "no registered device is a different answer from a ring that rang out",
        )
        // Someone who WAS in the call and left is not a verdict on a ring.
        assertNull(CallRingState.of(CallStatus.Left))
    }

    @Test
    fun `the line a group ring shows is the furthest any callee has got`() {
        assertEquals(
            CallRingState.Accepted,
            CallRingState.best(listOf(CallRingState.Declined, CallRingState.Accepted, CallRingState.Calling)),
        )
        assertEquals(
            CallRingState.Ringing,
            CallRingState.best(listOf(CallRingState.Calling, CallRingState.Ringing)),
            "one slow device must not drag the line back to Calling…",
        )
        assertEquals(CallRingState.Calling, CallRingState.best(listOf(CallRingState.Calling)))
        // Nobody pending and nobody in: the first negative is the whole story.
        assertEquals(CallRingState.Busy, CallRingState.best(listOf(CallRingState.Busy)))
        assertNull(CallRingState.best(emptyList()), "no ring to describe")
    }

    @Test
    fun `only a callee who could still pick up counts as pending`() {
        assertTrue(CallRingState.Calling.isPending)
        assertTrue(CallRingState.Ringing.isPending)
        assertTrue(!CallRingState.Accepted.isPending)
        assertTrue(!CallRingState.Declined.isPending)
        assertTrue(!CallRingState.NoAnswer.isPending)
    }

    @Test
    fun `every state has words of its own`() {
        val labels = CallRingState.entries.map { it.label }
        assertTrue(labels.none(String::isBlank), "a state with no words would draw an empty line: $labels")
        assertEquals(
            labels.size,
            labels.toSet().size,
            "two states reading the same is two states nobody can tell apart",
        )
        assertEquals("Ringing…", CallRingState.Ringing.label)
        assertEquals("Calling…", CallRingState.Calling.label)
        assertEquals("Joining…", CallRingState.Accepted.label)
    }
}
