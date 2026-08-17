package com.zillit.desktop.feature.cashexpenses.domain

import kotlin.math.max
import kotlin.math.min

/**
 * How a batch of receipts settles against the float it was spent from.
 *
 * Every figure the Submit Receipts screen shows comes from here, from one
 * computation, so the reduce/reimburse decision, the reimbursed amount, the
 * "float consumed" line and the "cash to return" line cannot disagree with
 * each other — which is exactly what happened on the web before
 * `computeFloatSettlement` was extracted.
 */
data class FloatSettlement(
    val issued: Double,
    val balance: Double,
    /** Null when the server did not tell us; see [of] for what that changes. */
    val receiptsCommits: Double?,
    val headroom: Double,
    /** True when the batch exceeds the headroom and the difference is owed back. */
    val reimburses: Boolean,
    /** The amount owed back to the submitter. Zero on a plain reduce. */
    val overdraft: Double,
    /** The part of the batch the float absorbs. */
    val floatConsumed: Double,
    /** Cash still to hand back. Negative means the holder is out of pocket. */
    val returnAmount: Double,
) {
    companion object {

        /**
         * Works out the settlement for [activeFloat] taking [newBatchTotal].
         *
         * ## The headroom model, and why it has a floor
         *
         * Nominally the spendable headroom is `issued − receiptsCommits`: what
         * was handed over, less everything already committed against it. Two
         * corrections make that safe to act on:
         *
         *  - it is **clamped to the balance**, because a backend that reports
         *    `receipts_commits` as pending-only would otherwise say there is
         *    more headroom than there is cash;
         *  - it is **floored by `balance − pendingBatchesTotal`**, the figure
         *    this client can always compute fresh. A stale commitment figure
         *    can therefore never overstate the headroom, and never understate
         *    what is reimbursed.
         *
         * When the server sends no commitment figure at all, the live floor is
         * the whole answer — which is the behaviour that shipped before the
         * field existed, so nothing regresses on a production running an older
         * service.
         *
         * ## Never blocks
         *
         * Unlike the card module's headroom, this never refuses a submission.
         * Cash always lets crew submit; spend beyond the headroom is routed to
         * the reimburse path rather than rejected, because the money has
         * already left their pocket and telling them "no" does not undo that.
         */
        fun of(
            activeFloat: CashFloat?,
            newBatchTotal: Double = 0.0,
            pendingBatchesTotal: Double = 0.0,
        ): FloatSettlement {
            val issued = activeFloat?.issuedAmount?.takeIf { it > 0 }
                ?: activeFloat?.requestedAmount
                ?: 0.0
            val balance = activeFloat?.balance ?: 0.0

            // Defensive clamps. Both are sums of positive amounts in the UI,
            // but a negative would make `floatConsumed` negative and break the
            // identity `overdraft + floatConsumed == batch`.
            val batch = max(0.0, newBatchTotal)
            val pending = max(0.0, pendingBatchesTotal)

            val liveFloor = max(0.0, balance - pending)
            val commits = activeFloat?.receiptsCommits

            val headroom = if (commits == null) {
                liveFloor
            } else {
                min(min(balance, max(0.0, issued - commits)), liveFloor)
            }

            // Strictly greater: a batch that exactly meets the headroom is a
            // reduce, not a reimbursement. Mirrors the card module's gate.
            return FloatSettlement(
                issued = issued,
                balance = balance,
                receiptsCommits = commits,
                headroom = headroom,
                reimburses = batch > headroom,
                overdraft = max(0.0, batch - headroom),
                floatConsumed = min(batch, headroom),
                returnAmount = headroom - batch,
            )
        }
    }
}

/**
 * Totals for a set of batches, kept per currency.
 *
 * Productions shoot across borders and a float register can hold sterling and
 * euros at once. Summing them into one number would be wrong in a way nobody
 * notices until a reconciliation fails, so totals are always a map keyed by
 * currency — the web reaches the same conclusion and renders one table per
 * currency.
 */
fun List<ClaimBatch>.totalsByCurrency(defaultCurrency: String?): Map<String, Double> =
    groupBy { it.currency?.takeIf(String::isNotBlank) ?: defaultCurrency.orEmpty() }
        .mapValues { (_, batches) -> batches.sumOf { it.totalGross } }

/** As [totalsByCurrency], for floats. */
fun List<CashFloat>.floatTotalsByCurrency(defaultCurrency: String?): Map<String, Double> =
    groupBy { it.currency?.takeIf(String::isNotBlank) ?: defaultCurrency.orEmpty() }
        .mapValues { (_, floats) -> floats.sumOf { it.balance } }
