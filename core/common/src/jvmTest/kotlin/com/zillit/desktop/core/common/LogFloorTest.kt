package com.zillit.desktop.core.common

import io.github.aakira.napier.LogLevel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a production log keeps.
 *
 * Two field logs drove this. One was DEBUG on a production build — thirteen
 * thousand lines of request headers on a user's disk, because the log level was
 * read from a property a release installer does not set (see
 * `EnvironmentResolutionTest`). The other was the opposite: warnings only, for
 * a week, with three local databases wiped and no way to tell whether the
 * database key had been regenerated, because that line was INFO.
 *
 * So neither floor is right on its own, and the split is by tag: the state of
 * the install is kept, what was done on it is not.
 */
class LogFloorTest {

    private val production = ZillitLogging.Floor(verbose = false)
    private val development = ZillitLogging.Floor(verbose = true)

    @Test
    fun `production keeps the lines that say what this install is`() {
        // The build and the environment, the config it loaded, a replaced
        // database key, the session's own story, an update handing over — and
        // how long this machine took to open a production, which is a question
        // only ever asked about somebody else's.
        listOf(
            "Logging", "Startup", "Shutdown", "ConfigLoader", "DatabaseKey", "TokenSession", "AppUpdate", "Open",
        ).forEach { tag -> assertTrue(production.keeps(LogLevel.INFO, tag), tag) }
    }

    /**
     * And not the lines that say what was done on it. `CallCoordinator`'s
     * roster names who was on a call, `MediasoupSession`'s peers name the
     * device each joined from, and `Auth` records every production opened —
     * a standing record of who did what and when, which is the thing the file
     * logger declines to leave on someone's machine.
     */
    @Test
    fun `production keeps no INFO that is a record of the user`() {
        listOf("CallCoordinator", "MediasoupSession", "CallStatusPlane", "Auth", "Chat", "LiveKitLine", "Socket")
            .forEach { tag -> assertFalse(production.keeps(LogLevel.INFO, tag), tag) }
    }

    @Test
    fun `production keeps every warning and error, whoever said it`() {
        listOf("CallCoordinator", "Auth", "Chat", "ApiClient", "Crash", null).forEach { tag ->
            assertTrue(production.keeps(LogLevel.WARNING, tag), "warning from $tag")
            assertTrue(production.keeps(LogLevel.ERROR, tag), "error from $tag")
        }
    }

    /** Debug is for a developer's machine, including from the kept tags. */
    @Test
    fun `production keeps no debug at all`() {
        listOf("Startup", "TokenSession", "DatabaseKey", "Badges", "Presence", null).forEach { tag ->
            assertFalse(production.keeps(LogLevel.DEBUG, tag), tag)
        }
    }

    @Test
    fun `a verbose build keeps everything`() {
        listOf("Startup", "CallCoordinator", "Presence", "LiveKitPeer", null).forEach { tag ->
            assertTrue(development.keeps(LogLevel.DEBUG, tag), tag)
            assertTrue(development.keeps(LogLevel.INFO, tag), tag)
        }
    }

    /** An untagged line is nobody's state: it holds to the ordinary floor. */
    @Test
    fun `an unknown or absent tag gets the ordinary floor`() {
        assertFalse(production.keeps(LogLevel.INFO, null))
        assertFalse(production.keeps(LogLevel.INFO, "SomethingNew"))
        assertTrue(production.keeps(LogLevel.WARNING, "SomethingNew"))
    }
}
