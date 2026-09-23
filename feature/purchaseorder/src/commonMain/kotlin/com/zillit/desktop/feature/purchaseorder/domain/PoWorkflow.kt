package com.zillit.desktop.feature.purchaseorder.domain

/**
 * The cost-report lock: every source document dated on or before
 * [lockedThrough] is read-only, and the server refuses a write to one.
 *
 * The web's `useCrLock` + `isDateLocked`. Blank means nothing is locked, which is
 * also what a failed read means — the lock is a courtesy the client extends so
 * people are not offered an action the server will refuse, never the guard.
 */
data class PoPeriodLock(val lockedThrough: String = "") {
    val isLocked: Boolean get() = lockedThrough.isNotBlank()

    /**
     * Whether a document dated [millis] sits inside the locked period.
     *
     * Compared as UTC days, which is how both the effective date (UTC midnight)
     * and the boundary are stored. An undated order is never locked — the web's
     * `isDateLocked` answers false for an empty date.
     */
    fun locks(millis: Long?): Boolean =
        isLocked && millis != null && millis.utcIsoDay() <= lockedThrough

    /** Whether this order's own saved effective date is locked. */
    fun locks(order: PurchaseOrder): Boolean = locks(order.effectiveDate)
}

/**
 * An order's query thread — the web's `QueryPanel` over `/account-hub/queries`.
 *
 * One record per order, created by the first message; everything after is
 * appended to it.
 */
data class PoQueryThread(val id: String, val messages: List<PoQueryMessage>)

data class PoQueryMessage(val text: String, val by: String?, val at: Long?)

/**
 * The project currencies' exchange rates against the default.
 *
 * `exr` is foreign-per-default (`foreign = default × exr`), so converting a
 * foreign amount into the default divides by it — the web's `toDefaultCurrency`.
 */
data class PoCurrencyRates(
    val defaultCode: String? = null,
    /** Upper-case ISO code to its `exr`; a currency with no usable rate is absent. */
    val rates: Map<String, Double> = emptyMap(),
) {
    /**
     * Converts [amount] in [code] into the default currency, or null when the
     * code has no rate — the caller adds such an amount at face value and says so.
     */
    fun toDefault(amount: Double, code: String?, default: String): Double? {
        val currency = code?.uppercase()?.takeIf { it.isNotBlank() } ?: return amount
        if (currency == default.uppercase()) return amount
        return rates[currency]?.let { amount / it }
    }
}
