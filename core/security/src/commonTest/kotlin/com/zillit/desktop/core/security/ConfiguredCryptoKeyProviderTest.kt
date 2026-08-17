package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfiguredCryptoKeyProviderTest {

    private val key = "0123456789abcdef0123456789abcdef"
    private val iv = "abcdef9876543210"

    @Test
    fun `it supplies the configured key`() {
        val provider = ConfiguredCryptoKeyProvider(key, iv)

        val material = provider.keyMaterial()

        assertTrue(material is ZillitResult.Success)
        assertContentEquals(key.encodeToByteArray(), material.data.keyBytes)
        assertContentEquals(iv.encodeToByteArray(), material.data.ivBytes)
    }

    @Test
    fun `each call gets its own material`() {
        // CryptoKeyMaterial is zeroed by whoever consumes it. Handing out a
        // shared instance means the second request encrypts with 32 zero bytes
        // — the exact trap the database key provider hit, and it fails as
        // SQLITE_NOTADB rather than as anything mentioning keys.
        val provider = ConfiguredCryptoKeyProvider(key, iv)

        val first = (provider.keyMaterial() as ZillitResult.Success).data
        first.clear()
        val second = (provider.keyMaterial() as ZillitResult.Success).data

        assertContentEquals(key.encodeToByteArray(), second.keyBytes, "the second caller got a zeroed key")
    }

    @Test
    fun `the fallback is used only when the primary has nothing`() {
        val configured = ConfiguredCryptoKeyProvider(key, iv)
        val keychain = ConfiguredCryptoKeyProvider("f".repeat(32), "0".repeat(16))

        val material = FallbackCryptoKeyProvider(configured, keychain).keyMaterial()

        assertContentEquals(
            key.encodeToByteArray(),
            (material as ZillitResult.Success).data.keyBytes,
            "config should win over the keychain",
        )
    }

    @Test
    fun `a failing primary falls through`() {
        val empty = failing("no key")

        val material = FallbackCryptoKeyProvider(empty, ConfiguredCryptoKeyProvider(key, iv))
            .keyMaterial()

        assertContentEquals(key.encodeToByteArray(), (material as ZillitResult.Success).data.keyBytes)
    }

    @Test
    fun `both empty surfaces the fallback's error`() {
        // The keychain's message names the user-actionable problem ("keys are
        // not set up on this machine"); the config provider's would not.
        val primary = failing("primary")
        val fallback = failing("fallback")

        val material = FallbackCryptoKeyProvider(primary, fallback).keyMaterial()

        assertTrue(material is ZillitResult.Failure)
        assertEquals("fallback", material.error.technical)
    }

    private fun failing(message: String) = object : CryptoKeyProvider {
        override fun keyMaterial() = ZillitResult.Failure(ZillitError.Crypto(message))
    }
}
