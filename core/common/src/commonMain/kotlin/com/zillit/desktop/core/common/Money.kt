package com.zillit.desktop.core.common

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Money formatting for the finance tools.
 *
 * The web renders amounts through `formatCurrency(value, code)` in a dozen
 * near-identical copies (`cardExpenses/lib/formatCurrency.js`, the cash
 * module's `groupAmount`, the invoice module's own); this is the single
 * version, so a float register and a card register cannot disagree about how
 * £1,234.50 is written.
 *
 * ## Why not the platform formatter
 *
 * `java.text.NumberFormat` is JVM-only and this is common code, and its
 * locale-driven output would render the *viewer's* convention over a
 * production's currency — a US-locale desktop showing a UK shoot's petty cash
 * as `$1,234.50`. Amounts here follow the currency, not the machine.
 */
object Money {

    /** Symbols for the currencies productions actually run in; code otherwise. */
    private val symbols = mapOf(
        "GBP" to "£",
        "USD" to "$",
        "EUR" to "€",
        "INR" to "₹",
        "AUD" to "A$",
        "CAD" to "C$",
        "NZD" to "NZ$",
        "ZAR" to "R",
        "JPY" to "¥",
        "CNY" to "¥",
        "CHF" to "CHF ",
        "SEK" to "kr ",
        "NOK" to "kr ",
        "DKK" to "kr ",
        "AED" to "AED ",
        "SGD" to "S$",
        "HKD" to "HK$",
        "PLN" to "zł ",
        "CZK" to "Kč ",
        "HUF" to "Ft ",
    )

    fun symbol(currencyCode: String?): String {
        val code = currencyCode?.trim()?.uppercase().orEmpty()
        if (code.isEmpty()) return ""
        return symbols[code] ?: "$code "
    }

    /**
     * `£1,234.50`.
     *
     * Null and unparseable amounts render as an em dash rather than `0.00`:
     * a missing figure and a zero figure mean very different things on an
     * approval queue, and printing one as the other has approved payments.
     */
    fun format(
        amount: Double?,
        currencyCode: String? = null,
        decimals: Int = DEFAULT_DECIMALS,
        blank: String = EM_DASH,
    ): String {
        if (amount == null || amount.isNaN() || amount.isInfinite()) return blank
        val negative = amount < 0
        val body = symbol(currencyCode) + group(abs(amount), decimals)
        return if (negative) "-$body" else body
    }

    /** As [format], for the string amounts the API sends numbers as. */
    fun format(
        amount: String?,
        currencyCode: String? = null,
        decimals: Int = DEFAULT_DECIMALS,
        blank: String = EM_DASH,
    ): String = format(amount.toAmountOrNull(), currencyCode, decimals, blank)

    /**
     * `1.2M`, `847K`, `1,240` — for stat tiles, where the exact pennies are
     * noise and the column width is fixed.
     */
    fun compact(amount: Double?, currencyCode: String? = null): String {
        if (amount == null || amount.isNaN() || amount.isInfinite()) return EM_DASH
        val negative = amount < 0
        val value = abs(amount)
        val body = when {
            value >= MILLION -> group(value / MILLION, 1) + "M"
            value >= THOUSAND -> group(value / THOUSAND, if (value >= HUNDRED_THOUSAND) 0 else 1) + "K"
            else -> group(value, 0)
        }
        return (if (negative) "-" else "") + symbol(currencyCode) + body
    }

    /** Thousands-separated fixed-point, no sign and no symbol. */
    fun group(value: Double, decimals: Int): String {
        val scale = POWERS_OF_TEN[decimals.coerceIn(0, MAX_DECIMALS)]
        val scaled = (value * scale).roundToLong()
        val whole = scaled / scale
        val fraction = scaled % scale

        val digits = whole.toString()
        val grouped = buildString {
            digits.forEachIndexed { index, digit ->
                if (index > 0 && (digits.length - index) % GROUP_SIZE == 0) append(',')
                append(digit)
            }
        }
        if (decimals <= 0) return grouped
        return "$grouped.${fraction.toString().padStart(decimals, '0')}"
    }

    private const val EM_DASH = "—"
    private const val DEFAULT_DECIMALS = 2
    private const val MAX_DECIMALS = 4
    private const val GROUP_SIZE = 3
    private const val THOUSAND = 1_000.0
    private const val HUNDRED_THOUSAND = 100_000.0
    private const val MILLION = 1_000_000.0
    private val POWERS_OF_TEN = longArrayOf(1, 10, 100, 1_000, 10_000)
}

/**
 * Reads a wire amount, which arrives as a number *or* a string depending on
 * the endpoint and the column type behind it.
 *
 * Blank strings become null rather than zero — see [Money.format] for why that
 * distinction is load-bearing on a money screen.
 */
fun String?.toAmountOrNull(): Double? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.replace(",", "")?.toDoubleOrNull()

/** The same read, for arithmetic where an absent figure contributes nothing. */
fun String?.toAmount(): Double = toAmountOrNull() ?: 0.0
