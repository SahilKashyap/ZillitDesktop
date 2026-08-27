package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rollback switch, `<ENV>_USE_NEW_URL`.
 *
 * Android's equivalent is a Firebase Remote Config boolean read per call, and
 * it fails *closed* — a fresh install that has not fetched config keeps using
 * the old hosts. This one defaults **on**, because the old hosts are gone:
 * twelve of the thirteen answered 502 on 2026-08-27, so failing closed would
 * ship an app that reaches nothing.
 */
class UseNewUrlFlagTest {

    private fun properties(vararg extra: Pair<String, String>): Map<String, String> = buildMap {
        put("STG_BASE_URL", "https://projectapi-dev.zillit.com")
        put("STG_CASTING_BASE_URL", "https://castingapi-dev.zillit.com")
        put("STG_CONTINUITY_BASE_URL", "https://continuityapi-dev.zillit.com")
        put("STG_DRIVE_BASE_URL", "https://driveapi-dev.zillit.com")
        put("STG_LCW_BASE_URL", "https://lcwapi-dev.zillit.com")
        put("STG_CSE_BASE_URL", "https://cseapi-dev.zillit.com")
        put("STG_URL", "https://dev.zillit.com")
        putAll(extra)
    }

    private fun parse(vararg extra: Pair<String, String>): AppConfig {
        val result = ConfigParser.parse(Environment.Develop, properties(*extra))
        assertTrue(result is ZillitResult.Success, "config failed to parse: $result")
        return (result as ZillitResult.Success).data
    }

    @Test
    fun `absent means consolidated`() {
        val config = parse()

        assertEquals("https://lcwapi-dev.zillit.com", config.baseUrl(ZillitService.Casting))
        assertEquals("https://cseapi-dev.zillit.com", config.baseUrl(ZillitService.Continuity))
    }

    @Test
    fun `false routes back to the old hosts`() {
        val config = parse("STG_USE_NEW_URL" to "false")

        assertEquals("https://castingapi-dev.zillit.com", config.baseUrl(ZillitService.Casting))
        assertEquals("https://continuityapi-dev.zillit.com", config.baseUrl(ZillitService.Continuity))
    }

    /** Whatever a person is likely to type for "off" should mean off. */
    @Test
    fun `the off spellings all work`() {
        listOf("false", "FALSE", "False", "0", "no", " no ").forEach { value ->
            val config = parse("STG_USE_NEW_URL" to value)
            assertEquals(
                "https://castingapi-dev.zillit.com",
                config.baseUrl(ZillitService.Casting),
                "\"$value\" should have meant off",
            )
        }
    }

    @Test
    fun `anything else means on`() {
        listOf("true", "TRUE", "1", "yes", "").forEach { value ->
            val config = parse("STG_USE_NEW_URL" to value)
            assertEquals(
                "https://lcwapi-dev.zillit.com",
                config.baseUrl(ZillitService.Casting),
                "\"$value\" should have meant on",
            )
        }
    }

    @Test
    fun `a module that never moved is unaffected either way`() {
        assertEquals("https://driveapi-dev.zillit.com", parse().baseUrl(ZillitService.Drive))
        assertEquals(
            "https://driveapi-dev.zillit.com",
            parse("STG_USE_NEW_URL" to "false").baseUrl(ZillitService.Drive),
        )
    }

    /** The paths callers append are untouched — only the host swapped. */
    @Test
    fun `the api prefix is unchanged by the swap`() {
        assertEquals(
            "https://lcwapi-dev.zillit.com/api/v2/",
            parse().apiV2(ZillitService.Casting),
        )
    }

    /**
     * Both spellings of the host — with and without a trailing slash — must
     * land the same way. Android needs a `withSlash`/`noSlash` pair per module
     * for this; `baseUrl` trims, so it falls out here.
     */
    @Test
    fun `a trailing slash on the consolidated host makes no difference`() {
        val slashed = parse("STG_LCW_BASE_URL" to "https://lcwapi-dev.zillit.com/")

        assertEquals("https://lcwapi-dev.zillit.com", slashed.baseUrl(ZillitService.Casting))
        assertEquals("https://lcwapi-dev.zillit.com/api/v2/", slashed.apiV2(ZillitService.Casting))
    }
}
