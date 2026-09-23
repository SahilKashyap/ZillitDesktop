package com.zillit.desktop.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wheel distance, without a wheel.
 *
 * The plumbing around this — which events to intercept, when to consume them —
 * can only be judged with a hand on a real mouse. The arithmetic cannot, and it
 * is where the sign errors and the off-by-a-factor-of-three live, so it is
 * separated out and pinned here.
 */
class WheelScrollTest {

    private val line = 20f

    @Test
    fun `a notch scrolls the system's lines, not one`() {
        // macOS defaults to three lines a notch, so a 20f line is 60f of
        // travel. The line width is WHEEL_LINE's business; this only fixes the
        // arithmetic that multiplies it.
        assertEquals(60f, wheelScrollDistance(notches = 1f, linesPerNotch = 3, linePx = line))
    }

    @Test
    fun `the system's scroll speed setting is honoured, not overridden`() {
        // Someone who has turned their wheel down to one line gets one line;
        // someone who has turned it up to ten gets ten. Fixing the distance here
        // would quietly ignore a preference they set deliberately.
        assertEquals(20f, wheelScrollDistance(1f, linesPerNotch = 1, linePx = line))
        assertEquals(200f, wheelScrollDistance(1f, linesPerNotch = 10, linePx = line))
    }

    @Test
    fun `scrolling up is negative and scrolling down is positive`() {
        // Matching ScrollableState.scrollBy, where positive runs towards the end
        // of the content. An inverted wheel is the obvious way to get this wrong.
        assertTrue(wheelScrollDistance(notches = 1f, linesPerNotch = 3, linePx = line) > 0f)
        assertTrue(wheelScrollDistance(notches = -1f, linesPerNotch = 3, linePx = line) < 0f)
    }

    @Test
    fun `a reversed list scrolls the other way, as its own scrollable does`() {
        // The chat thread is reverseLayout: its content runs from the bottom.
        // A notch towards the user's "down" must still move the eye down.
        assertEquals(-60f, wheelScrollDistance(1f, linesPerNotch = 3, linePx = line, reverseDirection = true))
        assertEquals(60f, wheelScrollDistance(-1f, linesPerNotch = 3, linePx = line, reverseDirection = true))
    }

    @Test
    fun `a trackpad's whole-number event is still the trackpad's`() {
        // The 2026-09-23 jump: one integer-valued event in a fractional stream
        // was stepped as a notch. Within the grace after a fractional event,
        // it stays with the platform.
        assertTrue(isTrackpadStream(isPrecise = false, atMillis = 5_016, lastPreciseMillis = 5_000))
        assertTrue(isTrackpadStream(isPrecise = true, atMillis = 5_000, lastPreciseMillis = 0))
    }

    @Test
    fun `a stepped wheel that never sent a fractional event is still stepped`() {
        assertFalse(isTrackpadStream(isPrecise = false, atMillis = 5_000, lastPreciseMillis = Long.MIN_VALUE / 2))
        assertFalse(
            isTrackpadStream(isPrecise = false, atMillis = 9_000, lastPreciseMillis = 9_000 - TRACKPAD_GRACE_MILLIS),
        )
    }

    @Test
    fun `several notches in one event scroll several notches' worth`() {
        // A fast flick coalesces into one event carrying the total rotation.
        assertEquals(180f, wheelScrollDistance(3f, linesPerNotch = 3, linePx = line))
    }

    @Test
    fun `a system reporting no lines per notch still scrolls`() {
        // Consuming the event and then scrolling nothing is a dead wheel, which
        // is worse than the default we replaced.
        assertEquals(line, wheelScrollDistance(1f, linesPerNotch = 0, linePx = line))
    }

    @Test
    fun `the line is scaled by density, so the distance is not in raw pixels`() {
        // linePx arrives already converted, so a Retina display moves the same
        // apparent distance as a 1x one rather than half of it.
        assertEquals(120f, wheelScrollDistance(1f, linesPerNotch = 3, linePx = line * 2))
    }
}
