package com.zillit.desktop.feature.cashexpenses.domain

/**
 * A petty-cash float: an amount of cash issued to one crew member, spent
 * against, and eventually returned or closed.
 *
 * Amounts are `Double` rather than a decimal type because the backend sends
 * them as JSON numbers and every downstream use is display or comparison. The
 * one place arithmetic decides an outcome — the reimburse/reduce split — is
 * [FloatSettlement], which is pinned by tests.
 */
data class CashFloat(
    val id: String,
    val requestNumber: String,
    val userId: String,
    val holderName: String,
    val departmentId: String?,
    val status: FloatStatus,
    val currency: String?,
    /** What was asked for. */
    val requestedAmount: Double,
    /** What was actually handed over. Zero until the float is issued. */
    val issuedAmount: Double,
    /** Cash still in hand, as the server last computed it. */
    val balance: Double,
    /** Total of receipts submitted against this float. */
    val receiptsAmount: Double,
    /**
     * Receipts committed against the float — posted *and* pending.
     *
     * Plural on the wire for cash and singular for card; both spellings are
     * read (see the DTO). Null when the backend did not send it, which the
     * settlement maths treats as "no commitment known" rather than zero — see
     * [FloatSettlement].
     */
    val receiptsCommits: Double?,
    val returnAmount: Double,
    val bsCode: String?,
    val companyId: String?,
    val duration: String?,
    val durationType: String?,
    val purpose: String?,
    val createdAt: Long?,
) {
    /** Cash spent so far, as the difference the crew member actually sees. */
    val spent: Double get() = (issuedAmount - balance).coerceAtLeast(0.0)

    /** How much of the issued float has been used, 0..1. Zero-safe. */
    val consumedFraction: Float
        get() = if (issuedAmount <= 0) 0f else (spent / issuedAmount).toFloat().coerceIn(0f, 1f)
}

/**
 * A batch of receipts submitted together.
 *
 * The cash service batches on purpose: crew submit a handful of receipts at
 * once and the whole batch moves through coding, audit and approval as one
 * unit. Nothing in this module approves an individual [Claim] — the batch is
 * the thing that has a status.
 */
data class ClaimBatch(
    val id: String,
    val reference: String,
    val userId: String,
    val holderName: String,
    val departmentId: String?,
    val status: BatchStatus,
    val expenseType: ExpenseType,
    val claimCount: Int,
    val totalGross: Double,
    /** What is owed back to the submitter, when the batch is a reimbursement. */
    val reimbursementAmount: Double,
    val currency: String?,
    val settlementType: String?,
    /** BACS or PAYROLL, read out of `settlement_details`. Null until routed. */
    val paymentMethod: String?,
    val notes: String?,
    val assignedTo: String?,
    val assignedBy: String?,
    val assignmentReason: String?,
    val createdAt: Long?,
    val claims: List<Claim> = emptyList(),
) {
    val lifecycle: Lifecycle get() = Lifecycle.of(status)
}

/** One receipt inside a [ClaimBatch]. */
data class Claim(
    val id: String,
    val batchId: String?,
    val description: String,
    val supplier: String?,
    val category: String?,
    val costCode: String?,
    val codedDescription: String?,
    val episode: String?,
    val receiptDate: Long?,
    val grossAmount: Double,
    val netAmount: Double,
    val vatAmount: Double,
    val taxRate: Double?,
    val taxType: String?,
    val settlementType: String?,
    val status: BatchStatus,
    /** The stored receipt image or PDF. A key, not a URL, on some productions. */
    val receiptUrl: String?,
    val lineItems: List<ClaimLineItem> = emptyList(),
) {
    /** Whether the attachment should be shown as a document rather than an image. */
    val receiptIsPdf: Boolean get() = receiptUrl?.endsWith(".pdf", ignoreCase = true) == true
}

/**
 * A coded split of one receipt across cost codes.
 *
 * [autoDeduction] marks the rows the server's processing-rules engine owns. The
 * web learnt the hard way that round-tripping them duplicates the deduction on
 * every save — see `stripAutoDeductionLines` — so this module keeps the flag
 * and never sends those rows back.
 */
data class ClaimLineItem(
    val id: String?,
    val account: String?,
    val description: String,
    /** GROSS on this wire, not net — see [LineItemEditor]. */
    val total: Double,
    val taxRate: Double?,
    val autoDeduction: Boolean,
    val quantity: Double = 1.0,
    /** NET per unit on this wire. */
    val unitPrice: Double = 0.0,
    val taxType: String? = null,
    /** The line this was split off. Server ids only — see [LineItemEditor.toWire]. */
    val splitParentId: String? = null,
)

/** A request to add cash to a float that is running low. */
data class CashTopUp(
    val id: String,
    val userId: String,
    val holderName: String,
    val amount: Double,
    val currency: String?,
    val status: String,
    val note: String?,
    val floatRequestNumber: String?,
    val floatIssued: Double,
    val floatBalance: Double,
    val floatRequestedAmount: Double,
    val createdAt: Long?,
)

/**
 * Who this viewer is inside the cash module, as the server sees them.
 *
 * From `GET /metadata`. Every flag defaults to **false**: an absent answer is
 * "no", because the alternative is showing a sign-off tab to someone who
 * cannot sign off and letting them find out by being refused.
 */
