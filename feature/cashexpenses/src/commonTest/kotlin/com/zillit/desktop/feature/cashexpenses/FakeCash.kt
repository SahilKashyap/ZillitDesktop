package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashCompany
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashQueue
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashReturn
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cashexpenses.domain.FundRequest
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.PostBatchRequest
import com.zillit.desktop.feature.cashexpenses.domain.QueryThread
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.domain.TierStep

/**
 * Reads answer with what the flows need; every write answers
 * [writesSucceed] and is recorded in [calls]; everything else fails, which
 * the view model absorbs as an error banner.
 */
@Suppress("TooManyFunctions") // One override per server operation.
internal class FakeCash(var writesSucceed: Boolean) : CashRepository {
    var floatRequests = 0
    var codingSaves = 0
    var floatApprovals = 0
    var floatCloses = 0

    var metadata = CashMetadata()
    var queueRows: List<ClaimBatch> = listOf(queuedBatch())
    var activeFloatRows: List<CashFloat>? = null
    var topUpRows: List<CashTopUp>? = null
    var settingsDoc: CashSettings? = null
    var lock: String? = null

    /** Every write, as `name:target` — the tests read what reached the server. */
    val calls = mutableListOf<String>()
    var lastPost: PostBatchRequest? = null
    var lastApprovalTier: TierStep? = null
    var lastClaimIds: List<String>? = listOf("unset")
    var lastReturn: CashReturn? = null
    var lastTeam: List<CashTeamMember>? = null
    var lastFloatRequest: NewFloatRequest? = null

