package com.zillit.desktop.feature.cardexpenses.domain

import kotlin.time.Clock
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
 *
 * The date window is EITHER [untilToday] (every transaction up to now, no dates
 * sent at all) OR the [from]/[to] range — never both. A [to] of today or later is
 * never sent either way: there are no future transactions, and `to=<today>T23:59:59Z`
 * is a UTC day boundary, so west of UTC it clipped part of today's own spend.
 * [withDateRules] is what Done applies to a typed draft.
 */
data class TransactionFilters(
    val statementId: String = "",
    val cardId: String = "",
    val departmentId: String = "",
    /** `YYYY-MM-DD`, inclusive. */
    val from: String = "",
    /** `YYYY-MM-DD`, inclusive — the query runs to the last second of the day. */
    val to: String = "",
    val untilToday: Boolean = false,
) {
    /**
     * How many filters are set; the date window counts once, however many ends
     * it has. [untilToday] alone still counts as one — the Filters button must
     * show a filter is applied even though nothing is sent. A range whose only
     * content is a [to] of today or later counts as none: the query would send nothing.
     */
    val count: Int
        get() {
            val today = todayIso()
            val hasDateFilter = untilToday || from.isNotBlank() || sendsTo(today)
            return listOf(statementId, cardId, departmentId).count { it.isNotBlank() } +
                if (hasDateFilter) 1 else 0
        }

    /** The query string, in UTC and end-inclusive, as the web sends it. */
    fun query(): Map<String, String> = buildMap {
        statementId.trim().takeIf { it.isNotEmpty() }?.let { put("statement_id", it) }
        cardId.trim().takeIf { it.isNotEmpty() }?.let { put("card_id", it) }
        departmentId.trim().takeIf { it.isNotEmpty() }?.let { put("department_id", it) }
        if (!untilToday) {
            val today = todayIso()
            from.trim().takeIf { it.isNotEmpty() }?.let { put("from", "${it}T00:00:00Z") }
            to.trim().takeIf { sendsTo(today) }?.let { put("to", "${it}T23:59:59Z") }
        }
    }

    /** A real date strictly before [today] — the web's `pickDateTo`; anything else narrows nothing. */
    private fun sendsTo(today: String): Boolean = YMD.matches(to.trim()) && to.trim() < today

    /**
     * The draft as Done applies it (`applyDateRules`): each end a real date and never after
     * [today], so a future From cannot return an empty table. Left alone while [untilToday] is
     * on, so switching it off brings the typed range back.
     */
    fun withDateRules(today: String = todayIso()): TransactionFilters {
        if (untilToday) return this
        fun notAfterToday(day: String) = day.trim().takeIf { YMD.matches(it) }?.let { minOf(it, today) }.orEmpty()
        return copy(from = notAfterToday(from), to = notAfterToday(to))
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

        private val YMD = Regex("""\d{4}-\d{2}-\d{2}""")

        internal fun todayIso(): String = CardDates.toIso(Clock.System.now().toEpochMilliseconds())
    }
}
