package com.zillit.desktop.feature.purchaseorder.domain

import kotlin.math.roundToLong

/**
 * The Queue's Cash Flow Forecast — the web's `computeCashFlowWeeks`.
 *
 * Six weeks from today. Each posted or committed order is expected to go out
 * [PAYMENT_DAYS] after its effective date; posted counts as *confirmed*,
 * approved or Acct Entered as *committed*. Amounts are thousands, rounded per
 * order, exactly as the web sums them. An undated order, or one whose cash-out
 * falls outside the window, is not in the forecast.
 */
data class PoCashWeek(val index: Int, val confirmedK: Long, val committedK: Long) {
    val totalK: Long get() = confirmedK + committedK
}

object PoCashFlow {
    const val WEEKS = 6
    const val PAYMENT_DAYS = 30
    private const val DAYS_PER_WEEK = 7
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val THOUSAND = 1_000.0

    fun weeks(orders: List<PurchaseOrder>, todayMillis: Long): List<PoCashWeek> {
        val today = todayMillis / MILLIS_PER_DAY
        val confirmed = LongArray(WEEKS)
        val committed = LongArray(WEEKS)
        orders.forEach { order ->
            val isConfirmed = order.status == PoStatus.Posted
            val isCommitted = order.status == PoStatus.Approved ||
                order.status == PoStatus.AccountsEntered ||
                order.status == PoStatus.Queued
            val effective = order.effectiveDate ?: return@forEach
            if (!isConfirmed && !isCommitted) return@forEach
            val offset = effective / MILLIS_PER_DAY + PAYMENT_DAYS - today
            if (offset < 0 || offset >= WEEKS * DAYS_PER_WEEK) return@forEach
            val week = (offset / DAYS_PER_WEEK).toInt()
            val thousands = (order.gross / THOUSAND).roundToLong()
            if (isConfirmed) confirmed[week] += thousands else committed[week] += thousands
        }
        return (0 until WEEKS).map { PoCashWeek(it + 1, confirmed[it], committed[it]) }
    }
}
