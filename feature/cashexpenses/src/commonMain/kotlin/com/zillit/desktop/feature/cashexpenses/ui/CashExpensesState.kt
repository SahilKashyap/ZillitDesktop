package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormTemplate
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
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashCompany
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap

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
    /** Unread notifications per `level_1` key — the tabs' red chips. */
    val unread: Map<String, Int> = emptyMap(),
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
    /**
     * The production's crew, resolved by the host: who a batch may be handed
     * to, and where every name on these pages comes from — the cash service
     * sends people as ids. See `CashPeople`.
     */
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
    /**
     * What the accountant configured the float request form to be.
     *
     * Empty until it is read, and an unread template shows every field — a
     * form must not blank its own controls because a fetch failed.
     */
    val formTemplate: FormTemplate = FormTemplate(),
    val settingsDraft: CashSettings? = null,
    val prompt: CashPrompt? = null,
    /** The receipt whose coding is open, if any. */
    val coding: CodingDraft? = null,

    // -- the web's full-page views and their inputs ------------------------
    /** The cost-report lock, `YYYY-MM-DD`; a post may not be dated on or before it. */
    val lockedThrough: String? = null,
    /** Production Setup's companies — what a float is pinned to before collection. */
    val companies: List<CashCompany> = emptyList(),
    /** The open batch's receipts, fetched rather than trusted from the queue row. */
    val panel: BatchPanel? = null,
    /** The reconciliation open for counting, if any. */
    val recon: ReconDraft? = null,
    /** The Fund Requests surface, when it is open. */
    val funds: FundsState? = null,
    /** The team member being added or edited in Settings. */
    val teamEditor: TeamMemberDraft? = null,
    /** Settings' Request Cap section, while it is being edited. */
    val capDraft: RequestCap? = null,
    /** Settings' auto-assignment rules, while they are being edited. */
    val rulesDraft: List<CashAssignmentRule>? = null,
    /** An export is on its way down. */
    val exporting: Boolean = false,
) {
    /** The float request form's own rules — which fields show, which are required. */
    val floatForm: FormLayout get() = FormLayout(formTemplate)

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

    /**
     * The open batch's receipts: fetched when there are some, the row's own
     * otherwise. Every web view fetches them (`getExpenseClaimBatch`); a queue
     * row may carry none, and a batch detail with no receipts is a batch nobody
     * can check.
     */
    val panelClaims: List<Claim>
        get() = panel?.claims ?: selectedBatch?.claims.orEmpty()

    /**
     * Whether this viewer may code receipts on this page: a coordinator in the
     * coding queue, an accountant correcting one in audit or before posting —
     * never inside the locked period. The screen and the handler both ask.
     */
    val canCode: Boolean
        get() = when {
            selectedLocked -> false
            destination == CashDestination.CodingQueue -> viewer.isCoordinator
            destination == CashDestination.AuditQueue || destination.isPostLedger -> viewer.isAccountant
            else -> false
        }

    /** Whether the open batch's stored ledger date sits inside the locked period. */
    val selectedLocked: Boolean
        get() = selectedBatch?.let { CashDates.isLocked(it.effectiveDate, lockedThrough) } == true

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
    /** An accountant raising the float for this crew member; blank for one's own. */
    val targetUserId: String = "",
    val amount: String = "",
    val purpose: String = "",
    val duration: String = "",
    val durationType: String = "days",
    val departmentId: String = "",
    /** The extra fields this production added, by their form key. */
    val customFields: Map<String, String> = emptyMap(),
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

    /**
     * Ready to Collect: the company the float spends under, and its BS code.
     *
     * Always asked, even when the float already has a company — this is the
     * one point in a float's life where the BS code can be set (the web's
     * "Set Company & BS Code", `PCFloatsPage.jsx:455-487`).
     */
    data class ReadyToCollect(
        val floatId: String,
        val companyId: String = "",
        val bsCode: String = "",
    ) : CashPrompt

    /** A manual cash return — [floatId] null asks which float first. */
    data class RecordReturn(
        val floatId: String?,
        val amount: String = "",
        val receivedDate: String = "",
        val reason: String = ReturnReasons.CLOSE_FULL,
        /** "Other" splits into close or continue. */
        val otherCloses: Boolean = true,
        val notes: String = "",
    ) : CashPrompt

    /** Opens a reconciliation period: the safe's opening balance, the month and the currency. */
    data class NewReconciliation(
        val openingBalance: String = "",
        val year: Int,
        val month: Int,
        val currency: String = "",
    ) : CashPrompt
}

