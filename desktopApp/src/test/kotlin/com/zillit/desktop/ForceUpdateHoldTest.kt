package com.zillit.desktop

import com.zillit.desktop.feature.calls.domain.CallPhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a mandatory update covers the frame, and the one thing that holds it off. */
class ForceUpdateHoldTest {

    @Test
    fun `a mandatory update on a packaged build blocks when no call is up`() {
        assertTrue(forceUpdateBlocks(mandatory = true, packaged = true, callPhase = CallPhase.Idle))
    }

    @Test
    fun `a call under way holds the block off until it ends`() {
        listOf(CallPhase.Outgoing, CallPhase.InCall, CallPhase.Ending).forEach { phase ->
            assertFalse(forceUpdateBlocks(mandatory = true, packaged = true, callPhase = phase), "$phase")
        }
    }

    @Test
    fun `a call only ringing in is not ongoing`() {
        assertTrue(forceUpdateBlocks(mandatory = true, packaged = true, callPhase = CallPhase.Incoming))
    }

    @Test
    fun `an optional update or a Gradle run never blocks`() {
        assertFalse(forceUpdateBlocks(mandatory = false, packaged = true, callPhase = CallPhase.Idle))
        assertFalse(forceUpdateBlocks(mandatory = true, packaged = false, callPhase = CallPhase.Idle))
    }
}
