package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitResult

/**
 * Supplies the header key from configuration, falling back to another provider.
 *
 * The Android client compiles `ENCRYPTION_KEY` / `IV_KEY` into `BuildConfig`
 * from `local.properties`. The desktop equivalent is a `zillit.properties` line,
 * read at startup — same values, same key names, one file for both clients.
 *
 * ## Why there is still a fallback
 *
 * A deployment that ships without the key in its config file keeps working: the
 * user is asked for it once and it goes to the OS keychain. Removing that path
 * would turn a missing config line into a dead application, and the keychain is
 * the better place for the value on a machine where an admin can put it there.
 *
 * ## What this does not do
 *
 * It does not make the key secret. The key is identical across every install and
 * sits in a file beside a decompilable JAR; anyone who has it can forge
 * `moduledata` and `bodyhash` for any device. This is transport obfuscation the
 * backend requires, and it is replaced by per-device request signing (plan §8.3).
 * The one thing worth preserving is that it never reaches a log — see
 * [HeaderKeyMaterial.toString] and `ZillitLog.redact`.
 */
class ConfiguredCryptoKeyProvider(
    private val key: String,
    private val iv: String,
) : CryptoKeyProvider {

    override fun keyMaterial(): ZillitResult<CryptoKeyMaterial> =
        // A fresh instance per call: CryptoKeyMaterial is zeroed by its consumer,
        // so a shared one would be blanked after first use. The same trap the
        // keychain provider documents.
        ZillitResult.Success(CryptoKeyMaterial.fromStrings(key, iv))
}

/**
 * Tries [primary], then [fallback].
 *
 * Composed rather than branched inside one provider so neither implementation
 * knows the other exists, and so the order is decided once, where the graph is
 * wired.
 */
class FallbackCryptoKeyProvider(
    private val primary: CryptoKeyProvider,
    private val fallback: CryptoKeyProvider,
) : CryptoKeyProvider {

    override fun keyMaterial(): ZillitResult<CryptoKeyMaterial> =
        when (val result = primary.keyMaterial()) {
            is ZillitResult.Success -> result
            is ZillitResult.Failure -> fallback.keyMaterial()
        }
}
