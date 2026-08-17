package com.zillit.desktop.feature.cashexpenses.domain

import com.zillit.desktop.core.common.ZillitResult

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

    suspend fun activeFloats(): ZillitResult<List<CashFloat>>

    suspend fun floatApprovalQueue(): ZillitResult<List<CashFloat>>

    suspend fun floatHistory(floatId: String): ZillitResult<List<CashHistoryEntry>>

    suspend fun requestFloat(request: NewFloatRequest): ZillitResult<Unit>

    suspend fun approveFloat(floatId: String, note: String?): ZillitResult<Unit>

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
    suspend fun markFloatReadyToCollect(floatId: String, companyId: String?): ZillitResult<Unit>

    suspend fun issueFloat(floatId: String): ZillitResult<Unit>

    suspend fun collectFloat(floatId: String): ZillitResult<Unit>

    suspend fun closeFloat(floatId: String): ZillitResult<Unit>

    suspend fun recordCashReturn(floatId: String, amount: Double, note: String?): ZillitResult<Unit>

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

    suspend fun partialTopUp(topUpId: String, amount: Double): ZillitResult<Unit>

    suspend fun skipTopUp(topUpId: String): ZillitResult<Unit>

    // -- claim batches -----------------------------------------------------

    suspend fun myBatches(): ZillitResult<List<ClaimBatch>>

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

    /** Coordinator: saves the coding and sends the batch on to accounts. */
    suspend fun saveAndSubmitCoded(batchId: String): ZillitResult<Unit>

    /** Accounts: saves the audit and sends the batch on to approval. */
    suspend fun saveAndVerify(batchId: String): ZillitResult<Unit>

    suspend fun approveBatch(batchId: String, note: String?): ZillitResult<Unit>

    suspend fun rejectBatch(batchId: String, reason: String): ZillitResult<Unit>

    suspend fun overrideBatch(batchId: String): ZillitResult<Unit>

    suspend fun queryBatch(batchId: String, reason: String): ZillitResult<Unit>

    suspend fun escalateBatch(batchId: String, reason: String?): ZillitResult<Unit>

    suspend fun submitBatchForReview(batchId: String): ZillitResult<Unit>

    suspend fun assignBatch(batchId: String, userId: String, reason: String?): ZillitResult<Unit>

    suspend fun postBatch(batchId: String, note: String?): ZillitResult<Unit>

    // -- dashboards --------------------------------------------------------

    suspend fun pettyCashOverview(): ZillitResult<PettyCashOverview>

    suspend fun outOfPocketOverview(): ZillitResult<OutOfPocketOverview>

    suspend fun myOverview(): ZillitResult<MyCashOverview>

    suspend fun departmentOverview(departmentId: String): ZillitResult<DepartmentOverview>

    suspend fun paymentRouting(): ZillitResult<PaymentRouting>

    // -- reconciliation ----------------------------------------------------

    suspend fun reconciliations(): ZillitResult<List<Reconciliation>>

    suspend fun computeBookBalance(): ZillitResult<Double>

    suspend fun createReconciliation(
        countedBalance: Double,
        note: String?,
    ): ZillitResult<Reconciliation>

    suspend fun submitReconciliationForReview(id: String): ZillitResult<Unit>

    suspend fun signOffReconciliation(id: String, note: String?): ZillitResult<Unit>

    // -- settings ----------------------------------------------------------

    suspend fun settings(): ZillitResult<CashSettings>

    suspend fun updateSettings(settings: CashSettings): ZillitResult<CashSettings>
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
)

/** A batch of receipts as submitted, before the server assigns it a reference. */
data class NewClaimBatch(
    val expenseType: ExpenseType,
    val floatId: String?,
    val receipts: List<DraftReceipt>,
    val settlementType: String?,
    val notes: String?,
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
        if (receipts.isEmpty()) return "Add at least one receipt."
        if (receipts.any { it.description.isBlank() || (it.amount.trim().toDoubleOrNull() ?: 0.0) <= 0 }) {
            return "Each receipt must have a description and an amount."
        }
        if (receipts.any { it.date == null }) return "Each receipt must have a date of purchase."
        if (receipts.any { it.attachmentKey.isNullOrBlank() }) {
            return "Each receipt must have an attachment — upload the receipt image or PDF."
        }
        return null
    }
}
