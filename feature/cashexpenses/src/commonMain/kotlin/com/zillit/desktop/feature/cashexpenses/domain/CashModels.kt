package com.zillit.desktop.feature.cashexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.forms.CustomFieldGroup
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    /** Who has signed at which level of the approval chain — see [ApprovalTiers]. */
    val approvals: List<TierApproval> = emptyList(),
    /** When the crew member collects the cash — UTC epoch millis, as the request form sends it. */
    val collectDate: Long? = null,
    /** `HH:mm`, free text on the wire. */
    val collectTime: String? = null,
    val collectionMethod: String? = null,
    /** The extra answers this production's float request form collected. */
    val customFields: List<CustomFieldGroup> = emptyList(),
    val activatedAt: Long? = null,
    val closedAt: Long? = null,
    /**
     * `spent` as the server reports it — settled spend. Null when absent;
     * [spent] is the figure worked out from issued and balance.
     */
    val reportedSpent: Double? = null,
    val companyName: String? = null,
    val episode: String? = null,
    val createdBy: String? = null,
) {
    /**
     * Whether anything has been charged against the float — the web's
     * `floatHasReceipts`. An unread figure counts as nothing, so a missing
     * number never freezes a code the accountant needs to fix.
     */
    val hasReceipts: Boolean get() = (receiptsCommits ?: 0.0) > 0 || (reportedSpent ?: 0.0) > 0

    /**
     * May an accountant correct this float's BS code — `canEditFloatBsCode`:
     * approved and live, nothing yet spent against it. Once a receipt commits,
     * the code decides where that spend posted.
     */
    fun bsCodeEditable(isAccountant: Boolean): Boolean =
        isAccountant && status in FloatStatus.BS_EDITABLE && !hasReceipts

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
    /**
     * The ledger date the batch posts on, UTC midnight epoch millis.
     *
     * Null until someone saves or posts it. A stored date on or before the
     * cost-report lock freezes the batch — see [CashRules.periodLocked].
     */
    val effectiveDate: Long? = null,
    val escalationReason: String? = null,
    val escalatedBy: String? = null,
    /** Who has signed at which level of the approval chain — see [ApprovalTiers]. */
    val approvals: List<TierApproval> = emptyList(),
    val escalatedAt: Long? = null,
    val postedAt: Long? = null,
    val rejectionReason: String? = null,
    val rejectedBy: String? = null,
    val rejectedAt: Long? = null,
    val queryReason: String? = null,
    /** The float the receipts were spent against; null on out-of-pocket. */
    val floatRequestId: String? = null,
    /** `settlement_details.follow_up` — `top_up`, `close`… as the submit form chose. */
    val followUp: String? = null,
    /** The whole `settlement_details` object, for what the fields above do not name. */
    val settlementDetails: JsonObject? = null,
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
    /** Ticked by the auditor, receipt by receipt; Send for Approval needs them all. */
    val isVerified: Boolean = false,
    /** `review` / `query` marks the processing rules left on this receipt. */
    val processingFlags: List<String> = emptyList(),
    /**
     * The stored lines exactly as the server sent them, engine rows removed.
     *
     * A save that sends the batch's claims back — posting, verifying — must not
     * rewrite what it did not edit: a tax line keeps its `is_tax`, a coded
     * line its layers and tags. [lineItems] is the editor's reading and loses
     * those.
     */
    val rawLines: List<JsonObject> = emptyList(),
    /** The stored receipt as the web uploads it; [receiptUrl] is the older flat key. */
    val attachment: CashAttachment? = null,
    /** What the processing rules took off this receipt. */
    val deductionAmount: Double = 0.0,
    val batchReference: String? = null,
) {
    /**
     * The key to open — the attachment first, [receiptUrl] when a row has
     * only that (the web's order).
     */
    val receiptKey: String?
        get() = attachment?.media?.takeIf { it.isNotBlank() } ?: receiptUrl?.takeIf { it.isNotBlank() }

    /** Whether the attachment should be shown as a document rather than an image. */
    val receiptIsPdf: Boolean
        get() = attachment?.isPdf ?: (receiptUrl?.endsWith(".pdf", ignoreCase = true) == true)
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
    /** The consolidated reclaimable-tax line: its gross is its tax. */
    val isTax: Boolean = false,
    val taxAmount: Double? = null,
    /** Coding layers, as stored — round-tripped, never interpreted here. */
    val trackingCodes: JsonElement? = null,
    val tags: JsonElement? = null,
    /** As stored — a date string or an epoch, sent back as it came. */
    val rentalStart: String? = null,
    val rentalEnd: String? = null,
    val sortOrder: Int? = null,
    val expenditureType: String? = null,
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
    /** What happened to the request, oldest first. */
    val history: List<TopUpHistoryEntry> = emptyList(),
    /** How the cash was asked for or paid — free text on the wire. */
    val method: String? = null,
    val updatedAt: Long? = null,
    /** What was actually paid, on a completed or partial top-up. */
    val issuedAmount: Double? = null,
    val floatRequestId: String? = null,
)

