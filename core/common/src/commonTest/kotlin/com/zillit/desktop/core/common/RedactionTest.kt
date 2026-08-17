package com.zillit.desktop.core.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What must never reach a log file.
 *
 * Desktop has no logcat, so `~/.zillit/logs/zillit.log` is a file that sits on
 * the user's machine and gets emailed to support. Everything written there goes
 * through [ZillitLog.redact] first (plan §8.4).
 */
class RedactionTest {

    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun `the header key is masked despite its environment prefix`() {
        // `_` is a word character, so a `\bencryption_key\b` pattern never
        // matches inside `PROD_ENCRYPTION_KEY`. This is the case the config
        // file introduced, and the one a plain word-boundary rule misses.
        listOf(
            "PROD_ENCRYPTION_KEY=$secret",
            "QA_IV_ENCRYPTION_KEY=$secret",
            "STG_ENCRYPTION_KEY = $secret",
            "prod_encryption_key:$secret",
        ).forEach { line ->
            assertFalse(ZillitLog.redact(line).contains(secret), "not masked: $line")
        }
    }

    @Test
    fun `the property name survives so the line is still diagnosable`() {
        // Masking the whole line would make a config warning useless.
        val redacted = ZillitLog.redact("PROD_ENCRYPTION_KEY=$secret")

        assertTrue(redacted.contains("PROD_ENCRYPTION_KEY"), "the key name should remain: $redacted")
    }

    @Test
    fun `tokens, passwords and bearer headers are masked`() {
        listOf(
            "authorization=Bearer abc.def.ghi",
            "Authorization: Bearer abc.def.ghi",
            """{"token":"abc123"}""",
            "password=hunter2",
            "api_key=abc123",
            "refresh_token: xyz789",
        ).forEach { line ->
            val redacted = ZillitLog.redact(line)
            listOf("abc.def.ghi", "abc123", "hunter2", "xyz789")
                .filter { line.contains(it) }
                .forEach { assertFalse(redacted.contains(it), "not masked: $line -> $redacted") }
        }
    }

    @Test
    fun `credential codes are masked, descriptive ones are not`() {
        // The QR `code` is a device-linking credential and `confirm_code` is a
        // single-use registration token. `project_code` is what a coordinator
        // reads out over the phone — masking it would gut the logs for the
        // debugging body logging exists to serve.
        val masked = ZillitLog.redact("""{"code":"abc123ZILLIT","confirm_code":"XY99"}""")
        assertFalse(masked.contains("abc123ZILLIT"), "the QR credential leaked: $masked")
        assertFalse(masked.contains("XY99"), "the confirm code leaked: $masked")

        val kept = ZillitLog.redact(
            """{"project_code":"DUNE-3","country_code":"44","language_code":"en"}""",
        )
        assertTrue(kept.contains("DUNE-3"), "project_code should stay readable: $kept")
        assertTrue(kept.contains("44"), "country_code should stay readable: $kept")
        assertTrue(kept.contains("en"), "language_code should stay readable: $kept")
    }

    @Test
    fun `encrypted header blobs are masked`() {
        // `moduledata` is a long hex run. It is not a secret in itself, but it
        // decrypts to the device id under a key that ships with the app.
        val blob = "a".repeat(128)

        assertFalse(ZillitLog.redact("moduledata $blob").contains(blob))
    }

    @Test
    fun `ordinary lines pass through untouched`() {
        // Over-redaction makes logs useless, which is its own failure mode.
        val line = "Loaded configuration from /Users/x/.zillit/zillit.properties (qa)"

        assertTrue(ZillitLog.redact(line) == line, "a harmless line was mangled")
    }

    @Test
    fun `a short hex string is not mistaken for a blob`() {
        val line = "colour #ff6d00 applied"

        assertTrue(ZillitLog.redact(line) == line)
    }
}
