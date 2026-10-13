package com.zillit.desktop.feature.cashexpenses.domain

/**
 * The six steps of a float's journey as the request page draws them
 * (`PCFloatRequestPage.jsx` `STEP_DEFS` / `STATUS_FLOW`): You, Approval, Ready
 * to Collect, Cash Collected, You spend, Submit receipts.
 */
@Suppress("MagicNumber") // The step indices are the steps; STATUS_FLOW in the web names them no better.
object FloatFlow {

    const val STEPS = 6

    /** One step's state; a rejection marks the Approval step and finishes nothing after it. */
    data class Step(val done: Boolean, val active: Boolean, val rejected: Boolean = false)

    /**
     * How far [status] has got. Approved and an accountant's override are both
     * still "Approval" — cash has not been prepared; a status the web has no
     * entry for (cancelled, closed) shows no progress at all.
     */
    fun steps(status: FloatStatus): List<Step> {
        if (status == FloatStatus.Rejected) {
            return List(STEPS) { Step(done = it == 0, active = false, rejected = it == 1) }
        }
        val (done, active) = when (status) {
            FloatStatus.AwaitingApproval, FloatStatus.Approved, FloatStatus.AcctOverride -> 1 to 1
            FloatStatus.ReadyToCollect -> 2 to 2
            FloatStatus.Collected, FloatStatus.Active -> 3 to 3
            FloatStatus.Spending -> 4 to 4
            FloatStatus.Spent, FloatStatus.PendingReturn -> 5 to 5
            else -> 0 to -1
        }
        return List(STEPS) { Step(done = it < done, active = it == active) }
    }
}
