package com.zillit.desktop.core.security

import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Faithful transcription of the Android client's `EncrytionDecryption`
 * (`ZillitAndroidV20/.../utils/EncrytionDecryption.kt`), reduced to the parts
 * that touch bytes — `BuildConfig` reads become constructor parameters and the
 * logging calls are dropped.
 *
 * This exists purely as a test oracle. `AesCbcCryptoEngineTest` runs the port
 * and this side by side over random inputs, which catches a misreading of the
 * original in a way that testing against the AES spec alone would not.
 *
 * **Do not "improve" this file.** Its value is that it matches the original,
 * including the parts that are odd.
 */
internal class AndroidReferenceCrypto(
    private val encryptionKey: String,
    private val ivKey: String,
) {

    /** Original: `encryptPlainText` — note `takeLast(32)` / `take(16)`. */
    fun encryptPlainText(plaintext: String): String {
        val algo = "AES/CBC/NoPadding"
        require(encryptionKey.length >= 32 && ivKey.length >= 16)

        val key = encryptionKey.substring(encryptionKey.length - 32)
        val iv = ivKey.take(16)
        val keySpec = SecretKeySpec(key.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        val cipher = Cipher.getInstance(algo)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

        val inputBytes = plaintext.toByteArray(Charsets.UTF_8)
        val paddedInput = padPKCS5(inputBytes)
        val cipherText = cipher.doFinal(paddedInput)

        val hexString = StringBuilder()
        for (i in cipherText.indices) {
            val hex = Integer.toHexString(0xff and cipherText[i].toInt())
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }
        return hexString.toString()
    }

    private fun padPKCS5(data: ByteArray): ByteArray {
        val blockSize = 16
        val padding = blockSize - data.size % blockSize
        val paddedData = Arrays.copyOf(data, data.size + padding)
        Arrays.fill(paddedData, data.size, paddedData.size, padding.toByte())
        return paddedData
    }

    /** Original: `decryptPlainText` — note it uses the key/IV strings WHOLE. */
    fun decryptPlainText(cipherText: String): String {
        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        val ivBytes = ivKey.toByteArray(Charsets.UTF_8)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        val cipherBytes = hexStringToByteArray(cipherText)

        return try {
            cipherBytes?.let { decryptPKCS5(it, keySpec, ivSpec) } ?: cipherText
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            cipherText
        }
    }

    private fun decryptPKCS5(
        cipherText: ByteArray,
        keySpec: SecretKeySpec,
        ivSpec: IvParameterSpec,
    ): String = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    } catch (@Suppress("SwallowedException") e: BadPaddingException) {
        String(decryptNoPadding(cipherText, keySpec, ivSpec), Charsets.UTF_8)
    } catch (@Suppress("SwallowedException") e: IllegalBlockSizeException) {
        String(decryptNoPadding(cipherText, keySpec, ivSpec), Charsets.UTF_8)
    }

    private fun decryptNoPadding(
        cipherText: ByteArray,
        keySpec: SecretKeySpec,
        ivSpec: IvParameterSpec,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return removePKCS7PaddingSafely(cipher.doFinal(cipherText))
    }

    private fun removePKCS7PaddingSafely(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data.last().toInt() and 0xFF
        if (pad < 1 || pad > 16) return data
        for (i in data.size - pad until data.size) {
            if ((data[i].toInt() and 0xFF) != pad) return data
        }
        return data.copyOf(data.size - pad)
    }

    private fun hexStringToByteArray(hex: String): ByteArray? = try {
        val cleanHex = hex.trim().replace("\\s".toRegex(), "").removePrefix("0x")
        require(cleanHex.length % 2 == 0)
        val result = ByteArray(cleanHex.length / 2)
        for (i in cleanHex.indices step 2) {
            val hi = Character.digit(cleanHex[i], 16)
            val lo = Character.digit(cleanHex[i + 1], 16)
            require(hi >= 0 && lo >= 0)
            result[i / 2] = ((hi shl 4) or lo).toByte()
        }
        result
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        null
    }
}
