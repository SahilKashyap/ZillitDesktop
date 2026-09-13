package com.zillit.desktop.feature.dealmemo.domain.rates

import kotlin.math.abs
import kotlin.math.floor

/**
 * One published rate for one day length: `{base_rate, min_rate, max_rate,
 * work_hrs?, day_type?}` — an entry of a rate row's `hourly[]`/`daily[]`/
 * `weekly[]`/`flat_rate[]`, or an agreement's `basic_rate_details` tier.
 */
data class RateTierEntry(
    val baseRate: Double? = null,
    val minRate: Double? = null,
    val maxRate: Double? = null,
    val workHours: Double? = null,
    /** `SWD`, `CWD`… Empty is kept apart from absent: the web treats them differently. */
    val dayType: String? = null,
)

/**
 * The web's shared rate formatters (`deal-memo/utils/rateFormat.js`,
 * `utils/currencySymbols.js`, `utils/money.js`), exactly.
 *
 * Every rate a crew member is offered is read off these strings, so the rules
 * are the web's to the character — the glued unknown code (`MAD540`), the
 * strict `<` on an open-bottomed budget, the kept trailing zero in `£2.50m`.
 */
object RateFormat {

    private val SYMBOLS: Map<String, String> = mapOf(
        "GBP" to "£", "USD" to "$", "EUR" to "€", "JPY" to "¥", "CNY" to "¥",
        "AUD" to "A$", "NZD" to "NZ$", "CAD" to "C$", "HKD" to "HK$", "SGD" to "S$",
        "CHF" to "CHF ", "INR" to "₹", "ZAR" to "R", "SEK" to "kr", "NOK" to "kr", "DKK" to "kr",
        "HUF" to "Ft", "PLN" to "zł", "CZK" to "Kč", "THB" to "฿", "AED" to "د.إ",
        "MXN" to "$", "BRL" to "R$", "KRW" to "₩", "RUB" to "₽", "TRY" to "₺", "ILS" to "₪",
    )

    private val PRODUCTION_TYPE_LABELS = mapOf(
        "feature" to "Feature",
        "television" to "Television",
        "commercial" to "Commercial",
        "documentary" to "Documentary",
        "music" to "Music",
    )

    /** `currencySymbol(code)`: the symbol, else the bare code upper-cased, else nothing. */
    fun currencySymbol(code: String?): String {
        val upper = code.orEmpty().trim().uppercase()
        return SYMBOLS[upper] ?: upper
    }

    /**
     * `CURRENCY_SYMBOLS[currency] ?? currency ?? ""` — the lookup the agreement
     * caps use, which does not upper-case first.
     */
    fun symbolExact(code: String?): String = code?.let { SYMBOLS[it] ?: it }.orEmpty()

    /**
     * `groupAmountAuto`: en-GB grouping, no forced decimals, at most two —
     * `1,250.5`, `25`, `2.28`. Rounded half away from zero on the shortest
     * decimal form, as `toLocaleString` does.
     */
    fun groupAmountAuto(value: Double): String {
        if (!value.isFinite()) return Js.number(value)
        val hundredths = hundredths(value)
        val whole = (hundredths / HUNDRED).toString().reversed().chunked(THOUSANDS).joinToString(",").reversed()
        val fraction = (hundredths % HUNDRED).toString().padStart(2, '0').trimEnd('0')
        val sign = if (value < 0 && hundredths > 0) "-" else ""
        return sign + whole + if (fraction.isEmpty()) "" else ".$fraction"
    }

    /** `groupAmount(v)`: en-GB grouping at exactly two places — `1,250.00`; non-finite reads as zero. */
    fun groupAmount(value: Double): String {
        val finite = if (value.isFinite()) value else 0.0
        val hundredths = hundredths(finite)
        val whole = (hundredths / HUNDRED).toString().reversed().chunked(THOUSANDS).joinToString(",").reversed()
        val sign = if (finite < 0 && hundredths > 0) "-" else ""
        return "$sign$whole.${(hundredths % HUNDRED).toString().padStart(2, '0')}"
    }

    fun productionTypeLabel(productionType: String?, translate: (String) -> String?): String {
        if (productionType.isNullOrEmpty()) return ""
        val translated = translate(productionType)
        if (!translated.isNullOrEmpty() && translated != productionType) return translated
        return PRODUCTION_TYPE_LABELS[productionType] ?: productionType
    }

    private fun envelope(min: Double?, max: Double?, sym: String): String? = when {
        min != null && max != null && min != max -> "$sym${groupAmountAuto(min)} – $sym${groupAmountAuto(max)}"
        min != null && max != null -> "$sym${groupAmountAuto(min)}"
        min != null -> "≥ $sym${groupAmountAuto(min)}"
        max != null -> "≤ $sym${groupAmountAuto(max)}"
        else -> null
    }