/** One step of a top-up's life — `{action, action_by, action_at, reason, amount}`. */
data class TopUpHistoryEntry(
    val action: String,
    val actionBy: String? = null,
    val actionAt: Long? = null,
    val reason: String? = null,
    val amount: Double? = null,
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
    /**
     * Whether the server said `posting_limit: null` — unlimited — rather than
     * leaving the key out.
     *
     * The two differ on the web: an absent limit is no grant at all
     * (`hasUnlimitedPostingLimit` is `limit === null`), so a senior's
     * designation, not the missing key, is what lets them post.
     */
    val postingLimitUnlimited: Boolean = false,
    /** The production's approval chains, as `/metadata` hands them over. */
    val approvalTierConfigs: List<ApprovalTierConfig> = emptyList(),
    /**
     * The float request ceiling, as crew read it — `/settings` is an
     * accountant's route, so the request form reads it here
     * (`PCFloatRequestPage.jsx:270-280`). Null when the server sent none.
     */
    val requestCap: RequestCap? = null,
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
    /** Out-of-pocket claims settled through payroll rather than a BACS run. */
    val reimburseToPayroll: Boolean = false,
    val deductionRules: List<DeductionRule> = emptyList(),
    /** The ceiling on a float request — its own section, saved on its own. */
    val requestCap: RequestCap = RequestCap(),
    /** Who a batch lands with automatically; written through the account hub's own route. */
    val assignmentRules: List<CashAssignmentRule> = emptyList(),
    /** Who codes and oversees each department's cash. */
    val departmentCoordinators: List<DepartmentCoordinator> = emptyList(),
)

/** A department's coordinators — one row of the settings' `department_coordinators`. */
data class DepartmentCoordinator(
    val departmentId: String,
    val userIds: List<String> = emptyList(),
    /** Receipts from this department are coded by these coordinators before accounts sees them. */
    val codingRequired: Boolean = false,
    /** The coordinators see the department's floats on Active Floats. */
    val viewDepartmentFloats: Boolean = false,
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
/**
 * A saved coding shortcut — "Fuel is 2400 at 20% VAT".
 *
 * The field names are the ones the web writes (`name`, `nominal_code`,
 * `keywords`, `vat`). This used to read `code`/`label`, which nothing has ever
 * written: a production configured on the web came through with no quick codes
 * at all, and the coding editor offered an empty list.
 */
data class QuickCode(
    val name: String,
    val nominalCode: String = "",
    val keywords: List<String> = emptyList(),
    /** Per cent, as configured; null when the category carries no default. */
    val vat: Double? = null,
) {
    /** "Fuel · 2400", or just the name where no code is set. */
    val label: String get() = if (nominalCode.isBlank()) name else "$name · $nominalCode"
}

/**
 * A rule that fires on a receipt's category and value — the web's
 * `deduction_rules`.
 *
 * Four ship as system defaults (fuel, accommodation, meals, high value), all
 * switched off until an accountant enables one. A rule either deducts a share
 * of the receipt, sends it for senior review, or raises a query.
 */
data class DeductionRule(
    val id: String,
    val title: String,
    val description: String = "",
    val processType: RuleProcess = RuleProcess.DeductAmount,
    val thresholdType: RuleThreshold = RuleThreshold.Percentage,
    val thresholdValue: Double = 0.0,
    val enabled: Boolean = false,
    /** Words matched against the receipt's description; empty means every receipt. */
    val triggerCodes: List<String> = emptyList(),
    /** One of the four the server ships; a production may not delete these. */
    val systemDefault: Boolean = false,
    /** The stored `type`, round-tripped so a save cannot rename a system rule. */
    val type: String = "",
)

/** What a [DeductionRule] does when it fires. */
enum class RuleProcess(val wire: String, private val labelKey: String) {
    DeductAmount("deduct_amount", S.desktop_ce_deduct),
    SeniorReview("senior_review", S.desktop_ce_senior_review),
    NeedQuery("need_query", S.desktop_sa_raise_query),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): RuleProcess =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() } ?: DeductAmount
    }
}

/** How a [DeductionRule]'s threshold is read. */
enum class RuleThreshold(val wire: String, private val labelKey: String) {
    Percentage("percentage", S.desktop_ce_percent_of_receipt),
    MinAmount("min_amount", S.desktop_ce_over_an_amount),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): RuleThreshold =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() } ?: Percentage
    }
}

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
    val routing: RoutingSplit = RoutingSplit(),
    val spendByCategory: List<CategorySpend> = emptyList(),
    val batches: List<ClaimBatch> = emptyList(),
)

