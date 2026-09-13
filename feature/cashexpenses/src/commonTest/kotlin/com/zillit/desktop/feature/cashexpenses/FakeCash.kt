package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashQueue
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation

/**
 * Reads answer with what the two flows need; every write answers
 * [writesSucceed]; everything else fails, which the view model absorbs as
 * an error banner.
 */
@Suppress("TooManyFunctions") // One override per server operation.
internal class FakeCash(var writesSucceed: Boolean) : CashRepository {
    var floatRequests = 0
    var codingSaves = 0
    var floatApprovals = 0
    var floatCloses = 0

    private fun <T> fail(): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.NoConnection(technical = "test: offline"))

    private fun write(): ZillitResult<Unit> =
        if (writesSucceed) ZillitResult.Success(Unit) else fail()

    override suspend fun metadata(): ZillitResult<CashMetadata> = ZillitResult.Success(CashMetadata())
    override suspend fun myFloats(): ZillitResult<List<CashFloat>> = ZillitResult.Success(emptyList())
    override suspend fun requestFloat(request: NewFloatRequest): ZillitResult<Unit> {
        floatRequests++
        return write()
    }

    override suspend fun queue(queue: CashQueue, expenseType: ExpenseType?): ZillitResult<List<ClaimBatch>> =
        ZillitResult.Success(listOf(queuedBatch()))

    override suspend fun saveClaimLines(
        batchId: String,
        claimId: String,
        lines: List<ClaimLineItem>,
    ): ZillitResult<Unit> {
        codingSaves++
        return write()
    }

    override suspend fun activeFloats(): ZillitResult<List<CashFloat>> = fail()
    override suspend fun floatApprovalQueue(): ZillitResult<List<CashFloat>> = fail()
    override suspend fun floatHistory(floatId: String): ZillitResult<List<CashHistoryEntry>> = fail()
    override suspend fun approveFloat(floatId: String, note: String?): ZillitResult<Unit> {
        floatApprovals++
        return if (writesSucceed) ZillitResult.Success(Unit) else fail()
    }
    override suspend fun rejectFloat(floatId: String, reason: String): ZillitResult<Unit> = fail()
    override suspend fun overrideFloat(floatId: String): ZillitResult<Unit> = fail()
    override suspend fun markFloatReadyToCollect(floatId: String, companyId: String?): ZillitResult<Unit> = fail()
    override suspend fun issueFloat(floatId: String): ZillitResult<Unit> = fail()
    override suspend fun collectFloat(floatId: String): ZillitResult<Unit> = fail()
    override suspend fun closeFloat(floatId: String): ZillitResult<Unit> {
        floatCloses++
        return if (writesSucceed) ZillitResult.Success(Unit) else fail()
    }
    override suspend fun recordCashReturn(floatId: String, amount: Double, note: String?): ZillitResult<Unit> =
        fail()

    override suspend fun floatTopUps(floatId: String): ZillitResult<List<CashTopUp>> = fail()
    override suspend fun requestFloatTopUp(floatId: String, amount: Double, reason: String?): ZillitResult<Unit> =
        fail()

    override suspend fun topUps(): ZillitResult<List<CashTopUp>> = fail()
    override suspend fun completeTopUp(topUpId: String): ZillitResult<Unit> = fail()
    override suspend fun partialTopUp(topUpId: String, amount: Double): ZillitResult<Unit> = fail()
    override suspend fun skipTopUp(topUpId: String): ZillitResult<Unit> = fail()
    override suspend fun myBatches(): ZillitResult<List<ClaimBatch>> = fail()
    override suspend fun batch(batchId: String): ZillitResult<ClaimBatch> = fail()
    override suspend fun batchHistory(batchId: String): ZillitResult<List<CashHistoryEntry>> = fail()
    override suspend fun submitReceipts(request: NewClaimBatch): ZillitResult<Unit> = fail()
    override suspend fun resubmitBatch(batchId: String, note: String?): ZillitResult<Unit> = fail()
    override suspend fun codeClaim(
        batchId: String,
        claimId: String,
        costCode: String,
        description: String?,
    ): ZillitResult<Unit> = fail()

    override suspend fun saveAndSubmitCoded(batchId: String): ZillitResult<Unit> = fail()
    override suspend fun saveAndVerify(batchId: String): ZillitResult<Unit> = fail()
    override suspend fun approveBatch(batchId: String, note: String?): ZillitResult<Unit> = fail()
    override suspend fun rejectBatch(batchId: String, reason: String): ZillitResult<Unit> = fail()
    override suspend fun overrideBatch(batchId: String): ZillitResult<Unit> = fail()
    override suspend fun queryBatch(batchId: String, reason: String): ZillitResult<Unit> = fail()
    override suspend fun escalateBatch(batchId: String, reason: String?): ZillitResult<Unit> = fail()
    override suspend fun submitBatchForReview(batchId: String): ZillitResult<Unit> = fail()
    override suspend fun assignBatch(batchId: String, userId: String, reason: String?): ZillitResult<Unit> = fail()
    override suspend fun postBatch(batchId: String, note: String?): ZillitResult<Unit> = fail()
    override suspend fun pettyCashOverview(): ZillitResult<PettyCashOverview> = fail()
    override suspend fun outOfPocketOverview(): ZillitResult<OutOfPocketOverview> = fail()
    override suspend fun myOverview(): ZillitResult<MyCashOverview> = fail()
    override suspend fun departmentOverview(departmentId: String): ZillitResult<DepartmentOverview> = fail()
    override suspend fun paymentRouting(): ZillitResult<PaymentRouting> = fail()
    override suspend fun reconciliations(): ZillitResult<List<Reconciliation>> = fail()
    override suspend fun computeBookBalance(): ZillitResult<Double> = fail()
    override suspend fun createReconciliation(countedBalance: Double, note: String?): ZillitResult<Reconciliation> =
        fail()

    override suspend fun submitReconciliationForReview(id: String): ZillitResult<Unit> = fail()
    override suspend fun signOffReconciliation(id: String, note: String?): ZillitResult<Unit> = fail()
    override suspend fun settings(): ZillitResult<CashSettings> = fail()
    override suspend fun updateSettings(settings: CashSettings): ZillitResult<CashSettings> = fail()

    /** One receipt awaiting coding, already carrying a cost code so its seeded line balances. */
    private fun queuedBatch() = ClaimBatch(
        id = "b1",
        reference = "PC-001",
        userId = "u2",
        holderName = "Sam Grip",
        departmentId = "grip",
        status = BatchStatus.Coding,
        expenseType = ExpenseType.PettyCash,
        claimCount = 1,
        totalGross = 100.0,
        reimbursementAmount = 0.0,
        currency = "GBP",
        settlementType = null,
        paymentMethod = null,
        notes = null,
        assignedTo = null,
        assignedBy = null,
        assignmentReason = null,
        createdAt = null,
        claims = listOf(
            Claim(
                id = "c1",
                batchId = "b1",
                description = "Gaffer tape",
                supplier = null,
                category = null,
                costCode = "5010",
                codedDescription = null,
                episode = null,
                receiptDate = null,
                grossAmount = 100.0,
                netAmount = 100.0,
                vatAmount = 0.0,
                taxRate = null,
                taxType = null,
                settlementType = null,
                status = BatchStatus.Coding,
                receiptUrl = null,
            ),
        ),
    )
}
