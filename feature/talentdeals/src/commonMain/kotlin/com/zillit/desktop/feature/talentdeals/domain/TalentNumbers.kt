package com.zillit.desktop.feature.talentdeals.domain

import kotlin.math.abs
import kotlin.math.floor

/**
 * The figures the talent contract speaks in — the web's `schedule.js` `num` /
 * `r2` and Account Hub's `groupAmount`.
 *
 * Money here is whole currency units as a decimal (`500000` is half a million
 * pounds), never cents. Every comparison rounds to pennies first, or the
 * totals drift.
 */
object TalentNumbers {

    /** A typed figure that may carry thousands separators; anything unreadable is 0. */
    fun num(value: String?): Double {
        val text = value.orEmpty().replace(",", "").trim()
        if (text.isEmpty()) return 0.0
        val parsed = text.toDoubleOrNull() ?: return 0.0
        return if (parsed.isNaN() || parsed.isInfinite()) 0.0 else parsed
    }

    /** `Math.round(value * 100) / 100` — JavaScript rounds a half UP, so this does too. */
    fun r2(value: Double): Double = floor(value * HUNDRED + HALF) / HUNDRED

    /**
     * A figure as an input holds it: no trailing `.0`, no exponent. The inverse
     * of [num] for anything a person types.
     */
    fun plain(value: Double): String {
        if (value == 0.0 || value.isNaN() || value.isInfinite()) return ""
        val whole = value.toLong().toDouble()
        if (whole == value && abs(value) < WHOLE_LIMIT) return value.toLong().toString()
        return value.toString()
    }

    /** en-GB grouping to two places: `1,234,567.89`. A non-finite figure is 0. */
    fun groupAmount(value: Double): String {
        val v = if (value.isNaN() || value.isInfinite()) 0.0 else value
        val negative = v < 0
        val cents = floor(abs(v) * HUNDRED + HALF).toLong()
        val whole = (cents / HUNDRED_L).toString()
        val fraction = (cents % HUNDRED_L).toString().padStart(2, '0')
        val grouped = whole.reversed().chunked(GROUP).joinToString(",").reversed()
        val sign = if (negative && cents != 0L) "-" else ""
        return "$sign$grouped.$fraction"
    }

    /**
     * Symbol plus grouped figures. The symbol is wrapped in a directional
     * isolate (U+2068 / U+2069), as the web does, so a right-to-left symbol
     * does not swap sides with the digits.
     */
    fun formatMoney(amount: Double, symbol: String?): String =
        "⁨${symbol.orEmpty()}⁩${groupAmount(amount)}"

    /** A JSON number the way JavaScript would have sent it: whole values without `.0`. */
    fun jsonNumber(value: Double): kotlinx.serialization.json.JsonPrimitive {
        val whole = value.toLong().toDouble()
        return if (whole == value && abs(value) < WHOLE_LIMIT) {
            kotlinx.serialization.json.JsonPrimitive(value.toLong())
        } else {
            kotlinx.serialization.json.JsonPrimitive(value)
        }
    }

    private const val HUNDRED = 100.0
    private const val HUNDRED_L = 100L
    private const val HALF = 0.5
    private const val GROUP = 3
    private const val WHOLE_LIMIT = 1e15
}
