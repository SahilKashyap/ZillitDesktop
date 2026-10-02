package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `<ENV>_WEATHER_API_KEY`, which is optional.
 *
 * Every other secret here is absent-or-nothing, so this is the one key with a
 * baked-in default: OpenWeatherMap is a third party with one account across
 * environments, Android hardcodes the same value and the web inlines it, and a
 * properties file written before the key existed used to blank the Weather tool
 * along with the Call Sheet and Production Report widgets.
 */
class WeatherApiKeyTest {

    private fun properties(vararg extra: Pair<String, String>): Map<String, String> = buildMap {
        put("STG_BASE_URL", "https://projectapi-dev.zillit.com")
        put("STG_URL", "https://dev.zillit.com")
        putAll(extra)
    }

    private fun parse(vararg extra: Pair<String, String>): AppConfig {
        val result = ConfigParser.parse(Environment.Develop, properties(*extra))
        assertTrue(result is ZillitResult.Success, "config failed to parse: $result")
        return (result as ZillitResult.Success).data
    }

    @Test
    fun `absent falls back to the project key`() {
        assertEquals("11c35c1b32e209927702b8fef0abab55", parse().weatherApiKey)
    }

    @Test
    fun `the file wins when it carries a key`() {
        assertEquals("machine-own-key", parse("STG_WEATHER_API_KEY" to "machine-own-key").weatherApiKey)
    }

    @Test
    fun `a blank value falls back rather than disabling the tool`() {
        assertEquals("11c35c1b32e209927702b8fef0abab55", parse("STG_WEATHER_API_KEY" to "   ").weatherApiKey)
    }

    @Test
    fun `the key is redacted from toString`() {
        val rendered = parse().toString()

        assertTrue("11c35c1b32e209927702b8fef0abab55" !in rendered, "key leaked into: $rendered")
        assertTrue("weatherApiKey=present" in rendered, rendered)
    }
}
