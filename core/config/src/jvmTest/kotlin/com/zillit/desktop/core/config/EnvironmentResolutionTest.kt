package com.zillit.desktop.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which environment a launch is aimed at — one rule, asked by more than one
 * caller.
 *
 * `AppGraph` has to choose a log level before any config exists, and used to
 * ask `System.getProperty("zillit.env") != "prod"` for itself. That is not the
 * question [JvmConfigLoader] answers, and the two part company on the one
 * input a release build actually has: a packaged installer bakes in no
 * `zillit.env` at all, so the property is null, `null != "prod"` is true, and
 * every production build logged at DEBUG — request headers and a record of who
 * did what and when, on a real user's disk. These pin the resolution both now
 * read.
 */
class EnvironmentResolutionTest {

    /** Fail closed: anything that is not a known id is production. */
    @Test
    fun `nothing set is production, so a packaged build is never verbose`() {
        assertTrue(Environment.fromId(null).isProduction, "an absent flag is the release case")
        assertTrue(Environment.fromId("").isProduction, "an empty flag is not a request for develop")
        assertTrue(Environment.fromId("  ").isProduction)
        assertTrue(Environment.fromId("nonsense").isProduction)
    }

    @Test
    fun `an explicit environment is honoured, and the log level follows it`() {
        withEnvProperty("develop") {
            assertEquals(Environment.Develop, JvmConfigLoader.environment())
            assertFalse(JvmConfigLoader.environment().isProduction, "develop is the verbose one")
        }
        withEnvProperty("qa") {
            assertEquals(Environment.Qa, JvmConfigLoader.environment())
            assertFalse(JvmConfigLoader.environment().isProduction)
        }
        withEnvProperty("prod") {
            assertTrue(JvmConfigLoader.environment().isProduction)
        }
    }

    /** Spelling is the packager's, not the code's: `-PzillitEnv=PROD` still means prod. */
    @Test
    fun `an environment id is read whatever its case and padding`() {
        withEnvProperty(" PROD ") {
            assertTrue(JvmConfigLoader.environment().isProduction)
        }
        withEnvProperty("Develop") {
            assertEquals(Environment.Develop, JvmConfigLoader.environment())
        }
    }

    /**
     * The property beats `$ZILLIT_ENV`, so these assertions hold whatever the
     * machine running them has set.
     */
    private fun withEnvProperty(value: String, body: () -> Unit) {
        val before: String? = System.getProperty(PROPERTY_ENV)
        System.setProperty(PROPERTY_ENV, value)
        try {
            body()
        } finally {
            if (before == null) System.clearProperty(PROPERTY_ENV) else System.setProperty(PROPERTY_ENV, before)
        }
    }

    private companion object {
        const val PROPERTY_ENV = "zillit.env"
    }
}
