package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitResult

/**
 * AES/CBC payload codec, byte-compatible with the Android client.
 *
 * The scheme is a shared-key transport obfuscation, not a security control:
 * the key ships with the client, so anyone who can read the binary can decrypt
 * anything it sends (plan §8.3). It is implemented faithfully because the
 * backend requires it, and it is scheduled for replacement by per-device
 * request signing.
 *
 * Errors are returned, never thrown — the Android version swallows exceptions
 * and returns the input unchanged, which turns a crypto failure into a
 * confusing downstream parse error instead of a clear one.
 */
interface CryptoEngine {

    /** Encrypts UTF-8 [plaintext], returning lowercase hex. */
    fun encryptToHex(plaintext: String): ZillitResult<String>

    /** Decrypts lowercase or uppercase [cipherHex] back to a UTF-8 string. */
    fun decryptFromHex(cipherHex: String): ZillitResult<String>

    /**
     * The `bodyhash` header: a salted digest binding the request body to its
     * `moduledata` header.
     *
     * ```
     * SHA-256( {"payload":<body>,"moduledata":"<hex>"} + <IV string> )  → lowercase hex
     * ```
     *
     * [bodyJson] is the serialised body verbatim, or empty for a bodiless
     * request — in which case `payload` is the empty **string** `""`, not an
     * empty object. The web client (`generateBodyHash`) and Android
     * (`generateSHA256WithSalt`) agree on this byte for byte, and the server
     * rejects anything else.
     *
     * The IV doubles as the salt here. That is the backend's design, not a
     * choice made in this port: reusing an IV as a MAC key is not a construction
     * anyone would pick, and it is replaced along with the rest of the scheme in
     * plan §8.3.
     */
    fun bodyHash(bodyJson: String, encryptedModuleData: String): ZillitResult<String>
}

/**
 * Supplies key material.
 *
 * Separate from [CryptoEngine] because the source changes per plan phase: a
 * launch-time config today, a runtime fetch after device attestation later
 * (plan §8.3). Nothing else has to change when it does.
 */
interface CryptoKeyProvider {
    fun keyMaterial(): ZillitResult<CryptoKeyMaterial>
}

/**
 * Key + IV bytes.
 *
 * Held as [ByteArray] rather than [String] on purpose: JVM string interning
 * leaves key material recoverable in heap dumps long after use (plan §8.4).
 * Call [clear] when done.
 */
class CryptoKeyMaterial(
    private val key: ByteArray,
    private val iv: ByteArray,
) {
    val keyBytes: ByteArray get() = key
    val ivBytes: ByteArray get() = iv

    fun clear() {
        key.fill(0)
        iv.fill(0)
    }

    companion object {
        const val AES_BLOCK_BYTES = 16
        const val AES_256_KEY_BYTES = 32

        /**
         * Derives key/IV the way the Android encrypt path does: the **last** 32
         * characters of the key string and the **first** 16 of the IV string.
         *
         * Note the Android decrypt path instead uses both strings whole
         * (`EncrytionDecryption.kt:139-142`). The two agree only when the
         * configured key is exactly 32 chars and the IV exactly 16 — which is
         * why [requireCanonicalLengths] exists.
         */
        fun fromStrings(key: String, iv: String): CryptoKeyMaterial =
            CryptoKeyMaterial(
                key = key.takeLast(AES_256_KEY_BYTES).encodeToByteArray(),
                iv = iv.take(AES_BLOCK_BYTES).encodeToByteArray(),
            )

        /**
         * True when encrypt and decrypt derive identical material. If this is
         * false for the production keys, the Android client can encrypt
         * payloads it cannot itself decrypt.
         */
        fun requireCanonicalLengths(key: String, iv: String): Boolean =
            key.length == AES_256_KEY_BYTES && iv.length == AES_BLOCK_BYTES
    }
}
