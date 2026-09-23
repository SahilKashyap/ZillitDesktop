package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The production's currencies and what they are worth against its default.
 *
 * A tile that adds £200 to ¥158,470 and calls the answer yuan is not a
 * rounding problem, it is a wrong number on a payment screen. The web
 * converts through each currency's `exr` before summing
 * (`describeConvertedTotal`), and says so when a rate is missing; this is the
 * same rule.
 *
 * [rates] is code → rate against [defaultCode], the default itself being 1.
 */
data class CurrencyRates(
    val defaultCode: String = "",
    val rates: Map<String, Double> = emptyMap(),
) {
    /** What [amount] in [code] is worth in the default, or null with no rate for it. */
    fun inDefault(amount: Double, code: String): Double? {
        val from = code.trim().uppercase().ifBlank { defaultCode.uppercase() }
        if (from.isBlank() || from == defaultCode.uppercase()) return amount
        val rate = rates[from]?.takeIf { it > 0 } ?: return null
        return amount / rate
    }

    /**
     * A total for a mixed list, converted, with whether anything was added at
     * face value for want of a rate.
     *
     * A single-currency list keeps its own currency and is not converted —
     * converting GBP to GBP only introduces error.
     */
    fun total(rows: List<Pair<Double, String>>): MoneyTotal {
        val codes = rows.map { it.second.trim().uppercase().ifBlank { defaultCode.uppercase() } }
            .filter { it.isNotBlank() }
            .distinct()
        if (codes.size <= 1) {
            val only = codes.firstOrNull() ?: defaultCode
            return MoneyTotal(rows.sumOf { it.first }, only, mixed = false, unrated = false)
        }
        var total = 0.0
        var unrated = false
        rows.forEach { (amount, code) ->
            val converted = inDefault(amount, code)
            if (converted == null) unrated = true
            total += converted ?: amount
        }
        return MoneyTotal(total, defaultCode, mixed = true, unrated = unrated)
    }
}

/** A summed amount, and what the reader needs to know about how it was summed. */
data class MoneyTotal(
    val amount: Double,
    val currency: String,
    val mixed: Boolean,
    val unrated: Boolean,
) {
    val text: String get() = Money.format(amount, currency)

    /** What the tile says underneath, when the figure needs qualifying. */
    val caveat: String?
        get() = when {
            unrated -> "converted; some amounts had no rate"
            mixed -> str(S.desktop_converted_to_currency, currency)
            else -> null
        }
}
