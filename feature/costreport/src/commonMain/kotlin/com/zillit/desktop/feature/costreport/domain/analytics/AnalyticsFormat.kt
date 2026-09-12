package com.zillit.desktop.feature.costreport.domain.analytics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * The web's analytics number vocabulary (`money`, `intFmt`, `pctFmt`,
 * `fmtVal`, `pctOf`, `deltaStr`), spelled exactly as a browser's en-GB
 * `Intl.NumberFormat` spells it — `£1.23m`, `US$54.2k`, `CHF 12k` — so a
 * figure reads the same on both clients.
 */
object AnalyticsFormat {

    const val DASH = "—"

    /**
     * Compact currency: one decimal under a million, two from a million up,
     * trailing zeros dropped. A value that rounds up into the next tier is
     * written in it (`£999,950` is `£1m`). A code that is not three letters
     * falls back to the code and a rounded whole number, as the web's catch does.
     */
    fun money(value: Double?, currency: String?): String {
        if (value == null || value.isNaN()) return DASH
        val code = currency?.takeIf { it.isNotEmpty() } ?: DEFAULT_CURRENCY
        if (!ISO_CODE.matches(code)) return "$code ${grouped(jsRound(value).toLong().toString())}"
        val prefix = SYMBOLS[code.uppercase()] ?: (code.uppercase() + NBSP)
        val sign = if (value < 0 || (value == 0.0 && 1.0 / value < 0)) "-" else ""
        if (value.isInfinite()) return "$sign$prefix∞"
        return sign + prefix + compact(abs(value))
    }

    /** What [money] writes before the number: `£`, `US$`, or a code and a no-break space. */
    fun prefix(currency: String?): String {
        val code = currency?.takeIf { ISO_CODE.matches(it) }?.uppercase() ?: DEFAULT_CURRENCY
        return SYMBOLS[code] ?: (code + NBSP)
    }

    /** `toLocaleString('en-GB')`: grouped, up to three decimals. */
    fun int(value: Double?): String {
        if (value == null || value.isNaN()) return DASH
        val sign = if (value < 0) "-" else ""
        val digits = decimal(abs(value), INT_FRACTION_DIGITS)
        return sign + grouped(digits.substringBefore('.')) + digits.substringAfter('.', "").let {
            if (it.isEmpty()) "" else ".$it"
        }
    }

    /** One decimal, rounded the way `Math.round` rounds, and a percent sign. */
    fun pct(value: Double?): String {
        if (value == null || value.isNaN()) return DASH
        return jsNumber(jsRound(value * PCT_SCALE) / PCT_SCALE) + "%"
    }

    /** A value in its `fmt`: `int`, `pct`, `x`, `ratio`, `text` or — by default — `money`. */
    fun value(raw: String?, fmt: String?, currency: String?, ratioOf: Double? = null): String {
        val number = raw?.let(::toNumber)
        return when (fmt) {
            "int" -> int(number)
            "pct" -> pct(number)
            "x" -> if (raw == null) DASH else "${raw}×"
            "ratio" -> "${int(number)}/${int(ratioOf)}"
            "text" -> raw.orEmpty()
            else -> money(number, currency)
        }
    }

    fun value(number: Double?, fmt: String?, currency: String?): String =
        value(number?.let(::jsNumber), fmt, currency)

    /** A part's share of a whole, rounded to a whole percent; blank when there is no whole. */
    fun share(value: Double, total: Double): String =
        if (total == 0.0 || total.isNaN()) "" else "${jsRound(value / total * PERCENT).toLong()}%"

    /**
     * A signed delta for a delta chip: `+12.5%` by default, or with
     * `deltaFmt = abs` an amount — money only when the figure itself is money.
     * The minus is U+2212, which is what the chip reads as "down".
     */
    fun delta(delta: Double?, deltaFmt: String?, fmt: String?, currency: String?): String? {
        if (delta == null || delta.isNaN()) return null
        val sign = when {
            delta > 0 -> "+"
            delta < 0 -> MINUS
            else -> ""
        }
        val magnitude = abs(delta)
        if (deltaFmt == "abs") return sign + if (fmt == "money") money(magnitude, currency) else int(magnitude)
        return "$sign${jsNumber(magnitude)}%"
    }

