package com.zillit.desktop.core.common

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gap detector, driven through a day of sleeping in a few microseconds.
 *
 * The false positives are what these tests are really about. A wake costs the
 * app a round of re-reads, so one it reports wrongly is cheap — but one it
 * reports on *every* tick would put the app in a permanent resync, and a clock
 * that steps backwards is the way that happens.
 */
class WakeWatcherTest {

    @Test
    fun `an ordinary tick is not a wake`() = runTest {
        val clock = FakeClock()
        val watcher = watcher(clock)
        val seen = collect(watcher)

        watcher.start()
        advanceTimeBy(TICK * 5)
        runCurrent()

        assertEquals(emptyList(), seen)
    }

    @Test
    fun `time the tick cannot account for is a wake`() = runTest {
        val clock = FakeClock()
        val watcher = watcher(clock)
        val seen = collect(watcher)
        watcher.start()

        // The lid closes for an hour: `tick` comes back having asked for 20s
        // and finds the wall clock an hour further on.
        clock.sleepOnNextTick = 60 * 60 * 1_000L
        advanceTimeBy(TICK + 1)
        runCurrent()

        assertEquals(1, seen.size)
        assertEquals(60 * 60 * 1_000L, seen.single())
    }

    @Test
    fun `a gap under the threshold is a slow tick, not a wake`() = runTest {
        val clock = FakeClock()
        val watcher = watcher(clock)
        val seen = collect(watcher)
        watcher.start()

        // A starved pool, a long GC, a build running on the same machine.
        clock.sleepOnNextTick = GAP - 1
        advanceTimeBy(TICK + 1)
        runCurrent()

        assertTrue(seen.isEmpty(), "a ${GAP - 1}ms overrun should not resync the app")
    }

    @Test
    fun `a clock stepped backwards is not a wake`() = runTest {
        val clock = FakeClock()
        val watcher = watcher(clock)
        val seen = collect(watcher)
        watcher.start()

        // NTP correcting a fast clock. Read as a negative gap, which must not
        // slip through a comparison written the other way round.
        clock.sleepOnNextTick = -(10 * 60 * 1_000L)
        advanceTimeBy(TICK + 1)
        runCurrent()

        assertTrue(seen.isEmpty(), "a backwards clock step is not a wake")
    }

    @Test
    fun `waking twice is reported twice`() = runTest {
        val clock = FakeClock()
        val watcher = watcher(clock)
        val seen = collect(watcher)
        watcher.start()

        clock.sleepOnNextTick = GAP * 2
        advanceTimeBy(TICK + 1)
        runCurrent()
        clock.sleepOnNextTick = GAP * 3
        advanceTimeBy(TICK + 1)
        runCurrent()

        assertEquals(listOf(GAP * 2, GAP * 3), seen)
    }

    // -- harness -----------------------------------------------------------

    /** A wall clock that advances with each tick, plus whatever the machine slept through. */
    private class FakeClock {
        var millis = 1_700_000_000_000L
        var sleepOnNextTick = 0L

        fun advance(asked: Long) {
            millis += asked + sleepOnNextTick
            sleepOnNextTick = 0
        }
    }

    private fun kotlinx.coroutines.test.TestScope.watcher(clock: FakeClock) = WakeWatcher(
        scope = backgroundScope,
        tickMillis = TICK,
        gapMillis = GAP,
        nowMillis = { clock.millis },
        // The real tick suspends; so does this one, so the test's virtual
        // clock drives the loop while the fake wall clock tells it a
        // different story — which is the whole mechanism under test.
        tick = { asked ->
            kotlinx.coroutines.delay(asked)
            clock.advance(asked)
        },
    )

    private fun kotlinx.coroutines.test.TestScope.collect(watcher: WakeWatcher): List<Long> {
        val seen = mutableListOf<Long>()
        // Subscribed before `start`: the stream has no replay, so a wake
        // emitted with nobody listening is gone.
        backgroundScope.launch { watcher.wakes.collect { seen += it } }
        runCurrent()
        return seen
    }

    private companion object {
        const val TICK = 20_000L
        const val GAP = 60_000L
    }
}
