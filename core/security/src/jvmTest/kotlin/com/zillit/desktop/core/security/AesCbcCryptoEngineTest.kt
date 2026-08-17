package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Differential tests: the port must produce byte-identical output to the
 * Android original for every input, or every request 401s (plan §10, risk 2).
 *
 * Test keys only — the production key and IV are not in this repository and
 * never will be (plan §8.3).
 */
class AesCbcCryptoEngineTest {

    // Canonical lengths: 32-char key, 16-char IV. With these the Android
    // encrypt path (takeLast(32)/take(16)) and decrypt path (whole strings)
    // agree — see CryptoKeyMaterial.requireCanonicalLengths.
    private val key = "0123456789abcdef0123456789abcdef"
    private val iv = "abcdef9876543210"

    private val engine = AesCbcCryptoEngine(FixedKeyProvider(key, iv))
    private val reference = AndroidReferenceCrypto(key, iv)

    @Test
    fun `encrypt matches the Android implementation for fixed inputs`() {
        val inputs = listOf(
            "",
            "a",
            "hello world",
            """{"device_id":"abc-123","module":"CHAT"}""",
            "0123456789abcdef",                       // exactly one block
            "0123456789abcdef0",                      // one block + 1
            "unicode: café — naïve — 日本語 — 🎬",
        )

        inputs.forEach { input ->
            assertEquals(
                reference.encryptPlainText(input),
                engine.encryptToHex(input).expectSuccess(),
                "ciphertext diverged for input: $input",
            )
        }
    }

    @Test
    fun `encrypt matches the Android implementation for random inputs`() {
        val random = Random(seed = 20260802)
        repeat(500) {
            val input = randomString(random, random.nextInt(0, 400))
            assertEquals(
                reference.encryptPlainText(input),
                engine.encryptToHex(input).expectSuccess(),
                "ciphertext diverged for random input of length ${input.length}",
            )
        }
    }

    @Test
    fun `decrypt matches the Android implementation`() {
        val random = Random(seed = 4242)
        repeat(200) {
            val input = randomString(random, random.nextInt(1, 200))
            val cipherHex = reference.encryptPlainText(input)
            assertEquals(
                reference.decryptPlainText(cipherHex),
                engine.decryptFromHex(cipherHex).expectSuccess(),
                "plaintext diverged for round-trip of length ${input.length}",
            )
        }
    }

    @Test
    fun `round trip preserves the original string`() {
        val input = """{"user":"sahil","project":"zillit","nested":{"a":[1,2,3]}}"""
        val cipherHex = engine.encryptToHex(input).expectSuccess()
        assertEquals(input, engine.decryptFromHex(cipherHex).expectSuccess())
    }

    @Test
    fun `ciphertext is lowercase hex of block-aligned length`() {
        val cipherHex = engine.encryptToHex("hello").expectSuccess()
        assertTrue(cipherHex.matches(Regex("[0-9a-f]+")), "expected lowercase hex, got $cipherHex")
        assertEquals(0, cipherHex.length % 32, "ciphertext must be a whole number of 16-byte blocks")
    }

    @Test
    fun `block-aligned input gains a full block of padding`() {
        // PKCS#7 always adds padding, so 16 bytes in produces 32 bytes out.
        val cipherHex = engine.encryptToHex("0123456789abcdef").expectSuccess()
        assertEquals(64, cipherHex.length, "16-byte input must encrypt to two blocks")
    }

    @Test
    fun `malformed hex fails instead of returning the input`() {
        // The Android version returns the ciphertext unchanged on failure,
        // which surfaces downstream as a confusing parse error.
        val result = engine.decryptFromHex("not-hex-at-all")
        assertTrue(result is ZillitResult.Failure, "expected failure for malformed hex")
    }

    @Test
    fun `odd length hex fails`() {
        assertTrue(engine.decryptFromHex("abc") is ZillitResult.Failure)
    }

    @Test
    fun `non-canonical key lengths make encrypt and decrypt disagree`() {
        // Documents a real hazard in the Android implementation: encrypt uses
        // the last 32 chars of the key, decrypt uses the whole string. With a
        // longer key the client encrypts payloads it cannot itself decrypt.
        val longKey = "PREFIX-$key"
        assertTrue(CryptoKeyMaterial.requireCanonicalLengths(key, iv))
        assertTrue(!CryptoKeyMaterial.requireCanonicalLengths(longKey, iv))

        val derived = CryptoKeyMaterial.fromStrings(longKey, iv)
        assertNotEquals(
            longKey.encodeToByteArray().toList(),
            derived.keyBytes.toList(),
            "fromStrings must reproduce the encrypt-path derivation, not the decrypt path",
        )
    }

    private fun randomString(random: Random, length: Int): String =
        buildString(length) {
            repeat(length) {
                // Mix ASCII and multi-byte characters so UTF-8 length differs
                // from character count.
                append(if (random.nextInt(10) == 0) ALPHABET_WIDE.random(random) else ALPHABET.random(random))
            }
        }

    private fun <T> ZillitResult<T>.expectSuccess(): T = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> error("expected success but got ${error.technical ?: error.userMessage}")
    }

    private class FixedKeyProvider(private val key: String, private val iv: String) : CryptoKeyProvider {
        override fun keyMaterial(): ZillitResult<CryptoKeyMaterial> =
            ZillitResult.Success(CryptoKeyMaterial.fromStrings(key, iv))
    }

    private companion object {
        val ALPHABET = ('a'..'z') + ('A'..'Z') + ('0'..'9') + " {}\":,[]-_/\\".toList()
        val ALPHABET_WIDE = "éàüñ日本語中文🎬🎥→—".toList()
    }
}
