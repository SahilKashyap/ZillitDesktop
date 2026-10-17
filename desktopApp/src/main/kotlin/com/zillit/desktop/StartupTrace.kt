package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog

/**
 * Milestones on the way to the first window, timed from process start.
 *
 * ## Why
 *
 * Startup cost on this path is not where reading the code suggests. The gap
 * worth attacking is between the splash and the window, and the only honest
 * way to split it is from inside a packaged build: a `:desktopApp:run` has a
 * warm Gradle daemon, no jlink image and a different class-loading profile, so
 * its numbers say nothing about what a person installing the MSI sees.
 *
 * Every line goes to the ordinary app log at INFO, so a log sent in from the
 * field carries the same breakdown a developer gets locally. The cost is one
 * formatted string per milestone, and there are a dozen.
 *
 * Timed against [ProcessHandle.startInstant] rather than the first line of
 * `main`, because the JVM spends real time before Kotlin runs — 195 jars to
 * put on the class path — and a trace that starts at `main` hides exactly the
 * part nobody can see.
 */
internal object StartupTrace {

    /**
     * Process start, in `nanoTime` terms, or null when the OS will not say.
     *
     * Resolved once and held: `ProcessHandle.info()` is a system call, and one
     * of those per milestone would be the measurement changing what it
     * measures.
     */
    private val origin: Long? = runCatching {
        val startedMillis = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()
        val sinceStart = System.currentTimeMillis() - startedMillis
        System.nanoTime() - sinceStart * NANOS_PER_MILLI
    }.getOrNull()

    /**
     * Lines reached before there was a log to write them to.
     *
     * The earliest milestones — the splash, the single-instance claim — happen
     * before `ZillitLogging.initialise`, and Napier drops everything logged
     * before a backend is registered. Holding them costs a handful of strings
     * and is the difference between a trace that starts at process start and
     * one that starts a second in.
     */
    private val pending = mutableListOf<String>()

    private var logging = false

    /**
     * Marks the moment [milestone] was reached.
     *
     * Synchronised on the same lock [loggingReady] drains under, and that is
     * not paranoia: the graph thread is what registers the log backend while
     * the main thread is still marking its way to the first window, so a mark
     * that tested the flag outside the lock could be filed a moment after the
     * drain and never printed — the one line somebody would be looking for.
     */
    fun mark(milestone: String) {
        val elapsed = origin?.let { (System.nanoTime() - it) / NANOS_PER_MILLI }
        val line = "${elapsed ?: "?"}ms $milestone"
        val live = synchronized(pending) {
            logging.also { if (!it) pending.add(line) }
        }
        if (live) ZillitLog.i(TAG) { line }
    }

    /**
     * Says the log is up, and writes out everything held until now.
     *
     * Called from [AppGraph.build] immediately after `ZillitLogging.initialise`
     * — the one place that knows the backend exists.
     */
    fun loggingReady() {
        val held = synchronized(pending) {
            logging = true
            pending.toList().also { pending.clear() }
        }
        held.forEach { line -> ZillitLog.i(TAG) { line } }
    }

    /**
     * Runs [work], marking how long it took.
     *
     * Returns what [work] returns so a call site wraps a value in place —
     * `val x = StartupTrace.time("x") { build() }` — rather than growing a
     * temporary and a second statement around every measurement.
     */
    inline fun <T> time(step: String, work: () -> T): T {
        val started = System.nanoTime()
        val result = work()
        val took = (System.nanoTime() - started) / NANOS_PER_MILLI
        mark("$step took ${took}ms")
        return result
    }

    private const val TAG = "Startup"
    const val NANOS_PER_MILLI = 1_000_000L
}
