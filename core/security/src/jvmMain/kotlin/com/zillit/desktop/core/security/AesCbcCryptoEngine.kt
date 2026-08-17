package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Port of the Android client's `EncrytionDecryption` (AES/CBC + hex).
 *
 * Behaviour is matched deliberately, including its quirks, because the backend
 * expects exactly this wire format:
 *
 *  - **Encrypt** uses `AES/CBC/NoPadding` with padding applied by hand, because
 *    the padding byte pattern must match what the server strips.
 *  - **Decrypt** tries `PKCS5Padding` first and falls back to `NoPadding` plus a
 *    permissive unpad, because iOS and Android peers produce both shapes.
 *
 * Verified against a transcription of the original in `AesCbcCryptoEngineTest`.
 */
class AesCbcCryptoEngine(
    private val keyProvider: CryptoKeyProvider,
) : CryptoEngine {

    override fun encryptToHex(plaintext: String): ZillitResult<String> {
        val material = when (val result = keyProvider.keyMaterial()) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION_NO_PADDING).apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(material.keyBytes, ALGORITHM),
                    IvParameterSpec(material.ivBytes),
                )
            }
            Hex.encode(cipher.doFinal(padPkcs5(plaintext.toByteArray(Charsets.UTF_8))))
        }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { ZillitResult.Failure(ZillitError.Crypto("encrypt failed: ${it::class.simpleName}")) },
        )
    }

    override fun decryptFromHex(cipherHex: String): ZillitResult<String> {
        val material = when (val result = keyProvider.keyMaterial()) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        val cipherBytes = Hex.decode(cipherHex)
            ?: return ZillitResult.Failure(ZillitError.Crypto("payload is not valid hex"))

        val keySpec = SecretKeySpec(material.keyBytes, ALGORITHM)
        val ivSpec = IvParameterSpec(material.ivBytes)

        return runCatching { decryptPkcs5(cipherBytes, keySpec, ivSpec) }
            .recoverCatching { throwable ->
                // Peers on other platforms send NoPadding payloads; the Android
                // client treats these two exceptions as "try the other shape".
                if (throwable is BadPaddingException || throwable is IllegalBlockSizeException) {
                    decryptNoPadding(cipherBytes, keySpec, ivSpec)
                } else {
                    throw throwable
                }
            }
            .fold(
                onSuccess = { ZillitResult.Success(it) },
                onFailure = {
                    // The Android version returns the ciphertext unchanged here,
                    // which surfaces later as a confusing parse error. Fail loudly.
                    ZillitResult.Failure(ZillitError.Crypto("decrypt failed: ${it::class.simpleName}"))
                },
            )
    }

    override fun bodyHash(bodyJson: String, encryptedModuleData: String): ZillitResult<String> {
        val material = when (val result = keyProvider.keyMaterial()) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        // Assembled by string concatenation rather than by building a JsonObject
        // and re-encoding it. `bodyJson` must be embedded exactly as it will be
        // sent: re-parsing and re-serialising could reorder keys or normalise
        // number formatting, and the digest would then cover bytes the server
        // never sees.
        val payload = bodyJson.ifEmpty { EMPTY_PAYLOAD }
        val salt = material.ivBytes.decodeToString()
        val toHash = """{"payload":$payload,"moduledata":"$encryptedModuleData"}$salt"""

        return runCatching {
            Hex.encode(MessageDigest.getInstance(DIGEST).digest(toHash.toByteArray(Charsets.UTF_8)))
        }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { ZillitResult.Failure(ZillitError.Crypto("body hash failed: ${it::class.simpleName}")) },
        )
    }

    private fun decryptPkcs5(
        cipherText: ByteArray,
        keySpec: SecretKeySpec,
        ivSpec: IvParameterSpec,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION_PKCS5).apply {
            init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        }
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun decryptNoPadding(
        cipherText: ByteArray,
        keySpec: SecretKeySpec,
        ivSpec: IvParameterSpec,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION_NO_PADDING).apply {
            init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        }
        return String(stripPaddingLeniently(cipher.doFinal(cipherText)), Charsets.UTF_8)
    }

    internal companion object {
        const val ALGORITHM = "AES"
        const val TRANSFORMATION_NO_PADDING = "AES/CBC/NoPadding"
        const val TRANSFORMATION_PKCS5 = "AES/CBC/PKCS5Padding"
        const val BLOCK_SIZE = CryptoKeyMaterial.AES_BLOCK_BYTES
        const val BYTE_MASK = 0xFF
        const val DIGEST = "SHA-256"

        /** `payload` for a bodiless request: the empty JSON string, not `{}`. */
        const val EMPTY_PAYLOAD = "\"\""

        /**
         * Pads to a whole block, always adding at least one byte — so an input
         * that is already block-aligned gains a full block of padding. This is
         * standard PKCS#5/7 and matches the Android implementation.
         */
        fun padPkcs5(data: ByteArray): ByteArray {
            val padding = BLOCK_SIZE - data.size % BLOCK_SIZE
            return ByteArray(data.size + padding).also { padded ->
                data.copyInto(padded)
                padded.fill(padding.toByte(), data.size, padded.size)
            }
        }

        /**
         * Removes PKCS#7 padding only when the trailing bytes are internally
         * consistent; otherwise returns the input untouched.
         *
         * Lenient on purpose — the Android client relies on this to read
         * payloads whose padding it does not control.
         */
        fun stripPaddingLeniently(data: ByteArray): ByteArray {
            if (data.isEmpty()) return data
            val pad = data.last().toInt() and BYTE_MASK
            if (pad < 1 || pad > BLOCK_SIZE || pad > data.size) return data
            for (index in data.size - pad until data.size) {
                if ((data[index].toInt() and BYTE_MASK) != pad) return data
            }
            return data.copyOf(data.size - pad)
        }
    }
}