data class CashMetadata(
    val isApprover: Boolean = false,
    val isCoordinator: Boolean = false,
    /** Flagged senior on the production's cash team. */
    val isSenior: Boolean = false,
    val isTeamMember: Boolean = false,
    val requireSeniorSignOff: Boolean = false,
    val codingRequired: Boolean = false,
    val viewDepartmentFloats: Boolean = false,
    val canOverride: Boolean = false,
    val overrideFloatRequest: Boolean = false,
    val overrideReceiptBatch: Boolean = false,
    /** The most this person may post in one go. Null means no ceiling. */
    val postingLimit: Double? = null,
)

/**
 * The production's cash configuration, as the senior accountant set it.
 *
 * Only the fields this client reads or writes are modelled. The rest of the
 * settings blob (deduction rules, quick codes, assignment rules) round-trips
 * untouched — see [rawJson] — so saving one section cannot drop another that
 * this build does not know about yet.
 */
data class CashSettings(
    val custodianAccount: String = "",
    val bsCodeFrom: String = "",
    val bsCodeTo: String = "",
    val overrideFloatRequest: Boolean = false,
    val overrideReceiptBatch: Boolean = false,
    val requireCoordinatorCoding: Boolean = false,
    val requireSeniorSignOff: Boolean = false,
    val teamMembers: List<CashTeamMember> = emptyList(),
    val quickCodes: List<QuickCode> = emptyList(),
)

/** Someone on the cash team, with the rights the accountant granted them. */
data class CashTeamMember(
    val userId: String,
    val name: String,
    val isSenior: Boolean,
    val canOverride: Boolean,
    val postingLimit: Double?,
)

/** A saved cost code, offered on the coding screens. */
data class QuickCode(
    val code: String,
    val label: String,
    val keywords: List<String> = emptyList(),
)

/** The accountant's petty-cash dashboard. */
data class PettyCashOverview(
    val stats: CashStats = CashStats(),
    val summary: CashSummary = CashSummary(),
    val floats: List<CashFloat> = emptyList(),
)

data class CashStats(
    val activeFloats: Int = 0,
    val awaitingAudit: Int = 0,
    val awaitingApproval: Int = 0,
    val readyToPost: Int = 0,
    val readyToPostAmount: Double = 0.0,
    val escalated: Int = 0,
    val totalOutstanding: Double = 0.0,
)

data class CashSummary(
    val totalPettyCashIssued: Double = 0.0,
    val totalReceiptsApproved: Double = 0.0,
    val cashToAccount: Double = 0.0,
    val vatRecoverable: Double = 0.0,
    val oopBacsQueued: Double = 0.0,
    val oopPayrollAdditions: Double = 0.0,
)

/** The accountant's out-of-pocket dashboard. */
data class OutOfPocketOverview(
    val pendingClaims: Int = 0,
    val totalClaimed: Double = 0.0,
    val bacsReady: Double = 0.0,
    val payrollAuto: Double = 0.0,
    val routing: PaymentRouting = PaymentRouting(),
    val spendByCategory: List<CategorySpend> = emptyList(),
    val batches: List<ClaimBatch> = emptyList(),
)

/** How reimbursements are split between the two payment rails. */
data class PaymentRouting(
    val bacs: Double = 0.0,
    val payroll: Double = 0.0,
    val total: Double = 0.0,
    val batches: List<ClaimBatch> = emptyList(),
)

data class CategorySpend(val category: String, val amount: Double)

/** What one crew member sees about their own cash. */
data class MyCashOverview(
    val pettyCashClaims: List<ClaimBatch> = emptyList(),
    val outOfPocketClaims: List<ClaimBatch> = emptyList(),
    val floats: List<CashFloat> = emptyList(),
)

/** A coordinator's view of one department's floats and batches. */
data class DepartmentOverview(
    val departmentId: String?,
    val floats: List<CashFloat> = emptyList(),
    val batches: List<ClaimBatch> = emptyList(),
    val totalIssued: Double = 0.0,
    val totalSpent: Double = 0.0,
)

/** A period's cash count, reconciled against the book balance. */
data class Reconciliation(
    val id: String,
    val reference: String?,
    val status: String,
    val periodStart: Long?,
    val periodEnd: Long?,
    val bookBalance: Double,
    val countedBalance: Double,
    val currency: String?,
    val note: String?,
    val createdAt: Long?,
) {
    /** Counted minus book. Non-zero is the whole reason the screen exists. */
    val variance: Double get() = countedBalance - bookBalance
}

/** One line of a float's or batch's audit trail. */
data class CashHistoryEntry(
    val action: String,
    val userId: String?,
    val note: String?,
    val at: Long?,
)

/** A new receipt on its way to the server, before it has an id. */
data class DraftReceipt(
    val description: String = "",
    val supplier: String = "",
    val amount: String = "",
    val vat: String = "",
    val category: String = ExpenseCategory.Other.wire,
    val costCode: String = "",
    val date: Long? = null,
    /** The uploaded attachment's storage key. Null until the upload finishes. */
    val attachmentKey: String? = null,
    val attachmentName: String? = null,
)
