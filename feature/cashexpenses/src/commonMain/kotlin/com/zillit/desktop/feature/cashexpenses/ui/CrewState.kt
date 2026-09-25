package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.feature.cashexpenses.domain.BankDetailsDraft
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExtraBankField
import com.zillit.desktop.feature.cashexpenses.domain.ReimbursementMethod

/**
 * The crew pages' own state — Submit Receipts' settlement choices, Receipts
 * History's filter and open receipt, the Float Request landing — beside the
 * shared drafts ([SubmitDraft], [FloatRequestDraft]) they complete.
 *
 * Held on the module state, not in composition, for the reason every draft
 * here is: the workspace disposes an inactive window's composition.
 */
data class CrewState(
    /** The float picked to submit against; null takes the oldest spendable one. */
    val submitFloatId: String? = null,
    /** `top_up`, `close`, or null — the optional follow-up chips. */
    val followUp: String? = null,
    val reimbursementMethod: ReimbursementMethod = ReimbursementMethod.Bacs,
    val bank: BankDetailsDraft = BankDetailsDraft(),
    /** Out of pocket's batch currency; blank takes the project default. */
    val submitCurrency: String = "",
    /**
     * The production's `reimburse_to_payroll`, from `GET /settings` — the
     * web's `useCashSettings`. Null until read, and a failed read (the route is
     * an accountant's) stays null: Payroll is offered only on a clear yes.
     */
    val cashSettings: CashSettings? = null,
    /** The submit form's inline refusal, as the web shows it above the button. */
    val submitError: String? = null,
    /** Receipts History's quick filter — a [HistoryFilter] key. */
    val historyFilter: String = HistoryFilter.ALL,
    /** The receipt whose detail dialog is open. */
    val openClaimId: String? = null,
    /** Float Request: the form is open rather than the float list. */
    val floatFormOpen: Boolean = false,
    /** Float Request: per-field refusals, keyed by the field's label; `currency` for the picker. */
    val floatErrors: Map<String, String> = emptyMap(),
    val floatSubmitError: String? = null,
) {
    val payrollAllowed: Boolean get() = cashSettings?.reimburseToPayroll == true

    /** The method the payload may carry — Payroll only while it is offered. */
    val effectiveMethod: ReimbursementMethod
        get() = if (payrollAllowed) reimbursementMethod else ReimbursementMethod.Bacs
}

/** The crew's own events, handled by [CrewDesk]. */
sealed interface CrewEvent : CashEvent {
    data class PickSubmitFloat(val floatId: String) : CrewEvent

    /** Toggles a follow-up chip: picking the one already on turns it off. */
    data class ToggleFollowUp(val followUp: String) : CrewEvent

    data class PickReimbursement(val method: ReimbursementMethod) : CrewEvent

    data class EditBank(val bank: BankDetailsDraft) : CrewEvent

    data object AddBankExtra : CrewEvent

    data class EditBankExtra(val index: Int, val row: ExtraBankField) : CrewEvent

    data class RemoveBankExtra(val index: Int) : CrewEvent

    data class PickSubmitCurrency(val code: String) : CrewEvent

    data class FilterHistory(val filter: String) : CrewEvent

    data class OpenClaim(val claimId: String) : CrewEvent

    data object CloseClaim : CrewEvent

    /** The crew member's query thread on the open batch — theirs to read and write. */
    data class ShowQuery(val open: Boolean) : CrewEvent

    data object SendQuery : CrewEvent

    data class ShowFloatForm(val open: Boolean) : CrewEvent
}

/** Receipts History's quick filters (`PCMyClaimsPage.jsx:34-45`), in the web's order. */
object HistoryFilter {
    const val ALL = "All"

    val KEYS: List<String> = listOf(
        ALL,
        BatchStatus.Coding.wire,
        BatchStatus.InAudit.wire,
        BatchStatus.AwaitingApproval.wire,
        BatchStatus.ReadyToPost.wire,
        BatchStatus.Rejected.wire,
        BatchStatus.UnderReview.wire,
        BatchStatus.Escalated.wire,
        BatchStatus.Queried.wire,
        BatchStatus.Posted.wire,
    )

    /** Ready to Post also takes an accountant's override, as it does for the crew label. */
    fun matches(filter: String, batch: ClaimBatch): Boolean = when (filter) {
        ALL -> true
        BatchStatus.ReadyToPost.wire ->
            batch.status == BatchStatus.ReadyToPost || batch.status == BatchStatus.AcctOverride
        else -> batch.status.wire == filter
    }
}

/** The crew's rules over the float and batch lists. */
object CrewRules {

    /** Every float the crew member may submit against, oldest first as loaded. */
    fun submittable(floats: List<CashFloat>): List<CashFloat> = floats.filter { it.status.isSubmittable }

    /**
     * The float picked, else the oldest spendable one — re-anchored when the
     * picked one leaves the list (a socket flips it to SPENT while the page is up).
     */
    fun submitFloat(floats: List<CashFloat>, pickedId: String?): CashFloat? {
        val spendable = submittable(floats)
        return spendable.firstOrNull { it.id == pickedId } ?: spendable.firstOrNull()
    }

    /**
     * The batches still in the pipeline against [float] — not posted, rejected
     * or closed (`PCSubmitClaimPage.jsx:578-588`). Out-of-pocket batches and
     * other floats' batches never count.
     */
    fun pendingAgainst(float: CashFloat?, batches: List<ClaimBatch>): List<ClaimBatch> {
        float ?: return emptyList()
        return batches.filter { batch ->
            batch.floatRequestId == float.id &&
                batch.status != BatchStatus.Posted &&
                batch.status != BatchStatus.Rejected &&
                batch.status != BatchStatus.Unknown
        }
    }
}