    /** A number as JavaScript's `String(n)` writes it: no `.0` on whole numbers, no exponent in range. */
    fun jsNumber(value: Double): String = when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
        value == 0.0 -> "0"
        value == floor(value) && abs(value) < WHOLE_LIMIT -> value.toLong().toString()
        else -> expandExponent(value.toString())
    }

    /** `Number(raw)`: blank is not a number here, where the web would read zero — a blank cell stays blank. */
    fun toNumber(raw: String): Double? = raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    // -- plumbing ---------------------------------------------------------------------------

    private fun compact(magnitude: Double): String {
        val fraction = if (magnitude >= MILLION) 2 else 1
        var tier = TIERS.indexOfLast { magnitude >= it.first }.coerceAtLeast(0)
        var scaled = halfExpand(magnitude / TIERS[tier].first, fraction)
        if (scaled >= THOUSAND && tier < TIERS.lastIndex) {
            tier += 1
            scaled = halfExpand(magnitude / TIERS[tier].first, fraction)
        }
        val digits = decimal(scaled, fraction)
        val whole = digits.substringBefore('.')
        // Compact notation groups only from five integer digits.
        val integer = if (whole.length >= COMPACT_GROUPING_MIN) grouped(whole) else whole
        val decimals = digits.substringAfter('.', "")
        return integer + (if (decimals.isEmpty()) "" else ".$decimals") + TIERS[tier].second
    }

    /** [value] rounded half away from zero to [digits] places, trailing zeros dropped. */
    private fun decimal(value: Double, digits: Int): String {
        val scale = 10.0.pow(digits)
        val units = halfExpandUnits(value, scale)
        val whole = floor(units / scale)
        val frac = (units - whole * scale).toLong()
        val wholeText = if (whole < WHOLE_LIMIT) whole.toLong().toString() else expandExponent(whole.toString())
        if (frac == 0L) return wholeText
        return wholeText + "." + frac.toString().padStart(digits, '0').trimEnd('0')
    }

    private fun halfExpand(value: Double, digits: Int): Double {
        val scale = 10.0.pow(digits)
        return halfExpandUnits(value, scale) / scale
    }

    /**
     * ICU rounds the decimal it prints, not the binary double: `9.95` is
     * `9.9499…` in binary and still rounds to `10`. The nudge restores that.
     */
    private fun halfExpandUnits(value: Double, scale: Double): Double = floor(value * scale + HALF + NUDGE)

    /** `Math.round`: halves go up, towards positive infinity. */
    private fun jsRound(value: Double): Double = floor(value + HALF)

    private fun grouped(integer: String): String {
        val negative = integer.startsWith("-")
        val digits = integer.removePrefix("-")
        val groups = digits.reversed().chunked(GROUP).joinToString(",").reversed()
        return if (negative) "-$groups" else groups
    }

    private fun expandExponent(text: String): String {
        val mantissa = text.substringBefore('E')
        val exponent = text.substringAfter('E', "").toIntOrNull() ?: return text
        val negative = mantissa.startsWith("-")
        val digits = mantissa.removePrefix("-").replace(".", "")
        val point = mantissa.removePrefix("-").indexOf('.').let { if (it < 0) digits.length else it } + exponent
        val body = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        val trimmed = (if ('.' in body) body.trimEnd('0').trimEnd('.') else body).trimStart('0')
        val leading = if (trimmed.startsWith(".") || trimmed.isEmpty()) "0$trimmed" else trimmed
        return (if (negative) "-" else "") + leading
    }

    private const val DEFAULT_CURRENCY = "GBP"
    private const val NBSP = " "
    private const val MINUS = "−"
    private const val HALF = 0.5
    private const val NUDGE = 1e-9
    private const val PCT_SCALE = 10.0
    private const val PERCENT = 100.0
    private const val THOUSAND = 1_000.0
    private const val MILLION = 1_000_000.0
    private const val WHOLE_LIMIT = 1e18
    private const val GROUP = 3
    private const val COMPACT_GROUPING_MIN = 5
    private const val INT_FRACTION_DIGITS = 3
    private val ISO_CODE = Regex("^[A-Za-z]{3}$")

    private val TIERS = listOf(1.0 to "", 1e3 to "k", 1e6 to "m", 1e9 to "bn", 1e12 to "tn")

    /** en-GB's own symbols; every other code is written before the number with a no-break space. */
    private val SYMBOLS = mapOf(
        "AUD" to "A$", "BRL" to "R$", "CAD" to "CA$", "CNY" to "CN¥", "EUR" to "€", "GBP" to "£",
        "HKD" to "HK$", "ILS" to "₪", "INR" to "₹", "JPY" to "JP¥", "KRW" to "₩", "MXN" to "MX$",
        "NZD" to "NZ$", "PHP" to "₱", "TWD" to "NT$", "USD" to "US$", "VND" to "₫", "XCD" to "EC$",
        "XAF" to "FCFA$NBSP", "XCG" to "Cg.$NBSP", "XOF" to "F${NBSP}CFA$NBSP", "XPF" to "CFPF$NBSP",
    )
}
