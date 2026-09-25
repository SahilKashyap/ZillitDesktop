package com.zillit.desktop.feature.cashexpenses.domain

/**
 * The accountant's top-up inbox rules — `PCTopUpsPage.jsx`.
 *
 * Kept out of the page so the filter, the ordering and the float-limit check
 * the page and the handler share can be tested without composing anything.
 */
object CashTopUps {
    const val PENDING = "pending"
    const val COMPLETED = "completed"
    const val PARTIAL = "partial"
    const val SKIPPED = "skipped"

    /** Below a penny is rounding, not a breach of the float limit. */
    private const val PENNY = 0.005

    /** The quick filters: All, Pending, Completed (which holds partials too), Skipped. */
    enum class Filter { All, Pending, Completed, Skipped }

    /** The header's sort: `Oldest first` (the default), `Newest first`, `Largest first`. */
    enum class Sort { Oldest, Newest, Largest }

    fun matches(topUp: CashTopUp, filter: Filter): Boolean = when (filter) {
        Filter.All -> true
        Filter.Pending -> topUp.status == PENDING
        Filter.Completed -> topUp.status == COMPLETED || topUp.status == PARTIAL
        Filter.Skipped -> topUp.status == SKIPPED
    }

    fun count(rows: List<CashTopUp>, filter: Filter): Int = rows.count { matches(it, filter) }

    /** The web's `SORTERS` — a missing date sorts as zero, as `Number(undefined) || 0` does. */
    fun sorted(rows: List<CashTopUp>, sort: Sort): List<CashTopUp> = when (sort) {
        Sort.Oldest -> rows.sortedBy { it.createdAt ?: 0L }
        Sort.Newest -> rows.sortedByDescending { it.createdAt ?: 0L }
        Sort.Largest -> rows.sortedByDescending { it.amount }
    }

    /** Pending rows first, then everything settled — each in [sort] order, both under [filter]. */
    fun sections(rows: List<CashTopUp>, filter: Filter, sort: Sort): Pair<List<CashTopUp>, List<CashTopUp>> {
        val shown = rows.filter { matches(it, filter) }
        val (pending, settled) = shown.partition { it.status == PENDING }
        return sorted(pending, sort) to sorted(settled, sort)
    }

    /** Limit minus current balance — how much more the float can take (`roomFor`). */
    fun room(topUp: CashTopUp): Double = (topUp.floatRequestedAmount - topUp.floatBalance).coerceAtLeast(0.0)

    /** Spent so far: the limit less what is left (`floatSpent`). */
    fun spent(topUp: CashTopUp): Double = (topUp.floatRequestedAmount - topUp.floatBalance).coerceAtLeast(0.0)

    /** Spent as a share of what was issued, for the progress bar; 0 when nothing was issued. */
    fun spentFraction(topUp: CashTopUp): Float =
        if (topUp.floatIssued > 0) (spent(topUp) / topUp.floatIssued).coerceIn(0.0, 1.0).toFloat() else 0f

    /** Whether paying [amount] would take the float past its limit. */
    fun exceedsRoom(topUp: CashTopUp, amount: Double): Boolean = amount > room(topUp) + PENNY
}