    private fun <T> fail(): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.NoConnection(technical = "test: offline"))

    private fun write(call: String): ZillitResult<Unit> {
        calls += call
        return if (writesSucceed) ZillitResult.Success(Unit) else fail()
    }

    override suspend fun metadata(): ZillitResult<CashMetadata> = ZillitResult.Success(metadata)
    override suspend fun myFloats(): ZillitResult<List<CashFloat>> = ZillitResult.Success(emptyList())
    override suspend fun requestFloat(request: NewFloatRequest): ZillitResult<Unit> {
        floatRequests++
        lastFloatRequest = request
        return write("requestFloat")
    }

    override suspend fun queue(queue: CashQueue, expenseType: ExpenseType?): ZillitResult<List<ClaimBatch>> =
        ZillitResult.Success(queueRows)

    override suspend fun saveClaimLines(
        batchId: String,
        claimId: String,
        lines: List<ClaimLineItem>,
    ): ZillitResult<Unit> {
        codingSaves++
        return write("saveClaimLines:$batchId")
    }

    override suspend fun activeFloats(): ZillitResult<List<CashFloat>> =
        activeFloatRows?.let { ZillitResult.Success(it) } ?: fail()

    override suspend fun floatApprovalQueue(): ZillitResult<List<CashFloat>> =
        activeFloatRows?.let { ZillitResult.Success(it) } ?: fail()

    override suspend fun floatHistory(floatId: String): ZillitResult<List<CashHistoryEntry>> = fail()
    override suspend fun approveFloat(floatId: String, tier: TierStep?): ZillitResult<Unit> {
        floatApprovals++
        lastApprovalTier = tier
        return write("approveFloat:$floatId")
    }

    override suspend fun rejectFloat(floatId: String, reason: String): ZillitResult<Unit> =
        write("rejectFloat:$floatId")
    override suspend fun overrideFloat(floatId: String): ZillitResult<Unit> = write("overrideFloat:$floatId")
    override suspend fun markFloatReadyToCollect(
        floatId: String,
        companyId: String?,
        bsCode: String?,
    ): ZillitResult<Unit> = write("readyToCollect:$floatId:$companyId:$bsCode")

    override suspend fun issueFloat(floatId: String): ZillitResult<Unit> = write("issueFloat:$floatId")
    override suspend fun collectFloat(floatId: String): ZillitResult<Unit> = write("collectFloat:$floatId")
    override suspend fun closeFloat(floatId: String): ZillitResult<Unit> {
        floatCloses++
        return write("closeFloat:$floatId")
    }

    override suspend fun recordCashReturn(floatId: String, cashReturn: CashReturn): ZillitResult<Unit> {
        lastReturn = cashReturn
        return write("recordReturn:$floatId")
    }

    override suspend fun floatTopUps(floatId: String): ZillitResult<List<CashTopUp>> = fail()
    override suspend fun requestFloatTopUp(floatId: String, amount: Double, reason: String?): ZillitResult<Unit> =
        write("requestTopUp:$floatId")

    override suspend fun topUps(): ZillitResult<List<CashTopUp>> = topUpRows?.let { ZillitResult.Success(it) } ?: fail()
    override suspend fun completeTopUp(topUpId: String): ZillitResult<Unit> = write("completeTopUp:$topUpId")
    override suspend fun partialTopUp(topUpId: String, amount: Double, note: String): ZillitResult<Unit> =
        write("partialTopUp:$topUpId:$amount:$note")

    override suspend fun skipTopUp(topUpId: String): ZillitResult<Unit> = write("skipTopUp:$topUpId")
    override suspend fun myBatches(): ZillitResult<List<ClaimBatch>> = fail()

    /** The batch as the detail fetch returns it — its receipts included. */
    override suspend fun batch(batchId: String): ZillitResult<ClaimBatch> =
        queueRows.firstOrNull { it.id == batchId }?.let { ZillitResult.Success(it) } ?: fail()

    override suspend fun batchHistory(batchId: String): ZillitResult<List<CashHistoryEntry>> = fail()
    override suspend fun submitReceipts(request: NewClaimBatch): ZillitResult<Unit> = fail()
    override suspend fun resubmitBatch(batchId: String, note: String?): ZillitResult<Unit> = fail()
    override suspend fun codeClaim(
        batchId: String,
        claimId: String,
        costCode: String,
        description: String?,
    ): ZillitResult<Unit> = fail()

    override suspend fun saveClaims(
        batchId: String,
        claims: List<Claim>,
        verified: Map<String, Boolean>,
    ): ZillitResult<Unit> = write("saveClaims:$batchId:$verified")

    override suspend fun saveAndSubmitCoded(batchId: String, claims: List<Claim>?): ZillitResult<Unit> =
        write("saveAndSubmitCoded:$batchId")

    override suspend fun saveAndVerify(batchId: String, claims: List<Claim>?): ZillitResult<Unit> =
        write("saveAndVerify:$batchId")

    override suspend fun approveBatch(batchId: String, tier: TierStep?, claimIds: List<String>?): ZillitResult<Unit> {
        lastApprovalTier = tier
        lastClaimIds = claimIds
        return write("approveBatch:$batchId")
    }

    override suspend fun rejectBatch(batchId: String, reason: String, claimIds: List<String>?): ZillitResult<Unit> =
        write("rejectBatch:$batchId")

    override suspend fun overrideBatch(batchId: String): ZillitResult<Unit> = write("overrideBatch:$batchId")
    override suspend fun escalateBatch(batchId: String, reason: String): ZillitResult<Unit> =
        write("escalate:$batchId:$reason")

    override suspend fun deescalateBatch(batchId: String): ZillitResult<Unit> = write("deescalate:$batchId")
    override suspend fun submitBatchForReview(batchId: String): ZillitResult<Unit> = write("submitForReview:$batchId")
    override suspend fun assignBatch(batchId: String, userId: String, reason: String?): ZillitResult<Unit> =
        write("assign:$batchId:$userId")

    override suspend fun postBatch(batchId: String, request: PostBatchRequest): ZillitResult<Unit> {
        lastPost = request
        return write("post:$batchId")
    }

    override suspend fun pettyCashOverview(): ZillitResult<PettyCashOverview> = fail()
    override suspend fun outOfPocketOverview(): ZillitResult<OutOfPocketOverview> = fail()
    override suspend fun myOverview(): ZillitResult<MyCashOverview> = fail()
    override suspend fun departmentOverview(departmentId: String): ZillitResult<DepartmentOverview> = fail()
    override suspend fun paymentRouting(): ZillitResult<PaymentRouting> = fail()
    override suspend fun reconciliations(): ZillitResult<List<Reconciliation>> = ZillitResult.Success(emptyList())
    override suspend fun reconciliation(id: String): ZillitResult<Reconciliation> = fail()
    override suspend fun computeBookBalance(draft: ReconDraft?): ZillitResult<Double> = ZillitResult.Success(0.0)
    override suspend fun createReconciliation(draft: ReconDraft): ZillitResult<Reconciliation> {
        calls += "createRecon"
        return if (writesSucceed) {
            ZillitResult.Success(
                Reconciliation(
                    id = "r1", reference = null, status = ReconDraft.DRAFT, periodStart = null, periodEnd = null,
                    bookBalance = draft.opening, countedBalance = 0.0, currency = draft.currency, note = null,
                    createdAt = null,
                ),
            )
        } else {
            fail()
        }
    }

    override suspend fun updateReconciliation(draft: ReconDraft): ZillitResult<Reconciliation> = fail()
    override suspend fun submitReconciliationForReview(draft: ReconDraft): ZillitResult<Unit> =
        write("submitRecon:${draft.id}")

    override suspend fun signOffReconciliation(draft: ReconDraft): ZillitResult<Unit> =
        write("signOffRecon:${draft.id}")

    override suspend fun settings(): ZillitResult<CashSettings> =
        settingsDoc?.let { ZillitResult.Success(it) } ?: fail()
    override suspend fun updateSettings(settings: CashSettings): ZillitResult<CashSettings> = fail()
    override suspend fun updateTeamMembers(members: List<CashTeamMember>): ZillitResult<CashSettings> {
        lastTeam = members
        calls += "team"
        val saved = (settingsDoc ?: CashSettings()).copy(teamMembers = members)
        return if (writesSucceed) ZillitResult.Success(saved) else fail()
    }

    override suspend fun updateRequestCap(cap: RequestCap): ZillitResult<CashSettings> = fail()
    override suspend fun saveAssignmentRule(rule: CashAssignmentRule): ZillitResult<CashAssignmentRule> = fail()
    override suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit> = fail()
    override suspend fun lockedThrough(): ZillitResult<String?> = ZillitResult.Success(lock)
    override suspend fun companies(): ZillitResult<List<CashCompany>> = ZillitResult.Success(emptyList())
    override suspend fun fundRequests(): ZillitResult<List<FundRequest>> = ZillitResult.Success(emptyList())
    override suspend fun createFundRequest(fundAccount: String, currency: String?, amount: Double): ZillitResult<Unit> =
        write("createFunds")

    override suspend fun receiveFundRequest(id: String): ZillitResult<Unit> = write("receiveFunds:$id")
    override suspend fun cancelFundRequest(id: String): ZillitResult<Unit> = write("cancelFunds:$id")
    override suspend fun queryThread(batchId: String): ZillitResult<QueryThread> =
        ZillitResult.Success(QueryThread(null))
    override suspend fun sendQuery(batchId: String, threadId: String?, text: String): ZillitResult<QueryThread> = fail()
    override suspend fun exportFloats(format: ExportFormat): ZillitResult<ByteArray> = fail()
    override suspend fun exportReceipts(
        format: ExportFormat,
        expenseType: ExpenseType?,
        historyOnly: Boolean,
    ): ZillitResult<ByteArray> = fail()

    /** One receipt awaiting coding, already carrying a cost code so its seeded line balances. */
    fun queuedBatch(
        id: String = "b1",
        status: BatchStatus = BatchStatus.Coding,
        assignedTo: String? = null,
    ) = ClaimBatch(
        id = id,
        reference = "PC-001",
        userId = "u2",
        holderName = "Sam Grip",
        departmentId = "grip",
        status = status,
        expenseType = ExpenseType.PettyCash,
        claimCount = 1,
        totalGross = 100.0,
        reimbursementAmount = 0.0,
        currency = "GBP",
        settlementType = null,
        paymentMethod = null,
        notes = null,
        assignedTo = assignedTo,
        assignedBy = null,
        assignmentReason = null,
        createdAt = null,
        claims = listOf(
            Claim(
                id = "c1",
                batchId = id,
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
                status = status,
                receiptUrl = null,
            ),
        ),
    )
}
