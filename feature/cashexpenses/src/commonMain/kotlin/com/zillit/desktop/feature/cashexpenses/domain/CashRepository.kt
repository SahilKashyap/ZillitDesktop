package com.zillit.desktop.feature.cashexpenses.domain

import com.zillit.desktop.core.forms.CustomFieldGroup
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Everything the cash module asks the server for.
 *
 * One interface rather than four (floats, claims, top-ups, reconciliations)
 * because they are one service with one authorisation model, and splitting
 * them would mean four fakes in every test and four constructor arguments in
 * every view model for no gain in clarity.
 *
 * Mirrors `cashExpensesApi` in the web client method for method, so a change
 * on either side is findable on the other.
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface CashRepository {

    // -- who am I ----------------------------------------------------------

    suspend fun metadata(): ZillitResult<CashMetadata>

    // -- floats ------------------------------------------------------------

    suspend fun myFloats(): ZillitResult<List<CashFloat>>

    /** Newest first — `sort=created_at&order=desc`, as the web's Active Floats asks. */
    suspend fun activeFloats(): ZillitResult<List<CashFloat>>

    /** One float with its batches, top-ups, returns and the server's totals. */
    suspend fun floatDetails(floatId: String): ZillitResult<FloatDetails>

    /**
     * Corrects a float's BS code — `PATCH /float-requests/{id}` with exactly
     * `{bs_code}` (`buildFloatBsCodePayload`): any other key risks the
     * route's unknown-column error. Gate with [CashFloat.bsCodeEditable].
     */
    suspend fun updateFloatBsCode(floatId: String, bsCode: String): ZillitResult<Unit>

    suspend fun floatApprovalQueue(): ZillitResult<List<CashFloat>>

    suspend fun floatHistory(floatId: String): ZillitResult<List<CashHistoryEntry>>

    suspend fun requestFloat(request: NewFloatRequest): ZillitResult<Unit>

    /** [tier] is the level being signed; null only where no chain is configured. */
    suspend fun approveFloat(floatId: String, tier: TierStep?): ZillitResult<Unit>

    suspend fun rejectFloat(floatId: String, reason: String): ZillitResult<Unit>

    /** Pushes a float past its approval chain. Requires the override right. */
    suspend fun overrideFloat(floatId: String): ZillitResult<Unit>

    /**
     * Marks the float collectable.
     *
     * [companyId] is not optional in practice: the server refuses the
     * transition on a float with no company, and the accountant can either set
     * it earlier or supply it here.
     */
    suspend fun markFloatReadyToCollect(floatId: String, companyId: String?, bsCode: String?): ZillitResult<Unit>

    suspend fun issueFloat(floatId: String): ZillitResult<Unit>

    suspend fun collectFloat(floatId: String): ZillitResult<Unit>

    suspend fun closeFloat(floatId: String): ZillitResult<Unit>

    suspend fun recordCashReturn(floatId: String, cashReturn: CashReturn): ZillitResult<Unit>

    // -- float top-ups (the crew's Cash Extension) --------------------------

    suspend fun floatTopUps(floatId: String): ZillitResult<List<CashTopUp>>

    suspend fun requestFloatTopUp(
        floatId: String,
        amount: Double,
        reason: String?,
    ): ZillitResult<Unit>

    // -- the accountant's top-up inbox -------------------------------------

    suspend fun topUps(): ZillitResult<List<CashTopUp>>

    suspend fun completeTopUp(topUpId: String): ZillitResult<Unit>

    suspend fun partialTopUp(topUpId: String, amount: Double, note: String): ZillitResult<Unit>

    suspend fun skipTopUp(topUpId: String): ZillitResult<Unit>

    // -- claim batches -----------------------------------------------------

    /** The viewer's own batches, narrowed to one float or one pipeline when given. */
    suspend fun myBatches(floatRequestId: String? = null, expenseType: String? = null): ZillitResult<List<ClaimBatch>>

    /** Every batch spent against one float — `GET /claims?float_request_id=`. */
    suspend fun floatBatches(floatId: String): ZillitResult<List<ClaimBatch>>

    suspend fun batch(batchId: String): ZillitResult<ClaimBatch>

    suspend fun batchHistory(batchId: String): ZillitResult<List<CashHistoryEntry>>

    suspend fun submitReceipts(request: NewClaimBatch): ZillitResult<Unit>

    suspend fun resubmitBatch(batchId: String, note: String?): ZillitResult<Unit>

    /** One queue call per workflow stage — see [CashQueue]. */
    suspend fun queue(queue: CashQueue, expenseType: ExpenseType?): ZillitResult<List<ClaimBatch>>

    suspend fun codeClaim(
        batchId: String,
        claimId: String,
        costCode: String,
        description: String?,
    ): ZillitResult<Unit>

    /**
     * Saves one receipt's coded line items.
     *
     * The server does a raw delete-then-reinsert of whatever it is given, so
     * the whole set goes every time and the ids have to be real — see
     * [LineItemEditor.toWire], which is what prepares them.
     */
    suspend fun saveClaimLines(
        batchId: String,
        claimId: String,
        lines: List<ClaimLineItem>,
    ): ZillitResult<Unit>

    /**
     * Saves the batch's claims as they stand — the audit's per-receipt Verify
     * rides on this, with the one receipt's `is_verified` flipped.
     */
    suspend fun saveClaims(
        batchId: String,
        claims: List<Claim>,
        verified: Map<String, Boolean> = emptyMap(),
    ): ZillitResult<Unit>

    /** Coordinator: saves the coding and sends the batch on to accounts. */
    suspend fun saveAndSubmitCoded(batchId: String, claims: List<Claim>?): ZillitResult<Unit>

    /** Accounts: saves the audit and sends the batch on to approval. */
    suspend fun saveAndVerify(batchId: String, claims: List<Claim>?): ZillitResult<Unit>

    /** [claimIds] null approves every receipt; a subset is a partial approval. */
    suspend fun approveBatch(batchId: String, tier: TierStep?, claimIds: List<String>?): ZillitResult<Unit>

    suspend fun rejectBatch(batchId: String, reason: String, claimIds: List<String>? = null): ZillitResult<Unit>

    suspend fun overrideBatch(batchId: String): ZillitResult<Unit>

    suspend fun escalateBatch(batchId: String, reason: String): ZillitResult<Unit>

    /** Sign-off's "Return to Accounts": an escalated batch goes back for amendment. */
    suspend fun deescalateBatch(batchId: String): ZillitResult<Unit>

    suspend fun submitBatchForReview(batchId: String): ZillitResult<Unit>

    suspend fun assignBatch(batchId: String, userId: String, reason: String?): ZillitResult<Unit>

    suspend fun postBatch(batchId: String, request: PostBatchRequest): ZillitResult<Unit>

    // -- dashboards --------------------------------------------------------

    suspend fun pettyCashOverview(): ZillitResult<PettyCashOverview>

    suspend fun outOfPocketOverview(): ZillitResult<OutOfPocketOverview>

    suspend fun myOverview(): ZillitResult<MyCashOverview>

    suspend fun departmentOverview(departmentId: String): ZillitResult<DepartmentOverview>

    suspend fun paymentRouting(): ZillitResult<PaymentRouting>

    // -- reconciliation ----------------------------------------------------

    suspend fun reconciliations(): ZillitResult<List<Reconciliation>>

    suspend fun reconciliation(id: String): ZillitResult<Reconciliation>

    /** The ledger's balance for [draft]'s opening balance and month; null for the page-level figure. */
    suspend fun computeBookBalance(draft: ReconDraft? = null): ZillitResult<Double>

    /** Opens a period: opening balance, currency, month and a blank count. */
    suspend fun createReconciliation(draft: ReconDraft): ZillitResult<Reconciliation>

    suspend fun updateReconciliation(draft: ReconDraft): ZillitResult<Reconciliation>

    suspend fun submitReconciliationForReview(draft: ReconDraft): ZillitResult<Unit>

    suspend fun signOffReconciliation(draft: ReconDraft): ZillitResult<Unit>

    // -- settings ----------------------------------------------------------

    suspend fun settings(): ZillitResult<CashSettings>

    suspend fun updateSettings(settings: CashSettings): ZillitResult<CashSettings>

    /** The team saves on its own, the moment a member is added, edited or removed. */
    suspend fun updateTeamMembers(members: List<CashTeamMember>): ZillitResult<CashSettings>

    suspend fun updateRequestCap(cap: RequestCap): ZillitResult<CashSettings>

    /** `{department_coordinators}` on its own, as the web's Coordinators section saves. */
    suspend fun updateDepartmentCoordinators(rows: List<DepartmentCoordinator>): ZillitResult<CashSettings>

    suspend fun saveAssignmentRule(rule: CashAssignmentRule): ZillitResult<CashAssignmentRule>

    suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit>

    // -- the rest of the account hub this module leans on -------------------

    /** The cost-report lock, `YYYY-MM-DD`; null when the production has none. */
    suspend fun lockedThrough(): ZillitResult<String?>

    suspend fun companies(): ZillitResult<List<CashCompany>>

    suspend fun fundRequests(): ZillitResult<List<FundRequest>>

    suspend fun createFundRequest(fundAccount: String, currency: String?, amount: Double): ZillitResult<Unit>

    suspend fun receiveFundRequest(id: String): ZillitResult<Unit>

    suspend fun cancelFundRequest(id: String): ZillitResult<Unit>

    suspend fun queryThread(batchId: String): ZillitResult<QueryThread>

    /** Adds to the thread, or opens it with this message when there is none yet. */
    suspend fun sendQuery(batchId: String, threadId: String?, text: String): ZillitResult<QueryThread>

    /** The float register as a file. */
    suspend fun exportFloats(format: ExportFormat): ZillitResult<ByteArray>

    /** The receipts register: one pipeline, or [historyOnly] for posted batches across both. */
    suspend fun exportReceipts(
        format: ExportFormat,
        expenseType: ExpenseType?,
        historyOnly: Boolean,
    ): ZillitResult<ByteArray>

    // -- settings parity --

    /**
     * One Settings section on its own — only that section's keys, as the web's
     * `saveSection` sends them. See [CashSettingsSection].
     */
    suspend fun updateSettingsSection(section: CashSettingsSection, settings: CashSettings): ZillitResult<CashSettings>


    // -- batch parity --

    /**
     * The batch view's Save / Save Progress / Save Draft: every receipt as it
     * now stands, and the ledger date when there is one —
     * `POST /claims/{id}/save-claims {claims, effective_date?}`.
     */
    suspend fun saveClaimsBatch(batchId: String, claims: List<Claim>, effectiveDate: Long?): ZillitResult<Unit>
}

