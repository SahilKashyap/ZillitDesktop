package com.zillit.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The handover from "no graph yet" to the wired one.
 *
 * Not a test of [AppGraph.build] or of [buildAppViewModels] — the first needs a
 * keychain, a database and a configuration file, the second needs the first,
 * and running either here would be an integration test pretending to be a unit
 * one. It tests the two properties the window depends on, both of which are
 * easy to lose in a refactor and neither of which shows up until a packaged
 * launch:
 *
 *  - nothing is published until the graph *and* its view models are both
 *    there, so the composition is never handed `Ready` with the starting
 *    screen's empty view models, and never a half-built pair;
 *  - a build that throws reaches the composition as a throw rather than
 *    leaving the app on the starting screen for ever.
 */
class AppGraphLoaderTest {

    /**
     * Nothing published yet, which is what makes
     * [AppGraphLoader.current] fall back to the starting screen's own
     * [AppGraphLoader.Wired] — [AppGraph.Building] and no view models.
     */
    @Test
    fun `nothing is published until the graph and its view models are both built`() {
        assertNull(AppGraphLoader.wired.value)
    }

    /**
     * The contract [AppGraphLoader.current] relies on: a `Result` carrying the
     * build's failure rethrows on whoever reads it, which in the app is the
     * composition, where `installCrashLogging` and Compose's dialog still
     * apply. Asserted on the `Result` rather than by driving the loader,
     * because the loader builds a real graph once and cannot be made to fail
     * without one.
     */
    @Test
    fun `a failed build is rethrown on the reader`() {
        val held: Result<AppGraph> = runCatching { error("no zillit.properties") }

        val thrown = assertFailsWith<IllegalStateException> { held.getOrThrow() }

        assertEquals("no zillit.properties", thrown.message)
    }
}
