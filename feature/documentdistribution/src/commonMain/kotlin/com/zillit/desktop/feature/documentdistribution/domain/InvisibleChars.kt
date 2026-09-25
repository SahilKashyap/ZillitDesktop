package com.zillit.desktop.feature.documentdistribution.domain

/**
 * ZL-21475: invisible characters pasted from Word, Google Docs or a PDF ride
 * into a subject or body, and receiving mail servers read them as a spam
 * signal — a send to a Yahoo.it address bounced on hidden characters in the
 * subject. The web strips them at the payload builders (`stripInvisibleChars`);
 * this is the same rule, applied where a send or a saved template leaves.
 *
 * Removed: U+00AD soft hyphen, U+200B–U+200F zero-width and direction marks,
 * U+2060–U+2064 word joiner and invisible operators, U+FEFF. U+200D (the
 * zero-width joiner) is kept where it joins two pictographs, because that is
 * what makes a family or a flag one emoji rather than several.
 *
 * Known consequence of the ticket's range, kept for parity: U+200C separates
 * words in Persian and Urdu, and U+200E/U+200F pin direction in Arabic and
 * Hebrew runs.
 */
fun stripInvisibleChars(text: String): String {
    if (text.none { it.code in INVISIBLE_RANGE_HINT }) return text
    val points = codePoints(text).filterNot { it in STRIPPED }
    val out = StringBuilder(text.length)
    points.forEachIndexed { i, cp ->
        val strayJoiner = cp == ZWJ &&
            !(points.getOrNull(i - 1)?.let(::joinable) == true && points.getOrNull(i + 1)?.let(::joinable) == true)
        if (!strayJoiner) out.appendCodePoint(cp)
    }
    return out.toString()
}

private fun codePoints(text: String): List<Int> {
    val points = ArrayList<Int>(text.length)
    var i = 0
    while (i < text.length) {
        val high = text[i]
        if (high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            points += (high.code - HIGH_BASE shl HALF_SHIFT) + (text[i + 1].code - LOW_BASE) + SUPPLEMENTARY_BASE
            i += 2
        } else {
            points += high.code
            i++
        }
    }
    return points
}

private fun StringBuilder.appendCodePoint(cp: Int) {
    if (cp < SUPPLEMENTARY_BASE) {
        append(cp.toChar())
    } else {
        val offset = cp - SUPPLEMENTARY_BASE
        append((HIGH_BASE + (offset shr HALF_SHIFT)).toChar())
        append((LOW_BASE + (offset and HALF_MASK)).toChar())
    }
}

/**
 * Something that can sit either side of a joiner inside one emoji: a
 * pictograph, the U+FE0F presentation selector, or a skin-tone modifier.
 * Common code has no `Extended_Pictographic` property, so the pictograph
 * blocks are named instead.
 */
private fun joinable(cp: Int): Boolean =
    cp == VS16 || PICTOGRAPH_RANGES.any { cp in it }

private const val ZWJ = 0x200D
private const val VS16 = 0xFE0F
private val STRIPPED: Set<Int> = buildSet {
    add(0x00AD)
    addAll(listOf(0x200B, 0x200C, 0x200E, 0x200F))
    addAll(0x2060..0x2064)
    add(0xFEFF)
}

/** Every stripped character and the joiner are BMP, so one char test finds a text with none. */
private val INVISIBLE_RANGE_HINT: Set<Int> = STRIPPED + ZWJ

private val PICTOGRAPH_RANGES = listOf(
    0x2600..0x27BF, // Misc symbols, dingbats (♀ ♂ ⚕ ✈ ❤)
    0x2B00..0x2BFF, // arrows and stars (⬛ ⭐)
    0x1F000..0x1FAFF, // emoji blocks, skin tones included
)

private const val SUPPLEMENTARY_BASE = 0x10000
private const val HIGH_BASE = 0xD800
private const val LOW_BASE = 0xDC00
private const val HALF_SHIFT = 10
private const val HALF_MASK = 0x3FF