/** The web's `RETURN_REASONS`, as wire keys. */
object ReturnReasons {
    const val CLOSE_FULL = "close_full_return"
    const val CONTINUE_PARTIAL = "continue_partial_return"
    const val OVERSPEND = "overspend_settlement"
    const val CANCEL = "cancel_float_return"
    const val OTHER = "other"
    val ALL = listOf(CLOSE_FULL, CONTINUE_PARTIAL, OVERSPEND, CANCEL, OTHER)

    /** The key sent: "Other" becomes `close_other` or `continue_other`. */
    fun wire(reason: String, otherCloses: Boolean): String = when {
        reason != OTHER -> reason
        otherCloses -> "close_other"
        else -> "continue_other"
    }

    /** Reasons that close the float, so the whole balance has to come back. */
    fun closes(wire: String): Boolean = wire in setOf(CLOSE_FULL, CANCEL, OVERSPEND, "close_other")

    /** Reasons that keep the float going, so some balance has to stay. */
    fun continues(wire: String): Boolean = wire == CONTINUE_PARTIAL || wire == "continue_other"
}

/**
 * One batch open in the detail pane — its receipts and the inputs its actions take.
 *
 * Fetched on open, as every web view does (`getExpenseClaimBatch`), rather
 * than trusted from the queue row, which may carry no receipts at all.
 */
data class BatchPanel(
    val batchId: String,
    /** Null while the receipts load. */
    val claims: List<Claim>? = null,
    val failed: Boolean = false,
    /** `YYYY-MM-DD`; seeded from the batch, else the first day the lock allows. */
    val effectiveDate: String = "",
    /** Sign-off's notes, attached to the journal entry. */
    val seniorNotes: String = "",
    /** Receipts ticked for approval; null until the receipts arrive. */
    val selectedClaimIds: Set<String>? = null,
    /** The audit trail, once asked for. */
    val history: List<com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry>? = null,
    val historyOpen: Boolean = false,
    /** The query thread, while it is open. */
    val query: QueryPanel? = null,
    /** The receipt whose Verify is saving. */
    val verifying: String? = null,
)

/** A batch's query thread, open beside it. */
data class QueryPanel(
    val thread: com.zillit.desktop.feature.cashexpenses.domain.QueryThread? = null,
    val loading: Boolean = true,
    val draft: String = "",
    val sending: Boolean = false,
)

/** The Fund Requests surface: the list, and the new-request form beside it. */
data class FundsState(
    val requests: List<com.zillit.desktop.feature.cashexpenses.domain.FundRequest> = emptyList(),
    val loading: Boolean = true,
    val fundAccount: String = "",
    val currency: String = "",
    val amount: String = "",
)

/** A team member being added or edited — the web's Team Member modal. */
data class TeamMemberDraft(
    val userId: String = "",
    /** Null is unlimited, which a senior always is. */
    val postingLimit: String? = "0",
    val canOverride: Boolean = false,
    val isSenior: Boolean = false,
    /** The member's place in the list when editing; null adds. */
    val index: Int? = null,
)

enum class ReasonedAction { RejectFloat, RejectBatch, EscalateBatch }

enum class AmountAction { PartialTopUp, RequestFloatTopUp }

enum class ConfirmAction {
    ApproveFloat,
    OverrideFloat,
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
    SubmitReconciliation,
    ReturnToAccounts,
    ReceiveFunds,
    CancelFunds,
}
