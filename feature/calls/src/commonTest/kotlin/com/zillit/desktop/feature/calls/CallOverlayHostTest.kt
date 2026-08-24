package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.ui.drawsStage
import com.zillit.desktop.feature.calls.ui.mountsVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which of the two call surfaces draws what.
 *
 * `CallOverlay` is drawn by both the main window and the call's own window,
 * from one shared state. Both of these rules were once a bare `!pipOpen`
 * inside the composable, which reads correctly in the main window and exactly
 * backwards in the call window — where `pipOpen` is true by construction. The
 * result was an empty rectangle on every video call, and nothing in the suite
 * could see it. These pin the rule where a test can reach it.
 */
class CallOverlayHostTest {

    /** The states the two hosts are actually composed in. */
    private val pipOpenStates = listOf(true, false)
    private val expandedStates = listOf(true, false)

    @Test
    fun `the call window always draws the stage`() {
        // It is the call: there is nothing else in that window to fall back to,
        // and a pill floating in a window of its own is not a thing.
        for (pipOpen in pipOpenStates) {
            for (expanded in expandedStates) {
                assertTrue(
                    drawsStage(ownsCall = true, pipOpen = pipOpen, expanded = expanded),
                    "call window should draw the stage (pipOpen=$pipOpen, expanded=$expanded)",
                )
            }
        }
    }

    @Test
    fun `the main window shows the pill while the call has its own window`() {
        for (expanded in expandedStates) {
            assertEquals(
                false,
                drawsStage(ownsCall = false, pipOpen = true, expanded = expanded),
                "main window should not draw a second stage (expanded=$expanded)",
            )
        }
    }

    @Test
    fun `the main window honours minimise once the call lives inside it`() {
        assertEquals(true, drawsStage(ownsCall = false, pipOpen = false, expanded = true))
        assertEquals(false, drawsStage(ownsCall = false, pipOpen = false, expanded = false))
    }

    /**
     * The invariant that matters most: one browser, one mount. Two hosts
     * claiming it tears the component out of whichever had it, mid-call.
     */
    @Test
    fun `exactly one host mounts the video in every state`() {
        for (pipOpen in pipOpenStates) {
            val claimants = listOf(
                // The call window only exists while pipOpen; see CallWindow's
                // own early return.
                if (pipOpen) mountsVideo(ownsCall = true, pipOpen = true) else false,
                mountsVideo(ownsCall = false, pipOpen = pipOpen),
            ).count { it }
            assertEquals(1, claimants, "pipOpen=$pipOpen should have exactly one video host")
        }
    }

    @Test
    fun `the call window claims the surface whenever it is open`() {
        assertEquals(true, mountsVideo(ownsCall = true, pipOpen = true))
    }
}
