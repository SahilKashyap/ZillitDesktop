package com.zillit.desktop.feature.accounthub.domain

/**
 * One account's movement and closing balance over the period.
 *
 * [ending] is the balance the report shows, not `debit - credit` recomputed
 * here: opening balances and conversions are the server's, and a client that
 * derived the figure would disagree with the exported PDF.
 */
data class TrialBalanceRow(
    val accountCode: String = "",
    val name: String = "",
    /** The chart's cost type, which is what the report groups by. */
    val costType: String = "",
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val ending: Double = 0.0,
)

/** A cost type's rows, with its subtotal. */
data class TrialBalanceGroup(
    val costType: String,
    val label: String,
    val rows: List<TrialBalanceRow>,
) {
    val debit: Double get() = rows.sumOf { it.debit }
    val credit: Double get() = rows.sumOf { it.credit }
    val balance: Double get() = rows.sumOf { it.ending }
}

/**
 * The report over one period.
 *
 * Grouped by cost type with Expense pinned last and the rest alphabetical —
 * the order an accountant reads a trial balance in, and the web's.
 */
data class TrialBalance(
    val rows: List<TrialBalanceRow> = emptyList(),
) {
    val groups: List<TrialBalanceGroup>
        get() = rows.groupBy { it.costType }
            .map { (costType, rows) ->
                TrialBalanceGroup(
                    costType = costType,
                    label = labelFor(costType),
                    rows = rows.sortedWith(compareBy(NUMERIC_CODE) { it.accountCode }),
                )
            }
            .sortedBy { sortKey(it.costType) }

    val debit: Double get() = rows.sumOf { it.debit }
    val credit: Double get() = rows.sumOf { it.credit }
    val balance: Double get() = rows.sumOf { it.ending }

    /**
     * Whether the two sides agree.
     *
     * To the half-penny, not exactly: these are sums of converted decimals,
     * and demanding exact equality would report a balanced ledger as broken.
     */
    val isBalanced: Boolean get() = kotlin.math.abs(debit - credit) < TOLERANCE

    companion object {
        private const val TOLERANCE = 0.005

        /** Expense last; everything else by its label. */
        private fun sortKey(costType: String): String =
            if (costType == "expense") "￿" else labelFor(costType)

        fun labelFor(costType: String): String = when {
            costType.isBlank() -> "Uncategorised"
            else -> costType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

        /**
         * Codes sort as numbers where they are numbers.
         *
         * `100` before `20` is what a plain string sort gives, and an
         * accountant reading a chart by code notices immediately.
         */
        private val NUMERIC_CODE = Comparator<String> { a, b ->
            val left = a.toLongOrNull()
            val right = b.toLongOrNull()
            when {
                left != null && right != null -> left.compareTo(right)
                else -> a.compareTo(b)
            }
        }
    }
}

/**
 * The window a report covers.
 *
 * Supplied by the host rather than computed here: a year boundary needs a
 * calendar and a zone, and this module has neither — pulling a date library in
 * for one subtraction would be the wrong trade.
 */
data class ReportPeriod(val startMillis: Long, val endMillis: Long)

/** What the report is asked for. */
data class TrialBalanceQuery(
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    /** Blank means every account type. */
    val accountType: String = "",
    /** Blank means the production's default currency; the server converts. */
    val currency: String = "",
    /**
     * Accounts with no movement and a zero balance.
     *
     * Always sent, including when false: omitting it would leave the server's
     * own default to decide, and the two have disagreed before.
     */
    val includeZeroAccounts: Boolean = false,
)

// -- period close ------------------------------------------------------------

/**
 * How far the production's books are closed.
 *
 * One boundary, and it only ever moves forward. Everything dated on or before
 * [lockedThrough] is read-only in every source module — purchase orders,
 * invoices, cards, cash and payroll alike — and there is no endpoint to undo
 * it. That is why this screen asks before it acts and never offers an unlock.
 *
 * [lockedThrough] is the inclusive last day closed, as `YYYY-MM-DD`. Blank
 * means nothing has been closed yet.
 */
data class PeriodLock(
    val lockedThrough: String = "",
    /** The production's zone, which is what decides where a day ends. */
    val timeZone: String = "",
    val weekStartDay: Int? = null,
    val weekEndDay: Int? = null,
) {
    val isClosed: Boolean get() = lockedThrough.isNotBlank()
}