/** The out-of-pocket dashboard's split of reimbursements across the two rails. */
data class RoutingSplit(
    val bacs: Double = 0.0,
    val payroll: Double = 0.0,
    val total: Double = 0.0,
)

/**
 * Payment Routing — `GET /claims/overview/payment-routing`.
 *
 * The server sends `stats` plus the two batch lists (`bacs_batches`,
 * `payroll_batches`). This used to be read as `bacs`/`payroll`/`total`, which
 * the route never sends, so every tile said zero.
 */
data class PaymentRouting(
    val bacsReady: Double = 0.0,
    val bacsCount: Int = 0,
    val payrollTotal: Double = 0.0,
    val payrollCount: Int = 0,
    val bacsBatches: List<ClaimBatch> = emptyList(),
    val payrollBatches: List<ClaimBatch> = emptyList(),
)

data class CategorySpend(val category: String, val amount: Double)

/**
 * What one crew member sees about their own cash — `GET /claims/overview/my`.
 *
 * The two claim lists are *receipts* carrying their batch's reference and
 * status (`PCCrewOverviewPage.jsx:89-111`), not batches. See [RecentClaim].
 */
data class MyCashOverview(
    val pettyCashClaims: List<RecentClaim> = emptyList(),
    val outOfPocketClaims: List<RecentClaim> = emptyList(),
    val floats: List<CashFloat> = emptyList(),
)

/**
 * A coordinator's view of one department — `GET /claims/overview/department`:
 * `floats`, `oop_batches`, `stats` and `spend_by_category`
 * (`PCDeptViewPage.jsx:44`). [departmentId] is the one asked about.
 */
data class DepartmentOverview(
    val departmentId: String?,
    val floats: List<CashFloat> = emptyList(),
    val outOfPocketBatches: List<ClaimBatch> = emptyList(),
    val stats: DepartmentStats = DepartmentStats(),
    val spendByCategory: List<DepartmentCategorySpend> = emptyList(),
)

/** A period's cash count, reconciled against the book balance. */
data class Reconciliation(
    val id: String,
    val reference: String?,
    val status: String,
    val periodStart: Long?,
    val periodEnd: Long?,
    val bookBalance: Double,
    /** What was physically counted — `physical_cash` on the wire. */
    val countedBalance: Double,
    val currency: String?,
    val note: String?,
    val createdAt: Long?,
    /** The safe's opening balance for the period — `opening_safe_balance`. */
    val openingBalance: Double = 0.0,
    /** The variance the server stored, when it stored one. */
    val storedVariance: Double? = null,
    val denominations: List<Denomination> = emptyList(),
    val reconcilingItems: List<ReconItem> = emptyList(),
    val createdBy: String? = null,
    val updatedBy: String? = null,
    val updatedAt: Long? = null,
    val submittedBy: String? = null,
    val submittedAt: Long? = null,
    val signedBy: String? = null,
    val signedAt: Long? = null,
) {
    /** Counted minus book, unless the server already worked it out with the reconciling items. */
    val variance: Double get() = storedVariance ?: (countedBalance - bookBalance)

    val isSignedOff: Boolean get() = status == ReconDraft.SIGNED_OFF
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
    // Materials first, as the web's receipt card starts (`ReceiptSubmitForm.jsx`).
    val category: String = ExpenseCategory.Materials.wire,
    val costCode: String = "",
    val date: Long? = null,
    /** The uploaded attachment's storage key. Null until the upload finishes. */
    val attachmentKey: String? = null,
    val attachmentName: String? = null,
    /**
     * The uploaded file as the claim route takes it; when present it is sent
     * as `attachment`, and [attachmentKey] mirrors its key.
     */
    val attachment: CashAttachment? = null,
    // -- crew parity --
    /** Budget coding's episode — television productions only. */
    val episode: String = "",
    /** Budget coding's description — `coded_description`, not the vendor. */
    val codedDescription: String = "",
)

/**
 * `GET /float-requests/{id}/details` — one float and everything that moved
 * it: the batches spent against it, its top-ups and its returns.
 */
data class FloatDetails(
    val float: CashFloat?,
    val totals: FloatTotals = FloatTotals(),
    val batches: List<ClaimBatch> = emptyList(),
    val topUps: List<CashTopUp> = emptyList(),
    val returns: List<FloatReturn> = emptyList(),
)

/** The detail's `totals` block, as the server worked it out. */
data class FloatTotals(
    val requested: Double = 0.0,
    val issued: Double = 0.0,
    val spent: Double = 0.0,
    val toppedUp: Double = 0.0,
    val returned: Double = 0.0,
    val finalBalance: Double = 0.0,
)

/** A cash return recorded against a float. */
data class FloatReturn(
    val id: String,
    val amount: Double,
    val currency: String?,
    /** The wire key — see `ReturnReasons`. */
    val reason: String?,
    val recordedAt: Long?,
    val receivedDate: Long?,
    val notes: String?,
    val recordedBy: String? = null,
)
