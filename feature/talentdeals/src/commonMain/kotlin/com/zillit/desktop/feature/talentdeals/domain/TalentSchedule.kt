package com.zillit.desktop.feature.talentdeals.domain

import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers.num
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers.r2
import kotlin.math.abs

/** One tranche as the form holds it: strings the inputs bind to, dates as `yyyy-mm-dd`. */
data class TrancheDraft(
    val id: String,
    val label: String = "",
    val start: String = "",
    val end: String = "",
    val weeks: String = "",
    val value: String = "",
)

/** The running state of a schedule — what the allocation bar shows and what the save gate reads. */
data class ScheduleTotals(
    val fee: Double,
    val allocated: Double,
    val pctUsed: Double,
    val remaining: Double,
    val balanced: Boolean,
) {
    /** More than the fee has been handed out. */
    val over: Boolean get() = remaining < -OVER_TOLERANCE

    private companion object {
        const val OVER_TOLERANCE = 0.005
    }
}

/**
 * The payment schedule — the fee split into dated tranches.
 *
 * THE PERCENTAGE RULE, because it is the one thing a reader assumes wrongly: a
 * percentage is always a percentage of the TOTAL FEE, never of what is left
 * after the fixed amounts. 10% is a tenth of the whole contract wherever the
 * row sits, so re-ordering tranches never silently re-prices the others.
 *
 * A schedule is all percentages or all amounts — [PaidAs] is one setting for
 * the whole table, not per row.
 */
object TalentSchedule {

    /** The stages a schedule is normally split across. Offered, never enforced. */
    val TRANCHE_TYPES: List<String> = listOf("Prep", "Shoot", "Post")

    private const val PENNY = 0.01
    private const val PERCENT = 100.0

    /**
     * Weeks a whole schedule covers. The column is filled in by hand or from the
     * dates, so a tranche with the figure blank falls back to its dates. The
     * form and the read-only page both read this, so Exhibit G's day rate is
     * the same on either.
     */
    fun scheduleWeeks(tranches: List<TrancheDraft>): Double = tranches.sumOf { tranche ->
        val typed = num(tranche.weeks)
        if (typed > 0) typed else num(TalentDates.weeksBetween(tranche.start, tranche.end))
    }

    /** What one tranche is worth in currency. See the percentage rule above. */
    fun amountOf(tranche: TrancheDraft, fee: Double, paidAs: PaidAs): Double = when (paidAs) {
        PaidAs.Flat -> r2(num(tranche.value))
        PaidAs.Percent -> r2(fee * num(tranche.value) / PERCENT)
    }

    /**
     * One penny of slack, and no more: three equal thirds of £10,000 resolve to
     * 9,999.99, which is correct, while anything larger is a real mistake.
     * A zero fee cannot be "balanced" by an empty schedule — that is an
     * unstarted contract, not a complete one.
     */
    fun totals(tranches: List<TrancheDraft>, feeInput: String?, paidAs: PaidAs): ScheduleTotals {
        val fee = r2(num(feeInput))
        val allocated = r2(tranches.sumOf { amountOf(it, fee, paidAs) })
        val pctUsed = if (paidAs == PaidAs.Percent) r2(tranches.sumOf { num(it.value) }) else 0.0
        return ScheduleTotals(
            fee = fee,
            allocated = allocated,
            pctUsed = pctUsed,
            remaining = r2(fee - allocated),
            balanced = fee > 0 && tranches.isNotEmpty() && abs(fee - allocated) <= PENNY,
        )
    }

    /**
     * Start, end and weeks describe the same span, so editing one fills the
     * others in rather than leaving the row contradicting itself.
     */
    fun withStart(row: TrancheDraft, start: String): TrancheDraft = when {
        row.weeks.isNotEmpty() -> row.copy(start = start, end = TalentDates.endOfWeeks(start, row.weeks))
        row.end.isNotEmpty() -> row.copy(start = start, weeks = TalentDates.weeksBetween(start, row.end))
        else -> row.copy(start = start)
    }

    fun withEnd(row: TrancheDraft, end: String): TrancheDraft =
        row.copy(end = end, weeks = if (row.start.isNotEmpty()) TalentDates.weeksBetween(row.start, end) else "")

    fun withWeeks(row: TrancheDraft, weeks: String): TrancheDraft =
        row.copy(weeks = weeks, end = if (row.start.isNotEmpty()) TalentDates.endOfWeeks(row.start, weeks) else row.end)
}