    /** `formatTier`: `£429`, `£429 (£380 – £500)`, `≥ £380`, or null when the tier says nothing. */
    fun formatTier(tier: RateTierEntry?, currency: String?): String? {
        if (tier == null) return null
        val base = tier.baseRate
        val min = tier.minRate
        val max = tier.maxRate
        if (base == null && min == null && max == null) return null
        val sym = currencySymbol(currency)
        val range = envelope(min, max, sym)
        if (base == null) return range
        val showRange = range != null && !(min == max && min == base)
        return if (showRange) "$sym${groupAmountAuto(base)} ($range)" else "$sym${groupAmountAuto(base)}"
    }

    /** `formatRange`: [formatTier], with an em dash for nothing. */
    fun formatRange(tier: RateTierEntry?, currency: String?): String = formatTier(tier, currency) ?: DASH

    /** `formatRateWithHours`: `£600/10hrs`, `£600`, `10hrs`, or an em dash. */
    fun formatRateWithHours(tier: RateTierEntry?, currency: String?): String {
        val rate = formatTier(tier, currency)
        val hours = tier?.workHours
        return when {
            rate == null && hours == null -> DASH
            rate == null -> "${Js.number(hours!!)}hrs"
            hours == null -> rate
            else -> "$rate/${Js.number(hours)}hrs"
        }
    }

    /**
     * `formatTierArray`: every day length on one line, `£540/10hrs (CWD) · £594/11hrs (SWD)`.
     *
     * An entry that says nothing is dropped only when it is untagged — a
     * day-type-only entry still prints as `— (SWD)`.
     */
    fun formatTierArray(entries: List<RateTierEntry>?, currency: String?): String {
        if (entries.isNullOrEmpty()) return DASH
        val parts = entries.mapNotNull { entry ->
            val body = formatRateWithHours(entry, currency)
            when {
                body == DASH && entry.dayType == null -> null
                !entry.dayType.isNullOrEmpty() -> "$body (${entry.dayType})"
                else -> body
            }
        }
        return if (parts.isEmpty()) DASH else parts.joinToString(" · ")
    }

    /** `formatBudgetRange`: `£2.50m – £30m`, `≥ £12.3k`, and a strict `< £4m` for an open bottom. */
    @Suppress("CyclomaticComplexMethod")
    fun formatBudgetRange(min: Double?, max: Double?, currency: String?): String {
        val sym = currencySymbol(currency)
        fun amount(n: Double): String = when {
            n >= MILLION -> (n / MILLION).let { m -> "$sym${if (isInteger(m)) Js.number(m) else Js.toFixed(m, 2)}m" }
            n >= THOUSAND -> (n / THOUSAND).let { k -> "$sym${if (isInteger(k)) Js.number(k) else Js.toFixed(k, 1)}k" }
            else -> "$sym${Js.number(n)}"
        }
        return when {
            min == null && max == null -> DASH
            min != null && max != null -> "${amount(min)} – ${amount(max)}"
            min != null -> "≥ ${amount(min)}"
            else -> "< ${amount(max!!)}"
        }
    }

    /** `formatExpRange`: `2–5y`, `3+y`, `<2y`. */
    fun formatExpRange(min: Double?, max: Double?): String = when {
        min == null && max == null -> DASH
        min != null && max != null -> "${Js.number(min)}–${Js.number(max)}y"
        min != null -> "${Js.number(min)}+y"
        else -> "<${Js.number(max!!)}y"
    }

    private fun isInteger(value: Double): Boolean = value.isFinite() && value == floor(value)

    /** Whole hundredths of |value|, rounded half away from zero on its shortest decimal form. */
    private fun hundredths(value: Double): Long {
        val plain = Js.plainDecimal(abs(value))
        val point = plain.indexOf('.')
        val whole = if (point < 0) plain else plain.substring(0, point)
        val decimals = (if (point < 0) "" else plain.substring(point + 1)).padEnd(ROUNDING_DIGIT + 1, '0')
        val truncated = whole.toLong() * HUNDRED + decimals.take(ROUNDING_DIGIT).toLong()
        return if (decimals[ROUNDING_DIGIT] >= '5') truncated + 1 else truncated
    }

    const val DASH = "—"
    private const val HUNDRED = 100L
    private const val THOUSANDS = 3
    private const val ROUNDING_DIGIT = 2
    private const val MILLION = 1_000_000.0
    private const val THOUSAND = 1_000.0
}
