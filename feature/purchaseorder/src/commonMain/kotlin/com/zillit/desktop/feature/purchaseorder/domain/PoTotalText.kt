package com.zillit.desktop.feature.purchaseorder.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.math.abs
import kotlin.math.floor

/**
 * A total as the web's stat cards and headers print it: [value] compact
 * (`£1.2M`) and [title] the exact figure the pointer rests on (`£1,234,567.89`),
 * which also says when a currency without a rate was added at face value.
 */
data class PoTotalText(val value: String, val title: String)

/**
 * The web's `formatCompact` (`accountHub/utils/currency.js`): two decimals of
 * precision with trailing zeros dropped — `1.05B`, `2.9M`, `150K`, `1.25K` —
 * and amounts under a thousand verbatim.
 *
 * Not `Money.compact`, which rounds a hundred thousand and over to whole
 * thousands: `150.5K` there reads `151K` where the web's card reads `150.5K`.
 * Callers pass the magnitude; the sign is theirs to place before the symbol.
 */
object PoCompact {
    private const val SCALE = 100.0
    private val steps = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")

    fun number(amount: Double): String {
        if (!amount.isFinite()) return "0"
        val step = steps.firstOrNull { abs(amount) >= it.first }
        if (step == null) return trimmed(Money.group(abs(amount), 2))
        // JS `Math.round` rounds a half up, towards +infinity.
        val rounded = floor(abs(amount) / step.first * SCALE + 0.5) / SCALE
        val whole = rounded == floor(rounded)
        return (if (whole) rounded.toLong().toString() else rounded.toString()) + step.second
    }

    private fun trimmed(fixed: String): String = fixed.removeSuffix(".00").let { text ->
        if (text.contains('.') && text.endsWith("0")) text.dropLast(1) else text
    }
}

/**
 * The web's `describeConvertedTotal` over a list of orders.
 *
 * One currency keeps its own total; several are each converted into the
 * project default through its `exr` and summed there. A currency with no rate
 * is added at face value, which the hover title owns up to.
 */
fun PoCurrencyRates.describe(
    orders: List<PurchaseOrder>,
    fallbackDefault: String,
    amount: (PurchaseOrder) -> Double = { it.gross },
): PoTotalText {
    val default = (defaultCode ?: fallbackDefault).uppercase()
    val codes = orders.map { it.currency?.uppercase()?.takeIf { code -> code.isNotBlank() } ?: default }.distinct()
    var unrated = false
    val total: Double
    val shown: String
    if (codes.size <= 1) {
        total = orders.sumOf(amount)
        shown = codes.firstOrNull() ?: default
    } else {
        total = orders.sumOf { order ->
            toDefault(amount(order), order.currency, default) ?: amount(order).also { unrated = true }
        }
        shown = default
    }
    val sign = if (total < 0) "-" else ""
    val symbol = Money.symbol(shown)
    val exact = Money.group(abs(total), 2)
    return PoTotalText(
        value = sign + symbol + PoCompact.number(abs(total)),
        title = sign + symbol + exact + if (unrated) " · " + str(S.desktop_po_total_no_rate_note) else "",
    )
}
