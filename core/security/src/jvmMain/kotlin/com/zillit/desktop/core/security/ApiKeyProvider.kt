package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.runBlocking

/**
 * Supplies the header AES key/IV from the OS keychain.
 *
 * ## Why these are not in `zillit.properties`
 *
 * The Android client compiles them into `BuildConfig`, so they ship inside
 * every APK. A desktop JAR is *easier* to decompile than a minified APK, so
 * repeating that would be strictly worse — and putting them in a config file
 * beside the app is no better. They live in the OS keychain instead, entered
 * once by the user (see `ApiKeySetup`), and the app never writes them anywhere
 * else.
 *
 * This does not make the scheme secure — the key is shared across every install,
 * so anyone holding it can forge headers. It removes the *casual* exposure while
 * per-device request signing is built (plan §8.3).
 */
class KeychainCryptoKeyProvider(
    private val secureStore: SecureStore,
) : CryptoKeyProvider {

    /**
     * Cached after the first read.
     *
     * A keychain lookup per HTTP request would be both slow and, on macOS, a
     * plausible way to trigger repeated authorisation prompts. Cleared by
     * [invalidate] on sign-out.
     */
    private var cached: CachedKeys? = null

    override fun keyMaterial(): ZillitResult<CryptoKeyMaterial> {
        cached?.let { return ZillitResult.Success(it.toMaterial()) }

        // The header provider is called from a suspend context, but
        // `CryptoKeyProvider` is synchronous by design — it sits on the hot path
        // of every request, and making it suspend would push coroutine plumbing
        // into the crypto engine for a value that is cached after first use.
        @Suppress("ForbiddenMethodCall")
        val loaded = runBlocking { load() }

        return when (loaded) {
            is ZillitResult.Failure -> loaded
            is ZillitResult.Success -> {
                cached = loaded.data
                ZillitResult.Success(loaded.data.toMaterial())
            }
        }
    }

    private suspend fun load(): ZillitResult<CachedKeys> {
        val keyText = readSecret(SecureKey.ApiEncryptionKey) ?: return missing()
        val ivText = readSecret(SecureKey.ApiIvKey) ?: return missing()

        // Encrypt derives from the last 32 chars of the key and the first 16 of
        // the IV; decrypt uses both whole. They only agree at these exact
        // lengths — see CryptoKeyMaterial.requireCanonicalLengths.
        if (!CryptoKeyMaterial.requireCanonicalLengths(keyText, ivText)) {
            return ZillitResult.Failure(
                ZillitError.Crypto(
                    "API key must be ${CryptoKeyMaterial.AES_256_KEY_BYTES} characters and IV " +
                        "${CryptoKeyMaterial.AES_BLOCK_BYTES}; got ${keyText.length} and ${ivText.length}",
                ),
            )
        }

        return ZillitResult.Success(CachedKeys(keyText, ivText))
    }

    /** Reads one secret, zeroing the transfer buffer. Null when absent or unreadable. */
    private suspend fun readSecret(key: SecureKey): String? =
        secureStore.get(key).getOrNull()?.let { bytes ->
            bytes.decodeToString().also { bytes.fill(0) }
        }

    /** Drops the cache — called on sign-out, alongside clearing the keychain. */
    fun invalidate() {
        cached = null
    }

    private fun missing() = ZillitResult.Failure(
        ZillitError.Crypto("API keys are not set up on this machine"),
    )

    private class CachedKeys(val key: String, val iv: String) {
        // A fresh instance per call: CryptoKeyMaterial is zeroed by its
        // consumer, so handing out a shared one would blank it after first use.
        fun toMaterial() = CryptoKeyMaterial.fromStrings(key, iv)
    }
}

/**
 * Stores the API key and IV.
 *
 * Called from a setup screen where the **user** types or pastes their own
 * values. Nothing else in the app writes these, and they are never logged,
 * echoed, or written to disk outside the keychain.
 */
class ApiKeySetup(private val secureStore: SecureStore) {

    suspend fun store(key: String, iv: String): ZillitResult<Unit> {
        val trimmedKey = key.trim()
        val trimmedIv = iv.trim()

        // Validated before storing so a typo surfaces here rather than as a
        // baffling 401 on every request later.
        if (!CryptoKeyMaterial.requireCanonicalLengths(trimmedKey, trimmedIv)) {
            return ZillitResult.Failure(
                ZillitError.Validation(
                    "The key must be exactly ${CryptoKeyMaterial.AES_256_KEY_BYTES} characters and " +
                        "the IV exactly ${CryptoKeyMaterial.AES_BLOCK_BYTES}.",
                ),
            )
        }

        return when (val stored = secureStore.put(SecureKey.ApiEncryptionKey, trimmedKey.encodeToByteArray())) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> secureStore.put(SecureKey.ApiIvKey, trimmedIv.encodeToByteArray())
        }
    }

    /** Whether this machine already has usable keys. */
    suspend fun isConfigured(): Boolean {
        val key = secureStore.get(SecureKey.ApiEncryptionKey).getOrNull()
        val iv = secureStore.get(SecureKey.ApiIvKey).getOrNull()
        val configured = key != null && iv != null
        key?.fill(0)
        iv?.fill(0)
        return configured
    }
}