/**
 * The server-side queues, one per stage of the workflow.
 *
 * An enum rather than five near-identical repository methods: the pages differ
 * only in which queue they read and what they may do with a row, and five
 * methods invites five slightly different implementations.
 */
enum class CashQueue(val path: String) {
    Coding("coding-queue"),
    Audit("audit-queue"),
    Approval("approval-queue"),
    SignOff("sign-off-queue"),
    History("history-queue"),
}

/** A float request as the crew member filled it in. */
data class NewFloatRequest(
    val amount: Double,
    val currency: String?,
    val purpose: String,
    val departmentId: String?,
    val duration: String?,
    val durationType: String?,
    val bsCode: String? = null,
    val companyId: String? = null,
    /** An accountant raising the float for someone else. */
    val targetUserId: String? = null,
    /** The extra fields this production added to the float request form. */
    val customFields: List<CustomFieldGroup> = emptyList(),
    // -- crew parity --
    /** UTC midnight epoch millis, as the web's `new Date(value).getTime()`. */
    val collectDate: Long? = null,
    val episode: String? = null,
    val collectionMethod: String? = null,
)

/** A batch of receipts as submitted, before the server assigns it a reference. */
data class NewClaimBatch(
    val expenseType: ExpenseType,
    val floatId: String?,
    val receipts: List<DraftReceipt>,
    val settlementType: String?,
    val notes: String?,
    // -- crew parity --
    /** The submitter's department — the web's `currentUser.department_id`. */
    val departmentId: String? = null,
    /** The batch's currency, stamped on every claim too: the float's, or the one picked for out of pocket. */
    val currency: String? = null,
    val settlementDetails: SettlementDetails? = null,
) {
    val total: Double
        get() = receipts.sumOf { it.amount.trim().toDoubleOrNull() ?: 0.0 }

    /**
     * The first reason this batch cannot be submitted, or null.
     *
     * Ported from the web's `newReceiptsValidationError`, including the
     * mandatory attachment: a receipt with no evidence lands on an accountant
     * who can do nothing with it but query it back.
     */
    @Suppress("ReturnCount") // One rule per return; combining them loses which rule failed.
    fun validationError(): String? {
        if (receipts.isEmpty()) return str(S.desktop_ce_add_one_receipt)
        if (receipts.any { it.description.isBlank() || (it.amount.trim().toDoubleOrNull() ?: 0.0) <= 0 }) {
            return str(S.desktop_ce_receipt_needs_description_amount)
        }
        if (receipts.any { it.date == null }) return str(S.desktop_ce_receipt_needs_date)
        if (receipts.any { it.attachmentKey.isNullOrBlank() }) {
            return str(S.desktop_ce_receipt_needs_attachment)
        }
        return null
    }
}
