package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Built from the shape of the real Zillit config file.
 *
 * Every awkward case here is one the actual file contains — escaped colons,
 * spaces around the separator, escaped `=` inside a URL query. A naive
 * `split('=')` passes a hand-written test file and then rejects every URL in
 * the real one.
 */
class PropertiesParserTest {

    @Test
    fun `escaped colons are unescaped`() {
        // The whole file is written this way by the Android tooling. Left
        // escaped, `https\://` fails the TLS check and the app refuses to start.
        val parsed = PropertiesParser.parse("""PROD_BASE_URL=https\://projectapi.zillit.com/""")

        assertEquals("https://projectapi.zillit.com/", parsed["PROD_BASE_URL"])
        assertTrue(parsed.getValue("PROD_BASE_URL").startsWith("https://"))
    }

    @Test
    fun `unescaped colons in a value are kept`() {
        // Some lines are written plainly; both forms have to work.
        val parsed = PropertiesParser.parse("PROD_URL=https://web.zillit.com")

        assertEquals("https://web.zillit.com", parsed["PROD_URL"])
    }

    @Test
    fun `the first unescaped separator wins`() {
        // `=` at index 8 beats the `:` inside the URL.
        val parsed = PropertiesParser.parse("PROD_URL=https://web.zillit.com")

        assertEquals(setOf("PROD_URL"), parsed.keys)
    }

    @Test
    fun `escaped equals inside a value survives`() {
        // From the real file's UPDATE_LINK entries.
        val parsed = PropertiesParser.parse(
            """PROD_UPDATE_LINK=https\://play.google.com/store/apps/details?id\=com.zillit.zillitapp""",
        )

        assertEquals(
            "https://play.google.com/store/apps/details?id=com.zillit.zillitapp",
            parsed["PROD_UPDATE_LINK"],
        )
    }

    @Test
    fun `whitespace around the separator is stripped`() {
        // The real file has both `KEY= value` and `KEY = value`.
        val parsed = PropertiesParser.parse(
            """
            PROD_DRIVE_BASE_URL= https://driveapi.zillit.com/
            PROD_MEDIASOUP_CALLING_URL = calling-sfu-prod.zillit.com
            """.trimIndent(),
        )

        assertEquals("https://driveapi.zillit.com/", parsed["PROD_DRIVE_BASE_URL"])
        assertEquals("calling-sfu-prod.zillit.com", parsed["PROD_MEDIASOUP_CALLING_URL"])
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val parsed = PropertiesParser.parse(
            """
            # a commented-out preprod override
            #QA_BASE_URL=https\://projectapi-preprod.zillit.com/

            ! bang comments too
            QA_BASE_URL=https\://projectapi-qa.zillit.com/
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals("https://projectapi-qa.zillit.com/", parsed["QA_BASE_URL"])
    }

    @Test
    fun `a colon may separate key from value`() {
        assertEquals("value", PropertiesParser.parse("KEY:value")["KEY"])
    }

    @Test
    fun `a line with no separator is skipped rather than throwing`() {
        // A stray line must not stop the app from starting.
        assertTrue(PropertiesParser.parse("just-some-text").isEmpty())
    }

    @Test
    fun `a malformed unicode escape degrades instead of throwing`() {
        // `\uZZZZ` is not valid hex. Dropping the backslash and keeping the
        // rest loses a character, but a config file is not worth refusing to
        // start over — and the alternative is an exception at launch.
        assertEquals("abuZZZZ", PropertiesParser.parse("""K=ab\uZZZZ""")["K"])
    }

    @Test
    fun `the real file shape parses into a valid production config`() {
        // End to end: escaped input through to a validated AppConfig.
        val raw = """
            PROD_BASE_URL=https\://projectapi.zillit.com/
            PROD_CHAT_BASE_URL=https\://cncapi.zillit.com
            PROD_UNITS_BASE_URL=https\://unitsapi.zillit.com/
            PROD_ACCOUNT_HUB_BASE_URL=https://accounthubapi.zillit.com/
            PROD_DRIVE_BASE_URL= https://driveapi.zillit.com/
            PROD_URL=https://web.zillit.com
            PROD_LIVEKIT_URL=wss://calls.zillit.com/livekit
        """.trimIndent()

        val result = ConfigParser.parse(Environment.Production, PropertiesParser.parse(raw))

        assertIs<ZillitResult.Success<AppConfig>>(result)
        val config = result.data
        assertEquals("https://projectapi.zillit.com", config.baseUrl(ZillitService.Core))
        assertEquals("https://projectapi.zillit.com/api/v2/", config.apiV2())
        assertEquals("https://cncapi.zillit.com", config.baseUrl(ZillitService.Chat))
        assertEquals("wss://calls.zillit.com/livekit", config.realtimeUrl(ZillitRealtimeEndpoint.LiveKit))
    }

    @Test
    fun `an escaped-colon file would fail validation without unescaping`() {
        // Guards the regression directly: this is what the old parser produced.
        val notUnescaped = mapOf("PROD_BASE_URL" to """https\://projectapi.zillit.com/""")

        val result = ConfigParser.parse(Environment.Production, notUnescaped)

        assertIs<ZillitResult.Failure>(result)
        assertTrue(
            result.error.technical.orEmpty().contains("Non-TLS"),
            "expected the TLS check to reject a still-escaped URL",
        )
    }

    @Test
    fun `secrets in the file are ignored rather than loaded`() {
        // ENCRYPTION_KEY / IV_ENCRYPTION_KEY appear in the Android config file.
        // They are not modelled as services, so they never reach AppConfig —
        // secrets belong in the OS keychain (plan §8.3).
        val raw = """
            PROD_BASE_URL=https\://projectapi.zillit.com/
            PROD_ENCRYPTION_KEY=some-secret-value
            PROD_IV_ENCRYPTION_KEY=another-secret
        """.trimIndent()

        val result = ConfigParser.parse(Environment.Production, PropertiesParser.parse(raw))

        assertIs<ZillitResult.Success<AppConfig>>(result)
        val leaked = result.data.services.values + result.data.realtime.values
        assertTrue(leaked.none { it.contains("secret") }, "a secret reached AppConfig: $leaked")
        assertNull(ZillitService.fromConfigKey("ENCRYPTION_KEY"))
    }
}
