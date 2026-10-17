package com.zillit.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The startup trace, which is only useful if it survives the part of startup
 * that has no log yet.
 *
 * The milestones that matter most — the splash going up, the shared state
 * being primed — all happen before `ZillitLogging.initialise`, and Napier
 * drops everything logged before a backend is registered. So the one thing
 * worth asserting is that marking before the log exists does not lose the
 * line and does not throw.
 */
class StartupTraceTest {

    /** Marking before `loggingReady` is safe, and so is marking after it. */
    @Test
    fun `marking either side of the log is harmless`() {
        StartupTrace.mark("before the log")
        StartupTrace.loggingReady()
        StartupTrace.mark("after the log")
    }

    /** [StartupTrace.time] is a wrapper, not a substitute: it returns the value. */
    @Test
    fun `timing a step hands back what the step produced`() {
        assertEquals("wired", StartupTrace.time("a step") { "wired" })
    }
}
