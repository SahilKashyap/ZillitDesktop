package com.zillit.desktop.core.common

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * What happens between picking a production and its board drawing, timed.
 *
 * ## Why
 *
 * The board's own disk read is a few milliseconds and is scoped to one
 * production, so the posts of a dozen other shoots cost it nothing. Yet a
 * person on a dozen productions still waits on "Loading posts…" with a
 * perfectly good page on disk, and the reason is never the read: it is one of
 * the gates in front of it — a cache pruned since the last visit, a tab strip
 * that has to arrive before kept posts can be drawn, a first open that waits
 * on the network, a production token that has to be minted, a dozen other
 * calls queued ahead on the same host.
 *
 * Which of those dominates is a question about one person's machine on one
 * morning, so guessing at it from the code is how the last three attempts
 * went. Each gate marks itself here instead, and a trace from a launch reads
 * top to bottom as the open actually happened.
 *
 * ## Shape
 *
 * [begin] opens a trace, every gate [mark]s or [time]s itself inside it, and
 * [settle] closes it when the board is on screen. Outside a trace a mark is
 * dropped, which is what keeps this to one readable block per open rather
 * than a line for every socket refresh of the rest of the session. A trace
 * that never settles — the person opened a tool and never went to Home —
 * stays open until the next [begin] and collects that board's later reads;
 * a handful of extra lines, and they say something true.
 *
 * Every line goes to the ordinary app log at INFO, following `StartupTrace`'s
 * bargain: a log sent in from the field carries the same breakdown a developer
 * gets locally, and the cost is a formatted string per gate.
 *
 * Marks arrive from several coroutines — the context refresh, the mint and the
 * board's two reads overlap on purpose — so two lines can reach the log out of
 * order. Each carries its own offset from [begin], so the numbers are right
 * even where the order is not.
 */
object OpenTrace {

    @Volatile
    private var started: TimeSource.Monotonic.ValueTimeMark? = null

    /** The production being opened, as a tail — see [tail]. */
    @Volatile
    private var production: String = ""

    /** Opens a trace on [projectId], closing any still standing. */
    fun begin(projectId: String?) {
        production = projectId?.tail().orEmpty()
        started = TimeSource.Monotonic.markNow()
        ZillitLog.i(TAG) { "$production +0ms production selected" }
    }

    /** Marks a gate reached, at its offset from [begin]. Dropped outside a trace. */
    fun mark(step: String) {
        val since = started?.elapsedNow()?.inWholeMilliseconds ?: return
        ZillitLog.i(TAG) { "$production +${since}ms $step" }
    }

    /**
     * Runs [work], marking what it cost as well as when it ran.
     *
     * Returns what [work] returns so a call site wraps a value in place, as
     * `StartupTrace.time` does. The two monotonic reads are taken whether or
     * not a trace is open, because the alternative is reading the flag from an
     * inline function, and a public inline function cannot see it.
     */
    inline fun <T> time(step: String, work: () -> T): T {
        val from = TimeSource.Monotonic.markNow()
        val result = work()
        mark("$step took ${from.elapsedNow().inWholeMilliseconds}ms")
        return result
    }

    /**
     * Closes the trace: the board is on screen, and nothing after this belongs
     * to the open. [step] says what finally drew it — the disk or the server —
     * which is the whole answer when the numbers are read later.
     */
    fun settle(step: String) {
        val since = started?.elapsedNow()?.inWholeMilliseconds ?: return
        started = null
        ZillitLog.i(TAG) { "$production +${since}ms $step — board on screen" }
    }

    /**
     * The last few characters of a production id — enough to tell two opens
     * apart in a log, not enough to be a record of which productions somebody
     * is on. The rule `TokenSessionManager` logs ids under.
     */
    private fun String.tail(): String = if (length <= ID_TAIL) this else "…" + takeLast(ID_TAIL)

    private const val ID_TAIL = 6
    private const val TAG = "Open"
}
