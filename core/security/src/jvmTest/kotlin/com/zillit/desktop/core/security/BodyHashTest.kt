package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The `bodyhash` header, pinned against the web client's algorithm.
 *
 * The expected digests below were produced by an **independent** implementation
 * of `generateBodyHash` (`multipleFunction.js:1834`) rather than by running this
 * code and recording what it printed — a self-generated fixture would pass
 * whatever this file happens to do, including the wrong thing.
 *
 * The algorithm:
 * ```
 * SHA-256( JSON.stringify({payload, moduledata}) + VITE_IV_ENCRYPTION_KEY )
 * ```
 * lowercase hex. Android's `generateSHA256WithSalt` agrees byte for byte.
 */
class BodyHashTest {

    // Canonical lengths (32 / 16). Test values, not production keys.
    private val iv = "abcdef9876543210"

    private val engine = AesCbcCryptoEngine(
        object : CryptoKeyProvider {
            override fun keyMaterial() = ZillitResult.Success(
                CryptoKeyMaterial.fromStrings("0123456789abcdef0123456789abcdef", iv),
            )
        },
    )

    @Test
    fun `matches the web client for a request with a body`() {
        // {"payload":{"code":"abc","file_name":"qrcode.png"},"moduledata":"deadbeef"} + iv
        val hash = engine.bodyHash(
            bodyJson = """{"code":"abc","file_name":"qrcode.png"}""",
            encryptedModuleData = "deadbeef",
        )

        assertEquals(
            ZillitResult.Success("02de975763ac50f3cceacb997f24e686d407250d566e3935d939f85ae97575ab"),
            hash,
        )
    }

    @Test
    fun `an empty body hashes as the empty string, not an empty object`() {
        // The web sends `payload: ''` for GET. `{}` would be a different digest
        // and every bodiless request would be rejected — a failure that would
        // look like an auth problem, not a serialisation one.
        val hash = engine.bodyHash(bodyJson = "", encryptedModuleData = "deadbeef")

        assertEquals(
            ZillitResult.Success("c69d575f864dabcf9e803c1233d0cb52c2005a67bd1dee1ce447db91e113377f"),
            hash,
        )
    }

    @Test
    fun `the digest is lowercase hex of a full SHA-256`() {
        val hash = engine.bodyHash("""{"a":1}""", "beef") as ZillitResult.Success

        assertEquals(64, hash.data.length)
        assertTrue(hash.data.matches(Regex("[0-9a-f]{64}")), "not lowercase hex: ${hash.data}")
    }

    @Test
    fun `changing either input changes the digest`() {
        // The header's purpose is to bind body and moduledata together; if
        // either could vary independently it would prove nothing.
        val base = engine.bodyHash("""{"a":1}""", "beef")

        assertNotEquals(base, engine.bodyHash("""{"a":2}""", "beef"))
        assertNotEquals(base, engine.bodyHash("""{"a":1}""", "beed"))
    }

    @Test
    fun `a missing key fails rather than hashing without salt`() {
        // An unsalted digest would be silently wrong: the request would go out
        // and come back 401, with nothing to say the key was never loaded.
        val noKeys = AesCbcCryptoEngine(
            object : CryptoKeyProvider {
                override fun keyMaterial() = ZillitResult.Failure(
                    com.zillit.desktop.core.common.ZillitError.Crypto("no key"),
                )
            },
        )

        assertTrue(noKeys.bodyHash("{}", "beef") is ZillitResult.Failure)
    }
}
