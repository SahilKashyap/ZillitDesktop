package com.zillit.desktop.feature.cardexpenses.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * The All Transactions filters, which the server applies (`transactionQuery.js`).
 *
 * Statement, card, department and a date window combine; status and free text
 * stay client predicates over what comes back. Blank values are **omitted**
 * rather than sent empty — `card_id=` asks for transactions on a card with no
 * id, not for all of them.
 */
data class TransactionFilters(
    val statementId: String = "",
    val cardId: String = "",
    val departmentId: String = "",
    /** `YYYY-MM-DD`, inclusive. */
    val from: String = "",
    /** `YYYY-MM-DD`, inclusive — the query runs to the last second of the day. */
    val to: String = "",
) {
    /** How many filters are set; the date window counts once, however many ends it has. */
    val count: Int
        get() = listOf(statementId, cardId, departmentId).count { it.isNotBlank() } +
            if (from.isNotBlank() || to.isNotBlank()) 1 else 0

    /** The query string, in UTC and end-inclusive, as the web sends it. */
    fun query(): Map<String, String> = buildMap {
        statementId.trim().takeIf { it.isNotEmpty() }?.let { put("statement_id", it) }
        cardId.trim().takeIf { it.isNotEmpty() }?.let { put("card_id", it) }
        departmentId.trim().takeIf { it.isNotEmpty() }?.let { put("department_id", it) }
        from.trim().takeIf { it.isNotEmpty() }?.let { put("from", "${it}T00:00:00Z") }
        to.trim().takeIf { it.isNotEmpty() }?.let { put("to", "${it}T23:59:59Z") }
    }

    companion object {
        /**
         * What the page opens on: the last month of spend, up to [today].
         *
         * Backwards, because every transaction is already in the past — a
         * window starting today would open on an empty table. A month back
         * from the 31st clamps to the shorter month's last day.
         */
        fun lastMonth(today: Long): TransactionFilters {
            val end = CardDates.toIso(today)
            val start = runCatching { LocalDate.parse(end).minus(1, DateTimeUnit.MONTH).toString() }.getOrDefault("")
            return TransactionFilters(from = start, to = end)
        }
    }
}
