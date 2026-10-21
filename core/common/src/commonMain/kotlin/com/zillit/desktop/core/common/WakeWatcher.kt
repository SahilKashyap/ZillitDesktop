package com.zillit.desktop.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "This machine was away."
 *
 * ## Why the app needs to be told
 *
 * Every live surface here is fed by the socket, and a lid closed on an open
 * app is the one outage nothing notices. The connection dies with the network
 * interface, so whatever was posted, approved, read or sent while the machine
 * slept was announced to a socket that no longer existed — and nothing replays
 * it. On wake the screen is still showing the data it had when the lid went
 * down, with no error, no spinner and no reason for the reader to doubt it.
 * That is the report this exists to answer: *the app does not refresh after
 * sleep.*
 *
 * The window cannot be used as the signal. On macOS a sleeping app keeps its
 * active window, so with no lock screen in the way `isWindowFocused` never
 * goes false and back — the activation seam the badge ledger already reseeds
 * on ([windowActivations] in `main.kt`) simply does not fire. And a reader who
 * wakes the machine to find mail already on screen may never click anything
 * that would make the app ask the server a question.
 *
 * ## The detector
 *
 * A tick that measures itself against the **wall clock**. A loop that asks for
 * [tickMillis] and comes back to find far more than that has passed was not
 * running in between, and the only ordinary reason for that is the machine
 * stopping — sleep, hibernation, a suspended VM.
 *
 * Wall clock rather than a monotonic one deliberately, because the two
 * disagree in exactly the useful way: `delay` is scheduled against a clock
 * that may not advance while the machine is asleep, so the sleep is invisible
 * to it, while `currentTimeMillis` keeps real time throughout. Whichever of
 * the two the platform gives us, one of them shows the gap. (This is the same
 * reasoning the web client writes down at its own wake seam — "setTimeout
 * doesn't tick through OS sleep", `tokenSession.js`.)
 *
 * **Everything this reports is a hint, never a fact.** An NTP correction or a
 * hand-set clock reads as a gap too, and the resync behind it is one round of
 * re-reads — cheap, idempotent, and no worse than being right. The threshold
 * is what keeps the hints rare: a jump under [gapMillis] is ignored, because
 * at that size thread starvation and a GC pause are likelier than sleep and
 * the socket's own heartbeat will have noticed anyway.
 *
 * Pure but for the clock: [nowMillis] and [tick] are injected so the test
 * drives a day of sleeping in a few microseconds.
 */
class WakeWatcher(
    private val scope: CoroutineScope,
    /** How often the clock is checked. Short enough that a wake is noticed while the reader is still looking. */
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    /** The unaccounted-for time that counts as sleep rather than a slow tick. */
    private val gapMillis: Long = DEFAULT_GAP_MILLIS,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val tick: suspend (Long) -> Unit = { delay(it) },
) {

    // Conflated: one resync answers any number of gaps, and a collector that
    // is mid-resync must never hold the loop up.
    private val _wakes = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Each wake, carrying roughly how long the machine was away, in milliseconds. */
    val wakes: SharedFlow<Long> = _wakes.asSharedFlow()

    fun start(): Job = scope.launch {
        var last = nowMillis()
        while (isActive) {
            tick(tickMillis)
            val now = nowMillis()
            // What the tick cannot account for. Subtracting the interval it
            // asked for is what keeps an ordinary tick at zero however long
            // the interval is set to.
            val away = now - last - tickMillis
            last = now
            // A clock moved backwards (an NTP step back, a corrected date) is
            // not a wake and must not read as one through the negative.
            if (away < gapMillis) continue
            ZillitLog.i(TAG) { "this machine was away for about ${away / MILLIS_PER_SECOND}s" }
            _wakes.emit(away)
        }
    }

    companion object {
        /**
         * 20s: inside the socket's own heartbeat window, so a wake is acted on
         * at about the same time the transport would have given up anyway —
         * and 3 clock reads a minute cost nothing.
         */
        const val DEFAULT_TICK_MILLIS = 20_000L

        /**
         * 60s unaccounted for. A screen asleep for less than a minute has
         * nothing worth re-reading, and below this the false positives (a long
         * GC, a starved pool under a build) outnumber the real wakes.
         */
        const val DEFAULT_GAP_MILLIS = 60_000L

        private const val MILLIS_PER_SECOND = 1_000L
        private const val TAG = "Wake"
    }
}
