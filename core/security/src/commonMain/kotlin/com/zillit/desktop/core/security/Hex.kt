package com.zillit.desktop.core.security

/**
 * Hex codec matching the Android client's wire format: lowercase, zero-padded,
 * no separator.
 */
internal object Hex {

    private const val HEX_DIGITS = "0123456789abcdef"
    private const val HEX_RADIX = 16
    private const val NIBBLE_BITS = 4
    private const val BYTE_MASK = 0xFF
    private const val DECIMAL_DIGITS = 10

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and BYTE_MASK
            out.append(HEX_DIGITS[value ushr NIBBLE_BITS])
            out.append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    /**
     * Returns null on malformed input rather than throwing — mirrors the
     * Android behaviour of tolerating whitespace and a `0x` prefix.
     */
    fun decode(hex: String): ByteArray? {
        val clean = hex.trim().filterNot { it.isWhitespace() }.removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty() || clean.length % 2 != 0) return null

        val out = ByteArray(clean.length / 2)
        for (index in clean.indices step 2) {
            val high = digit(clean[index])
            val low = digit(clean[index + 1])
            if (high < 0 || low < 0) return null
            out[index / 2] = ((high shl NIBBLE_BITS) or low).toByte()
        }
        return out
    }

    private fun digit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + DECIMAL_DIGITS
        in 'A'..'F' -> char - 'A' + DECIMAL_DIGITS
        else -> -1
    }.let { if (it >= HEX_RADIX) -1 else it }
}
