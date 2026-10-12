package com.zillit.desktop.feature.cashexpenses.domain

/**
 * The out-of-pocket overview's Status filter (`OOPOverviewPage.jsx:340-345`).
 *
 * The three buckets partition the batches and must not overlap: Approved is
 * cleared approval and not yet posted, Paid is posted, Pending is everything
 * before either. Approved once included posted ones, which made it a superset
 * of Paid and the two filters identical in the normal end state (ZL-20791).
 */
enum class OopStatusFilter {
    All,
    Pending,
    Approved,
    Paid,
    ;

    fun matches(status: BatchStatus): Boolean = when (this) {
        All -> true
        Pending -> status !in APPROVED && status != BatchStatus.Posted
        Approved -> status in APPROVED
        Paid -> status == BatchStatus.Posted
    }

    private companion object {
        val APPROVED = setOf(BatchStatus.ReadyToPost, BatchStatus.AcctOverride)
    }
}

/** The Routing filter: the rail a batch is paid by, `settlement_details.payment_method`. */
enum class OopRoutingFilter(val method: String?) {
    All(null),
    Bacs("BACS"),
    Payroll("PAYROLL"),
    ;

    fun matches(batch: ClaimBatch): Boolean = method == null || batch.paymentMethod == method
}

/** The OOP overview's table rows under its two filters. */
fun List<ClaimBatch>.filterOop(status: OopStatusFilter, routing: OopRoutingFilter): List<ClaimBatch> =
    filter { status.matches(it.status) && routing.matches(it) }
