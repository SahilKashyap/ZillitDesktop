package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatSettlement
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation

/**
 * Everything the cash tool is showing right now.
 *
 * One state object for the whole module rather than one per page, because the
 * pages share almost all of it: the viewer's rights gate every tab, the float
 * list drives four screens, and a batch approved on the queue has to disappear
 * from the overview counts. Per-page state made those go stale on the web,
 * which is why it re-fetches on every tab change.
 */
data class CashUiState(
    val viewer: CashViewer,
    val destination: CashDestination,
    /** Which pipeline the sub-navigation is showing. */
    val pipeline: ExpenseType = ExpenseType.PettyCash,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    /** Set while an action (approve, post, submit) is in flight. */
    val busy: Boolean = false,
    /** Confirmation of the last completed action, for the toast. */
    val notice: String? = null,

    // -- loaded data, by the page that needs it ---------------------------
    val pettyCashOverview: PettyCashOverview? = null,
    val outOfPocketOverview: OutOfPocketOverview? = null,
    val myOverview: MyCashOverview? = null,
    val departmentOverview: DepartmentOverview? = null,
    val paymentRouting: PaymentRouting? = null,
    val myFloats: List<CashFloat> = emptyList(),
    val activeFloats: List<CashFloat> = emptyList(),
    val floatApprovals: List<CashFloat> = emptyList(),
    val topUps: List<CashTopUp> = emptyList(),
    val floatTopUps: List<CashTopUp> = emptyList(),
    val queueBatches: List<ClaimBatch> = emptyList(),
    /** Who a batch may be handed to, resolved by the host from the crew. */
    val assignees: List<AssigneeOption> = emptyList(),
    val myBatches: List<ClaimBatch> = emptyList(),
    val reconciliations: List<Reconciliation> = emptyList(),
    val bookBalance: Double? = null,
    val settings: CashSettings? = null,

    // -- transient screen state -------------------------------------------
    val selectedBatchId: String? = null,
    val selectedFloatId: String? = null,
    val search: String = "",
    val draft: SubmitDraft = SubmitDraft(),
    val floatDraft: FloatRequestDraft = FloatRequestDraft(),
    val settingsDraft: CashSettings? = null,
    val prompt: CashPrompt? = null,
    /** The receipt whose coding is open, if any. */
    val coding: CodingDraft? = null,
) {
    /** The pages offered in the sub-navigation, for the current pipeline. */
    val sectionDestinations: List<CashDestination>
        get() = CashDestination.entries.filter {
            it.section != CashSection.Shared &&
                it.section.isOutOfPocket == (pipeline == ExpenseType.OutOfPocket) &&
                it.visibleTo(viewer)
        }

    /** The cross-pipeline pages offered in the top bar. */
    val sharedDestinations: List<CashDestination>
        get() = CashDestination.entries.filter {
            it.section == CashSection.Shared && it.visibleTo(viewer)
        }

    val onSharedPage: Boolean get() = destination.section == CashSection.Shared

    val selectedBatch: ClaimBatch?
        get() = (queueBatches + myBatches).firstOrNull { it.id == selectedBatchId }

    /** The float receipts may currently be submitted against, if any. */
    val submittableFloat: CashFloat?
        get() = myFloats.firstOrNull { it.status.isSubmittable }

    /**
     * Batches the crew member has submitted that are not yet posted.
     *
     * Their total is the freshness guard in the settlement maths — see
     * [FloatSettlement]. Computed here rather than fetched so it cannot lag
     * behind a submission made moments ago in this same window.
     */
    val pendingBatchesTotal: Double
        get() = myBatches.filterNot { it.status.isPosted }.sumOf { it.totalGross }

    val settlement: FloatSettlement
        get() = FloatSettlement.of(
            activeFloat = submittableFloat,
            newBatchTotal = draft.total,
            pendingBatchesTotal = pendingBatchesTotal,
        )
}

/**
 * One receipt's coding, open for editing.
 *
 * Held on the module state rather than in the row's composition: the workspace
 * disposes an inactive window's composition, and half-typed coding vanishing
 * because someone checked their mail is not acceptable on a screen people work
 * through a hundred rows at a time.
 */
data class CodingDraft(
    val batchId: String,
    val claimId: String,
    /** What the receipt itself came to, which the coding has to reach. */
    val receiptGross: Double,
    val currency: String?,
    val lines: List<EditorLine>,
) {
    val total: Double get() = LineItemEditor.total(lines)

    val balances: Boolean get() = LineItemEditor.balances(lines, receiptGross)

    /** What is still uncoded — negative when the coding overshoots. */
    val remaining: Double get() = receiptGross - total
}

/** The Submit Receipts form. */
data class SubmitDraft(
    val receipts: List<DraftReceipt> = listOf(DraftReceipt()),
    val notes: String = "",
) {
    val total: Double get() = receipts.sumOf { it.amount.trim().toDoubleOrNull() ?: 0.0 }
}

/** The Float Request form. */
data class FloatRequestDraft(
    val amount: String = "",
    val purpose: String = "",
    val duration: String = "",
    val durationType: String = "days",
    val departmentId: String = "",
)

/**
 * A question the screen is asking before it does something irreversible.
 *
 * Modelled as state rather than shown from a click handler so the answer
 * survives the window being switched away from and back — the workspace
 * disposes an inactive window's composition, and a dialog held in local
 * composition state would vanish mid-decision.
 */
sealed interface CashPrompt {

    /** Needs a reason before it can proceed. */
    data class WithReason(
        val action: ReasonedAction,
        val targetId: String,
        val title: String,
        val label: String,
        val reason: String = "",
    ) : CashPrompt

    /** Needs an amount — a partial top-up, a cash return. */
    data class WithAmount(
        val action: AmountAction,
        val targetId: String,
        val title: String,
        val label: String,
        val amount: String = "",
        val note: String = "",
    ) : CashPrompt

    /**
     * Hands a batch to someone.
     *
     * Its own shape rather than a [WithReason]: the reason is required only
     * on a reassignment, and the person is the field that must be chosen —
     * see [BatchAssignment].
     */
    data class Assign(
        val batchId: String,
        val title: String,
        val label: String,
        val selectedUserId: String = "",
        val reason: String = "",
    ) : CashPrompt

    /** Yes or no. */
    data class Confirm(
        val action: ConfirmAction,
        val targetId: String,
        val title: String,
        val message: String,
    ) : CashPrompt
}

enum class ReasonedAction { RejectFloat, RejectBatch, QueryBatch, EscalateBatch }

enum class AmountAction { PartialTopUp, RecordCashReturn, RequestFloatTopUp, CreateReconciliation }

enum class ConfirmAction {
    ApproveFloat,
    OverrideFloat,
    IssueFloat,
    ReadyToCollect,
    CollectFloat,
    CloseFloat,
    ApproveBatch,
    OverrideBatch,
    PostBatch,
    SubmitForReview,
    SaveAndVerify,
    SaveAndSubmitCoded,
    CompleteTopUp,
    SkipTopUp,
    SignOffReconciliation,
}
