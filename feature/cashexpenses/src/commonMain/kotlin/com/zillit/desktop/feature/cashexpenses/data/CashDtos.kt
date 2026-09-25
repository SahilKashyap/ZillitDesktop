package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashStats
import com.zillit.desktop.feature.cashexpenses.domain.CashSummary
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CategorySpend
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRule
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentStats
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.QuickCode
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.domain.RoutingSplit
import com.zillit.desktop.feature.cashexpenses.domain.Denomination
import com.zillit.desktop.feature.cashexpenses.domain.ReconItem
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The cash service's wire types.
 *
 * ## Everything is a nullable String
 *
 * Amounts, dates and ids all arrive as *either* a JSON number or a JSON string
 * depending on the column type behind them — Postgres `numeric` serialises as a
 * string through this stack, `integer` does not — and the same field differs
 * between the list endpoint and the detail endpoint. Typing them as `Double`
 * therefore fails to parse real responses, which is how a queue ends up empty
 * with no error.
 *
 * They are read here through [toAmount] / [toEpochMillisOrNull] into the domain
 * types, so exactly one layer deals with it.
 */
@Serializable
internal data class FloatDto(
    @SerialName("id") val id: String? = null,
    @SerialName("req_number") val reqNumber: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("req_amount") val reqAmount: String? = null,
    @SerialName("issued_float") val issuedFloat: String? = null,
    @SerialName("balance") val balance: String? = null,
    @SerialName("receipts_amount") val receiptsAmount: String? = null,
    // Plural on cash, singular on card. Both read — see CashFloat.receiptsCommits.
    @SerialName("receipts_commits") val receiptsCommits: String? = null,
    @SerialName("receipts_commit") val receiptsCommit: String? = null,
    @SerialName("return_amount") val returnAmount: String? = null,
    @SerialName("bs_code") val bsCode: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("duration_type") val durationType: String? = null,
    @SerialName("purpose") val purpose: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** An array, or a string holding one — see [readApprovals]. */
    @SerialName("approvals") val approvals: JsonElement? = null,
    // The record's currency when `currency` is absent — `resolveCashCurrency`.
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("transaction_currency") val transactionCurrency: String? = null,
    @SerialName("collect_date") val collectDate: String? = null,
    @SerialName("collect_time") val collectTime: String? = null,
    @SerialName("collection_method") val collectionMethod: String? = null,
    /** `[{section, fields: [...]}]`, as an array or a string holding one. */
    @SerialName("custom_fields") val customFields: JsonElement? = null,
    @SerialName("activated_at") val activatedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("spent") val spent: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("episode") val episode: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
) {
    fun toDomain(): CashFloat? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return CashFloat(
            id = identifier,
            requestNumber = reqNumber.orEmpty(),
            userId = userId.orEmpty(),
            holderName = holderName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            departmentId = departmentId,
            status = FloatStatus.from(status),
            currency = currency ?: transactionCurrency,
            requestedAmount = reqAmount.toAmount(),
            issuedAmount = issuedFloat.toAmount(),
            balance = balance.toAmount(),
            receiptsAmount = receiptsAmount.toAmount(),
            receiptsCommits = receiptsCommits.toAmountOrNull() ?: receiptsCommit.toAmountOrNull(),
            returnAmount = returnAmount.toAmount(),
            bsCode = bsCode,
            companyId = companyId,
            duration = duration,
            durationType = durationType,
            purpose = purpose,
            createdAt = createdAt.toEpochMillisOrNull(),
            approvals = approvals.readApprovals(),
            collectDate = collectDate.toEpochMillisOrNull(),
            collectTime = collectTime?.takeIf { it.isNotBlank() },
            collectionMethod = collectionMethod?.takeIf { it.isNotBlank() },
            customFields = customFields.readCustomFields(),
            activatedAt = activatedAt.toEpochMillisOrNull(),
            closedAt = closedAt.toEpochMillisOrNull(),
            reportedSpent = spent.toAmountOrNull(),
            companyName = companyName?.takeIf { it.isNotBlank() },
            episode = episode?.takeIf { it.isNotBlank() },
            createdBy = createdBy?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class BatchDto(
    @SerialName("id") val id: String? = null,
    @SerialName("batch_reference") val reference: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("batch_status") val batchStatus: String? = null,
    @SerialName("expense_type") val expenseType: String? = null,
    @SerialName("claim_count") val claimCount: Int? = null,
    @SerialName("total_gross") val totalGross: String? = null,
    @SerialName("reimbursement_amount") val reimbursementAmount: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("settlement_type") val settlementType: String? = null,
    /** JSON, sometimes as an object and sometimes as a string holding one. */
    @SerialName("settlement_details") val settlementDetails: JsonElement? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("assigned_by") val assignedBy: String? = null,
    @SerialName("assignment_reason") val assignmentReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("claims") val claims: List<ClaimDto>? = null,
    @SerialName("effective_date") val effectiveDate: String? = null,
    @SerialName("escalation_reason") val escalationReason: String? = null,
    @SerialName("escalated_by") val escalatedBy: String? = null,
    @SerialName("approvals") val approvals: JsonElement? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("transaction_currency") val transactionCurrency: String? = null,
    @SerialName("escalated_at") val escalatedAt: String? = null,
    @SerialName("posted_at") val postedAt: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("rejected_by") val rejectedBy: String? = null,
    @SerialName("rejected_at") val rejectedAt: String? = null,
    @SerialName("query_reason") val queryReason: String? = null,
    @SerialName("float_request_id") val floatRequestId: String? = null,
) {
    fun toDomain(): ClaimBatch? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return ClaimBatch(
            id = identifier,
            reference = reference.orEmpty(),
            userId = userId.orEmpty(),
            holderName = holderName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            departmentId = departmentId,
            // `status` on the batch endpoints, `batch_status` when a batch is
            // flattened onto its claims. Neither is always present.
            status = BatchStatus.from(status ?: batchStatus),
            expenseType = ExpenseType.from(expenseType),
            claimCount = claimCount ?: claims?.size ?: 0,
            totalGross = totalGross.toAmount(),
            reimbursementAmount = reimbursementAmount.toAmount(),
            currency = currency ?: transactionCurrency,
            settlementType = settlementType,
            paymentMethod = settlementDetails.readObject()?.get(PAYMENT_METHOD)
                ?.jsonPrimitiveOrNull()?.contentOrNull(),
            notes = notes,
            assignedTo = assignedTo,
            assignedBy = assignedBy,
            assignmentReason = assignmentReason,
            createdAt = createdAt.toEpochMillisOrNull(),
            claims = claims.orEmpty().mapNotNull { it.toDomain() },
            effectiveDate = effectiveDate.toEpochMillisOrNull(),
            escalationReason = escalationReason?.takeIf { it.isNotBlank() },
            escalatedBy = escalatedBy?.takeIf { it.isNotBlank() },
            approvals = approvals.readApprovals(),
            escalatedAt = escalatedAt.toEpochMillisOrNull(),
            postedAt = postedAt.toEpochMillisOrNull(),
            rejectionReason = rejectionReason?.takeIf { it.isNotBlank() },
            rejectedBy = rejectedBy?.takeIf { it.isNotBlank() },
            rejectedAt = rejectedAt.toEpochMillisOrNull(),
            queryReason = queryReason?.takeIf { it.isNotBlank() },
            floatRequestId = floatRequestId?.takeIf { it.isNotBlank() },
            followUp = settlementDetails.readObject()?.get(FOLLOW_UP)?.jsonPrimitiveOrNull()?.contentOrNull(),
            settlementDetails = settlementDetails.readObject(),
        )
    }
}

@Serializable
internal data class ClaimDto(
    @SerialName("id") val id: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("supplier") val supplier: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("cost_code") val costCode: String? = null,
    @SerialName("coded_description") val codedDescription: String? = null,
    @SerialName("episode") val episode: String? = null,
    @SerialName("receipt_date") val receiptDate: String? = null,
    @SerialName("gross_amount") val grossAmount: String? = null,
    @SerialName("net_amount") val netAmount: String? = null,
    @SerialName("vat_amount") val vatAmount: String? = null,
    @SerialName("tax_rate") val taxRate: String? = null,
    @SerialName("tax_type") val taxType: String? = null,
    @SerialName("settlement_type") val settlementType: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("batch_status") val batchStatus: String? = null,
    @SerialName("receipt_url") val receiptUrl: String? = null,
    @SerialName("line_items") val lineItems: JsonElement? = null,
    @SerialName("is_verified") val isVerified: JsonElement? = null,
    /** Strings or `{flag}` objects, in an array or a string holding one. */
    @SerialName("processing_flags") val processingFlags: JsonElement? = null,
    /** The uploaded file object, or a string holding one — see [readAttachment]. */
    @SerialName("attachment") val attachment: JsonElement? = null,
    @SerialName("deduction_amount") val deductionAmount: String? = null,
    @SerialName("batch_reference") val batchReference: String? = null,
) {
    fun toDomain(): Claim? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return Claim(
            id = identifier,
            batchId = batchId,
            description = description.orEmpty(),
            supplier = supplier,
            category = category,
            costCode = costCode,
            codedDescription = codedDescription,
            episode = episode,
            receiptDate = receiptDate.toEpochMillisOrNull(),
            grossAmount = grossAmount.toAmount(),
            netAmount = netAmount.toAmount(),
            vatAmount = vatAmount.toAmount(),
            taxRate = taxRate.toAmountOrNull(),
            taxType = taxType,
            settlementType = settlementType,
            status = BatchStatus.from(status ?: batchStatus),
            receiptUrl = receiptUrl,
            lineItems = lineItems.readLineItems(),
            isVerified = isVerified.isTrue(),
            processingFlags = processingFlags.readFlags(),
            rawLines = lineItems.readRawLines(),
            attachment = attachment.readAttachment(),
            deductionAmount = deductionAmount.toAmount(),
            batchReference = batchReference?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class LineItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("account") val account: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("total") val total: String? = null,
    @SerialName("tax_rate") val taxRate: String? = null,
    @SerialName("quantity") val quantity: String? = null,
    @SerialName("unit_price") val unitPrice: String? = null,
    @SerialName("tax_type") val taxType: String? = null,
    @SerialName("split_parent_id") val splitParentId: String? = null,
    @SerialName("meta") val meta: JsonElement? = null,
    @SerialName("is_tax") val isTax: JsonElement? = null,
    @SerialName("tax_amount") val taxAmount: String? = null,
    @SerialName("tracking_codes") val trackingCodes: JsonElement? = null,
    @SerialName("tags") val tags: JsonElement? = null,
    @SerialName("rental_start") val rentalStart: String? = null,
    @SerialName("rental_end") val rentalEnd: String? = null,
    @SerialName("sort_order") val sortOrder: String? = null,
    @SerialName("expenditure_type") val expenditureType: String? = null,
) {
    /**
     * Whether the processing-rules engine owns this row.
     *
     * Two independent marks, because the first one gets lost: `meta.auto` is
     * the tag the server writes, and the reserved `DEDUCT` account is what
     * remains after a client round-trip has dropped the meta blob. Real cost
     * codes are numeric, so there is no collision. Missing the second mark is
     * how the web ended up rendering the same deduction three times.
     */
    fun isAutoDeduction(): Boolean {
        val tagged = meta.readObject()?.get(AUTO)?.jsonPrimitiveOrNull()?.content == "true"
        return tagged || account == DEDUCT_ACCOUNT
    }

    fun toDomain(): ClaimLineItem = ClaimLineItem(
        id = id,
        account = account,
        description = description.orEmpty(),
        total = total.toAmount(),
        taxRate = taxRate.toAmountOrNull(),
        autoDeduction = isAutoDeduction(),
        // A line with no quantity is one of something, not none.
        quantity = quantity.toAmountOrNull() ?: 1.0,
        unitPrice = unitPrice.toAmount(),
        taxType = taxType,
        splitParentId = splitParentId,
        isTax = isTax.isTrue(),
        taxAmount = taxAmount.toAmountOrNull(),
        trackingCodes = trackingCodes?.takeUnless { it is JsonNull },
        tags = tags?.takeUnless { it is JsonNull },
        rentalStart = rentalStart?.takeIf { it.isNotBlank() },
        rentalEnd = rentalEnd?.takeIf { it.isNotBlank() },
        sortOrder = sortOrder?.toDoubleOrNull()?.toInt(),
        expenditureType = expenditureType?.takeIf { it.isNotBlank() },
    )
}

@Serializable
internal data class MetadataDto(
    @SerialName("is_approver") val isApprover: Boolean? = null,
    @SerialName("is_coordinator") val isCoordinator: Boolean? = null,
    @SerialName("is_senior") val isSenior: Boolean? = null,
    @SerialName("is_team_member") val isTeamMember: Boolean? = null,
    @SerialName("require_senior_sign_off") val requireSeniorSignOff: Boolean? = null,
    @SerialName("coding_required") val codingRequired: Boolean? = null,
    @SerialName("view_department_floats") val viewDepartmentFloats: Boolean? = null,
    @SerialName("can_override") val canOverride: Boolean? = null,
    @SerialName("override_float_req") val overrideFloatRequest: Boolean? = null,
    @SerialName("override_receipt_batch") val overrideReceiptBatch: Boolean? = null,
    /** A number, `null` (unlimited), or absent (no grant) — three answers, so read raw. */
    @SerialName("posting_limit") val postingLimit: JsonElement? = null,
    @SerialName("approval_tier_configs") val approvalTierConfigs: JsonElement? = null,
    /** `{enabled, basis, max_amount, salary_multiplier}`, or a string holding it. */
    @SerialName("request_cap") val requestCap: JsonElement? = null,
) {
    // Every flag defaults to false: a right the server did not mention is one
    // this person does not have.
    fun toDomain() = CashMetadata(
        isApprover = isApprover == true,
        isCoordinator = isCoordinator == true,
        isSenior = isSenior == true,
        isTeamMember = isTeamMember == true,
        requireSeniorSignOff = requireSeniorSignOff == true,
        codingRequired = codingRequired == true,
        viewDepartmentFloats = viewDepartmentFloats == true,
        canOverride = canOverride == true,
        overrideFloatRequest = overrideFloatRequest == true,
        overrideReceiptBatch = overrideReceiptBatch == true,
        postingLimit = (postingLimit as? JsonPrimitive)?.contentOrNull().toAmountOrNull(),
        // Whether the limit was stated as null is read off the raw body — see
        // CashRepositoryImpl.metadata; a nullable field cannot say.
        approvalTierConfigs = approvalTierConfigs.readTierConfigs(),
        requestCap = requestCap.readObject()?.toRequestCap(),
    )
}

@Serializable
internal data class TopUpDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("amount") val amount: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("float_req_number") val floatReqNumber: String? = null,
    @SerialName("float_issued") val floatIssued: String? = null,
    @SerialName("float_balance") val floatBalance: String? = null,
    @SerialName("float_req_amount") val floatReqAmount: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** JSON-stringified on this route (the BE's own note), an array elsewhere. */
    @SerialName("history") val history: JsonElement? = null,
    @SerialName("method") val method: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("issued_amount") val issuedAmount: String? = null,
    @SerialName("float_request_id") val floatRequestId: String? = null,
) {
    fun toDomain(): CashTopUp? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return CashTopUp(
            id = identifier,
            userId = userId.orEmpty(),
            holderName = holderName.orEmpty(),
            amount = amount.toAmount(),
            currency = currency,
            status = status?.lowercase().orEmpty(),
            note = note ?: reason,
            floatRequestNumber = floatReqNumber,
            floatIssued = floatIssued.toAmount(),
            floatBalance = floatBalance.toAmount(),
            floatRequestedAmount = floatReqAmount.toAmount(),
            createdAt = createdAt.toEpochMillisOrNull(),
            history = history.readTopUpHistory(),
            method = method?.takeIf { it.isNotBlank() },
            updatedAt = updatedAt.toEpochMillisOrNull(),
            issuedAmount = issuedAmount.toAmountOrNull(),
            floatRequestId = floatRequestId?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class HistoryDto(
    @SerialName("action") val action: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("timestamp") val timestamp: String? = null,
) {
    fun toDomain() = CashHistoryEntry(
        action = action ?: status.orEmpty(),
        userId = userId,
        note = note ?: reason,
        at = (createdAt ?: timestamp).toEpochMillisOrNull(),
    )
}

@Serializable
internal data class PettyCashOverviewDto(
    @SerialName("stats") val stats: StatsDto? = null,
    @SerialName("summary") val summary: SummaryDto? = null,
    @SerialName("floats") val floats: List<FloatDto>? = null,
) {
    fun toDomain() = PettyCashOverview(
        stats = stats?.toDomain() ?: CashStats(),
        summary = summary?.toDomain() ?: CashSummary(),
        floats = floats.orEmpty().mapNotNull { it.toDomain() },
    )
}

@Serializable
internal data class StatsDto(
    @SerialName("active_floats") val activeFloats: Int? = null,
    @SerialName("awaiting_audit") val awaitingAudit: Int? = null,
    @SerialName("awaiting_approval") val awaitingApproval: Int? = null,
    @SerialName("ready_to_post") val readyToPost: Int? = null,
    @SerialName("ready_to_post_amount") val readyToPostAmount: String? = null,
    @SerialName("escalated") val escalated: Int? = null,
    @SerialName("total_outstanding") val totalOutstanding: String? = null,
) {
    fun toDomain() = CashStats(
        activeFloats = activeFloats ?: 0,
        awaitingAudit = awaitingAudit ?: 0,
        awaitingApproval = awaitingApproval ?: 0,
        readyToPost = readyToPost ?: 0,
        readyToPostAmount = readyToPostAmount.toAmount(),
        escalated = escalated ?: 0,
        totalOutstanding = totalOutstanding.toAmount(),
    )
}

@Serializable
internal data class SummaryDto(
    @SerialName("total_pc_issued") val totalPettyCashIssued: String? = null,
    @SerialName("total_receipts_approved") val totalReceiptsApproved: String? = null,
    @SerialName("cash_to_account") val cashToAccount: String? = null,
    @SerialName("vat_recoverable") val vatRecoverable: String? = null,
    @SerialName("oop_bacs_queued") val oopBacsQueued: String? = null,
    @SerialName("oop_payroll_additions") val oopPayrollAdditions: String? = null,
) {
    fun toDomain() = CashSummary(
        totalPettyCashIssued = totalPettyCashIssued.toAmount(),
        totalReceiptsApproved = totalReceiptsApproved.toAmount(),
        cashToAccount = cashToAccount.toAmount(),
        vatRecoverable = vatRecoverable.toAmount(),
        oopBacsQueued = oopBacsQueued.toAmount(),
        oopPayrollAdditions = oopPayrollAdditions.toAmount(),
    )
}

@Serializable
internal data class OopOverviewDto(
    @SerialName("stats") val stats: OopStatsDto? = null,
    @SerialName("routing") val routing: RoutingDto? = null,
    @SerialName("spend_by_category") val spendByCategory: List<CategorySpendDto>? = null,
    @SerialName("batches") val batches: List<BatchDto>? = null,
) {
    fun toDomain(): OutOfPocketOverview {
        val rows = batches.orEmpty().mapNotNull { it.toDomain() }
        return OutOfPocketOverview(
            pendingClaims = stats?.pendingClaims ?: 0,
            totalClaimed = stats?.totalClaimed.toAmount(),
            bacsReady = stats?.bacsReady.toAmount(),
            payrollAuto = stats?.payrollAuto.toAmount(),
            routing = RoutingSplit(
                bacs = routing?.bacs.toAmount(),
                payroll = routing?.payroll.toAmount(),
                total = routing?.total.toAmount(),
            ),
            spendByCategory = spendByCategory.orEmpty().map {
                CategorySpend(it.category.orEmpty(), it.amount.toAmount())
            },
            batches = rows,
        )
    }
}

@Serializable
internal data class OopStatsDto(
    @SerialName("pending_claims") val pendingClaims: Int? = null,
    @SerialName("total_claimed") val totalClaimed: String? = null,
    @SerialName("bacs_ready") val bacsReady: String? = null,
    @SerialName("payroll_auto") val payrollAuto: String? = null,
)

/** The out-of-pocket dashboard's `routing` split. */
@Serializable
internal data class RoutingDto(
    @SerialName("bacs") val bacs: String? = null,
    @SerialName("payroll") val payroll: String? = null,
    @SerialName("total") val total: String? = null,
)

/**
 * `GET /claims/overview/payment-routing` — `stats` and the two batch lists.
 *
 * Read as `bacs`/`payroll`/`total` until now, none of which the route sends:
 * every tile on Payment Routing said zero (`OOPPaymentPage.jsx:191-195`).
 */
@Serializable
internal data class PaymentRoutingDto(
    @SerialName("stats") val stats: PaymentRoutingStatsDto? = null,
    @SerialName("bacs_batches") val bacsBatches: List<BatchDto>? = null,
    @SerialName("payroll_batches") val payrollBatches: List<BatchDto>? = null,
) {
    fun toDomain() = PaymentRouting(
        bacsReady = stats?.bacsReady.toAmount(),
        bacsCount = stats?.bacsCount ?: 0,
        payrollTotal = stats?.payrollTotal.toAmount(),
        payrollCount = stats?.payrollCount ?: 0,
        bacsBatches = bacsBatches.orEmpty().mapNotNull { it.toDomain() },
        payrollBatches = payrollBatches.orEmpty().mapNotNull { it.toDomain() },
    )
}

@Serializable
internal data class PaymentRoutingStatsDto(
    @SerialName("bacs_ready") val bacsReady: String? = null,
    @SerialName("bacs_count") val bacsCount: Int? = null,
    @SerialName("payroll_total") val payrollTotal: String? = null,
    @SerialName("payroll_count") val payrollCount: Int? = null,
)

@Serializable
internal data class CategorySpendDto(
    @SerialName("category") val category: String? = null,
    @SerialName("amount") val amount: String? = null,
)

@Serializable
internal data class MyOverviewDto(
    // Receipt rows with their batch's reference and status, not batches —
    // see RecentClaimDto (`PCCrewOverviewPage.jsx:89-111`).
    @SerialName("pc_claims") val pettyCashClaims: List<RecentClaimDto>? = null,
    @SerialName("oop_claims") val outOfPocketClaims: List<RecentClaimDto>? = null,
    @SerialName("floats") val floats: List<FloatDto>? = null,
) {
    fun toDomain() = MyCashOverview(
        pettyCashClaims = pettyCashClaims.orEmpty().mapNotNull { it.toDomain() },
        outOfPocketClaims = outOfPocketClaims.orEmpty().mapNotNull { it.toDomain() },
        floats = floats.orEmpty().mapNotNull { it.toDomain() },
    )
}

/** `floats`, `oop_batches`, `stats`, `spend_by_category` — the keys the web reads (`PCDeptViewPage.jsx:44`). */
@Serializable
internal data class DepartmentOverviewDto(
    @SerialName("floats") val floats: List<FloatDto>? = null,
    @SerialName("oop_batches") val outOfPocketBatches: List<BatchDto>? = null,
    @SerialName("stats") val stats: DepartmentStatsDto? = null,
    @SerialName("spend_by_category") val spendByCategory: List<DepartmentCategoryDto>? = null,
) {
    fun toDomain(departmentId: String?) = DepartmentOverview(
        departmentId = departmentId,
        floats = floats.orEmpty().mapNotNull { it.toDomain() },
        outOfPocketBatches = outOfPocketBatches.orEmpty().mapNotNull { it.toDomain() },
        stats = stats?.toDomain() ?: DepartmentStats(),
        spendByCategory = spendByCategory.orEmpty().mapNotNull { it.toDomain() },
    )
}

/**
 * One reconciliation, as the web writes it.
 *
 * `opening_safe_balance`, `physical_cash`, `variance`, `denominations` and
 * `reconciling_items`. The desktop read `counted_balance` and `note`, which
 * nothing writes, so every count came back as zero; both are kept as
 * fallbacks for any row that carries them.
 */
@Serializable
internal data class ReconciliationDto(
    @SerialName("id") val id: String? = null,
    @SerialName("reference") val reference: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    @SerialName("book_balance") val bookBalance: String? = null,
    @SerialName("opening_safe_balance") val openingBalance: String? = null,
    @SerialName("physical_cash") val physicalCash: String? = null,
    @SerialName("counted_balance") val legacyCounted: String? = null,
    @SerialName("variance") val variance: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("note") val legacyNote: String? = null,
    @SerialName("denominations") val denominations: JsonElement? = null,
    @SerialName("reconciling_items") val reconcilingItems: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("submitted_by") val submittedBy: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("signed_by") val signedBy: String? = null,
    @SerialName("signed_at") val signedAt: String? = null,
) {
    fun toDomain(): Reconciliation? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return Reconciliation(
            id = identifier,
            reference = reference,
            status = status?.uppercase().orEmpty(),
            periodStart = periodStart.toEpochMillisOrNull(),
            periodEnd = periodEnd.toEpochMillisOrNull(),
            bookBalance = bookBalance.toAmount(),
            countedBalance = (physicalCash ?: legacyCounted).toAmount(),
            currency = currency,
            note = notes ?: legacyNote,
            createdAt = createdAt.toEpochMillisOrNull(),
            openingBalance = openingBalance.toAmount(),
            storedVariance = variance.toAmountOrNull(),
            denominations = denominations.readList(DenominationDto.serializer()).map { it.toDomain() },
            reconcilingItems = reconcilingItems.readList(ReconItemDto.serializer()).map { it.toDomain() },
            createdBy = createdBy?.takeIf { it.isNotBlank() },
            updatedBy = updatedBy?.takeIf { it.isNotBlank() },
            updatedAt = updatedAt.toEpochMillisOrNull(),
            submittedBy = submittedBy?.takeIf { it.isNotBlank() },
            submittedAt = submittedAt.toEpochMillisOrNull(),
            signedBy = signedBy?.takeIf { it.isNotBlank() },
            signedAt = signedAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class DenominationDto(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("count") val count: String? = null,
) {
    fun toDomain() = Denomination(
        id = id.orEmpty().ifBlank { "${type.orEmpty()}_${value.orEmpty()}" },
        type = type.orEmpty(),
        value = value.orEmpty(),
        count = count.orEmpty(),
    )
}

@Serializable
internal data class ReconItemDto(
    @SerialName("desc") val description: String? = null,
    @SerialName("ref") val reference: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("amount") val amount: String? = null,
) {
    fun toDomain() = ReconItem(
        description = description.orEmpty(),
        reference = reference.orEmpty(),
        type = type?.takeIf { it.isNotBlank() } ?: ReconItem.OUT,
        amount = amount.orEmpty(),
    )
}

@Serializable
internal data class BookBalanceDto(
    @SerialName("book_balance") val bookBalance: String? = null,
    @SerialName("balance") val balance: String? = null,
)

@Serializable
internal data class SettingsDto(
    @SerialName("float_custodian_account") val custodianAccount: String? = null,
    @SerialName("bs_code_from") val bsCodeFrom: String? = null,
    @SerialName("bs_code_to") val bsCodeTo: String? = null,
    /** A JSON object, or a string holding one. See [readObject]. */
    @SerialName("approval_override") val approvalOverride: JsonElement? = null,
    @SerialName("team_members") val teamMembers: JsonElement? = null,
    @SerialName("quick_codes") val quickCodes: JsonElement? = null,
    @SerialName("reimburse_to_payroll") val reimburseToPayroll: Boolean? = null,
    @SerialName("deduction_rules") val deductionRules: JsonElement? = null,
    @SerialName("request_cap") val requestCap: JsonElement? = null,
    @SerialName("assignment_rules") val assignmentRules: JsonElement? = null,
    /** An array, or a string holding one — the web's `parseJsonField`. */
    @SerialName("department_coordinators") val departmentCoordinators: JsonElement? = null,
) {
    fun toDomain(): CashSettings {
        val overrides = approvalOverride.readObject()
        return CashSettings(
            custodianAccount = custodianAccount.orEmpty(),
            bsCodeFrom = bsCodeFrom.orEmpty(),
            bsCodeTo = bsCodeTo.orEmpty(),
            overrideFloatRequest = overrides.flag("override_float_req"),
            overrideReceiptBatch = overrides.flag("override_receipt_batch"),
            requireCoordinatorCoding = overrides.flag("require_coord_code"),
            requireSeniorSignOff = overrides.flag("require_senior_sign_off"),
            teamMembers = teamMembers.readList(TeamMemberDto.serializer()).map { it.toDomain() },
            quickCodes = quickCodes.readList(QuickCodeDto.serializer()).map { it.toDomain() },
            reimburseToPayroll = reimburseToPayroll == true,
            deductionRules = deductionRules.readList(DeductionRuleDto.serializer()).map { it.toDomain() },
            requestCap = requestCap.readObject().toRequestCap(),
            assignmentRules = assignmentRules.readList(CashRuleDto.serializer()).mapNotNull { it.toDomain() },
            departmentCoordinators = departmentCoordinators.readList(CoordinatorDto.serializer())
                .mapNotNull { it.toDomain() },
        )
    }
}

@Serializable
internal data class TeamMemberDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("is_senior") val isSenior: Boolean? = null,
    @SerialName("can_override") val canOverride: Boolean? = null,
    @SerialName("posting_limit") val postingLimit: String? = null,
) {
    fun toDomain() = CashTeamMember(
        userId = userId.orEmpty(),
        name = name?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
        isSenior = isSenior == true,
        canOverride = canOverride == true,
        postingLimit = postingLimit.toAmountOrNull(),
    )

    companion object {
        /** `{user_id, posting_limit, can_override, is_senior}` — a null limit is unlimited. */
        fun of(member: CashTeamMember): JsonObject = buildJsonObject {
            put("user_id", JsonPrimitive(member.userId))
            put("posting_limit", member.postingLimit?.let(::JsonPrimitive) ?: JsonNull)
            put("can_override", JsonPrimitive(member.canOverride))
            put("is_senior", JsonPrimitive(member.isSenior))
        }
    }
}

/**
 * A quick code as the web writes it.
 *
 * `name` and `nominal_code`, not `code`/`label` — those were read here for a
 * year and never written by anything, so a production configured on the web
 * arrived with an empty list. The older spellings are still accepted as
 * fallbacks in case some row somewhere carries them.
 */
@Serializable
internal data class QuickCodeDto(
    @SerialName("name") val name: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("keywords") val keywords: List<String>? = null,
    @SerialName("vat") val vat: JsonPrimitive? = null,
    @SerialName("code") val legacyCode: String? = null,
    @SerialName("label") val legacyLabel: String? = null,
) {
    fun toDomain() = QuickCode(
        name = name?.takeIf { it.isNotBlank() }
            ?: legacyLabel?.takeIf { it.isNotBlank() }
            ?: legacyCode.orEmpty(),
        nominalCode = nominalCode?.takeIf { it.isNotBlank() } ?: legacyCode.orEmpty(),
        keywords = keywords.orEmpty(),
        vat = vat?.contentOrNull?.toDoubleOrNull(),
    )

    companion object {
        fun of(code: QuickCode): JsonObject = buildJsonObject {
            put("name", JsonPrimitive(code.name))
            put("nominal_code", JsonPrimitive(code.nominalCode))
            put("keywords", buildJsonArray { code.keywords.forEach { add(JsonPrimitive(it)) } })
            put("vat", code.vat?.let(::JsonPrimitive) ?: JsonPrimitive(0))
        }
    }
}

/** One deduction rule, in the shape the web stores. */
@Serializable
internal data class DeductionRuleDto(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("process_type") val processType: String? = null,
    @SerialName("threshold_type") val thresholdType: String? = null,
    @SerialName("threshold_value") val thresholdValue: JsonPrimitive? = null,
    /** The web spells the on/off flag `enable`, not `enabled`. */
    @SerialName("enable") val enable: Boolean? = null,
    @SerialName("trigger_codes") val triggerCodes: List<String>? = null,
    @SerialName("system_default") val systemDefault: Boolean? = null,
) {
    fun toDomain() = DeductionRule(
        id = id.orEmpty().ifBlank { type.orEmpty() },
        title = title.orEmpty(),
        description = description.orEmpty(),
        processType = RuleProcess.from(processType),
        thresholdType = RuleThreshold.from(thresholdType),
        thresholdValue = thresholdValue?.contentOrNull?.toDoubleOrNull() ?: 0.0,
        enabled = enable == true,
        triggerCodes = triggerCodes.orEmpty(),
        systemDefault = systemDefault == true,
        type = type.orEmpty(),
    )

    companion object {
        fun of(rule: DeductionRule): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(rule.id))
            put("type", JsonPrimitive(rule.type.ifBlank { rule.id }))
            put("title", JsonPrimitive(rule.title))
            put("description", JsonPrimitive(rule.description))
            put("process_type", JsonPrimitive(rule.processType.wire))
            put("threshold_type", JsonPrimitive(rule.thresholdType.wire))
            put("threshold_value", JsonPrimitive(rule.thresholdValue))
            put("enable", JsonPrimitive(rule.enabled))
            put("trigger_codes", buildJsonArray { rule.triggerCodes.forEach { add(JsonPrimitive(it)) } })
            put("system_default", JsonPrimitive(rule.systemDefault))
        }
    }
}

// -- tolerant JSON reading ---------------------------------------------------
//
// The cash service stores several columns as JSONB and several as TEXT holding
// JSON, and which is which differs by endpoint and by how old the row is. Every
// one of these helpers therefore accepts both shapes and returns an empty
// answer rather than throwing: a malformed settlement blob must not take down
// the queue that shows it.

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun JsonElement?.readObject(): JsonObject? = when {
    this == null -> null
    this is JsonObject -> this
    this is JsonPrimitive && isString -> runCatching {
        lenientJson.parseToJsonElement(content).jsonObject
    }.getOrNull()

    else -> null
}

internal fun <T> JsonElement?.readList(serializer: kotlinx.serialization.KSerializer<T>): List<T> {
    val element = when {
        this == null -> return emptyList()
        this is JsonPrimitive && isString -> runCatching {
            lenientJson.parseToJsonElement(content)
        }.getOrNull() ?: return emptyList()

        else -> this
    }
    return runCatching {
        lenientJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(serializer), element)
    }.getOrElse { emptyList() }
}

internal fun JsonElement?.readLineItems(): List<ClaimLineItem> =
    readList(LineItemDto.serializer())
        .map { it.toDomain() }
        .dedupeAutoDeductions()

/**
 * Collapses duplicated engine-generated deduction rows for display.
 *
 * A de-tagged copy (its `meta` lost to an earlier round-trip) and the freshly
 * inserted tagged copy carry the same account, description and amount, so the
 * signature keys on those three — not on the rule id, which the de-tagged copy
 * no longer has. This heals an already-polluted claim without needing it
 * re-saved; [LineItemDto.isAutoDeduction] stops it happening again.
 */
private fun List<ClaimLineItem>.dedupeAutoDeductions(): List<ClaimLineItem> {
    val seen = mutableSetOf<String>()
    return filter { line ->
        if (!line.autoDeduction) return@filter true
        seen.add("${line.account}|${line.description}|${line.total}")
    }
}

private fun JsonObject?.flag(key: String): Boolean =
    this?.get(key)?.jsonPrimitiveOrNull()?.content == "true"

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = runCatching { jsonPrimitive }.getOrNull()

private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it.isNotBlank() && it != "null" }

private const val PAYMENT_METHOD = "payment_method"
private const val FOLLOW_UP = "follow_up"
private const val AUTO = "auto"
private const val DEDUCT_ACCOUNT = "DEDUCT"
