package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the header AES key from `zillit.properties`.
 *
 * The key names match the Android app's `local.properties`
 * (`<PREFIX>_ENCRYPTION_KEY`, `<PREFIX>_IV_ENCRYPTION_KEY`) so one file serves
 * both clients — which means a copied Android config has to work unchanged.
 */
class HeaderKeyConfigTest {

    // Correct lengths, obviously not real values.
    private val key = "0123456789abcdef0123456789abcdef"
    private val iv = "abcdef9876543210"

    private fun props(vararg extra: Pair<String, String>) =
        mapOf("PROD_BASE_URL" to "https://api.example.com") + extra

    private fun parse(vararg extra: Pair<String, String>) =
        ConfigParser.parse(Environment.Production, props(*extra))

    @Test
    fun `the key is read under the Android property names`() {
        val result = parse(
            "PROD_ENCRYPTION_KEY" to key,
            "PROD_IV_ENCRYPTION_KEY" to iv,
        )

        assertTrue(result is ZillitResult.Success)
        assertEquals(HeaderKeyMaterial(key, iv), result.data.headerKey)
    }

    @Test
    fun `a config without a key is still valid`() {
        // The keychain path has to keep working: a deployment that omits the
        // line is configured, not broken.
        val result = parse()

        assertTrue(result is ZillitResult.Success)
        assertNull(result.data.headerKey)
    }

    @Test
    fun `the key is read from the active environment only`() {
        // A QA key must not be picked up by a production launch. This is the
        // failure that would be least obvious from the symptoms.
        val result = ConfigParser.parse(
            Environment.Production,
            mapOf(
                "PROD_BASE_URL" to "https://api.example.com",
                "QA_ENCRYPTION_KEY" to key,
                "QA_IV_ENCRYPTION_KEY" to iv,
            ),
        )

        assertTrue(result is ZillitResult.Success)
        assertNull(result.data.headerKey, "a QA key leaked into a production launch")
    }

    @Test
    fun `a wrong-length key is dropped rather than used`() {
        // Encrypt takes the last 32 chars of the key and the first 16 of the IV;
        // decrypt uses both whole. At any other length the two disagree and the
        // client encrypts payloads the server cannot read — a 401 on every call
        // with nothing pointing at the config file.
        val result = parse(
            "PROD_ENCRYPTION_KEY" to "too-short",
            "PROD_IV_ENCRYPTION_KEY" to iv,
        )

        assertTrue(result is ZillitResult.Success)
        assertNull(result.data.headerKey)
    }

    @Test
    fun `a half-configured pair is dropped`() {
        val keyOnly = parse("PROD_ENCRYPTION_KEY" to key)
        val ivOnly = parse("PROD_IV_ENCRYPTION_KEY" to iv)

        assertNull((keyOnly as ZillitResult.Success).data.headerKey)
        assertNull((ivOnly as ZillitResult.Success).data.headerKey)
    }

    @Test
    fun `a malformed key is reported, an absent one is not`() {
        // The caller logs a warning for the first and stays quiet for the
        // second; conflating them makes a typo indistinguishable from a choice.
        assertTrue(
            ConfigParser.headerKeyIsMalformed(
                props("PROD_ENCRYPTION_KEY" to "short", "PROD_IV_ENCRYPTION_KEY" to iv),
                Environment.Production,
            ),
        )
        assertFalse(ConfigParser.headerKeyIsMalformed(props(), Environment.Production))
        assertFalse(
            ConfigParser.headerKeyIsMalformed(
                props("PROD_ENCRYPTION_KEY" to key, "PROD_IV_ENCRYPTION_KEY" to iv),
                Environment.Production,
            ),
        )
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        // `PROD_ENCRYPTION_KEY = value` is what a hand-edited file looks like,
        // and a trailing space would otherwise push it off the 32-char check.
        val result = parse(
            "PROD_ENCRYPTION_KEY" to "  $key  ",
            "PROD_IV_ENCRYPTION_KEY" to "  $iv  ",
        )

        assertEquals(HeaderKeyMaterial(key, iv), (result as ZillitResult.Success).data.headerKey)
    }

    @Test
    fun `neither the config nor the key material prints the key`() {
        // An AppConfig reaches log lines and crash reports. The generated
        // data-class toString would print the key in full.
        val config = (parse("PROD_ENCRYPTION_KEY" to key, "PROD_IV_ENCRYPTION_KEY" to iv)
            as ZillitResult.Success).data

        assertFalse(config.toString().contains(key), "the key leaked via AppConfig.toString()")
        assertFalse(config.toString().contains(iv), "the IV leaked via AppConfig.toString()")
        assertFalse(config.headerKey.toString().contains(key), "the key leaked via HeaderKeyMaterial")
    }
}
