package com.zillit.desktop.feature.cardexpenses.domain

/**
 * A production expense card issued to one crew member.
 *
 * The card is the unit of authorisation here: a limit is granted to a card,
 * receipts commit against a card, and a card belongs to exactly one holder at
 * a time — see [CardRules] for the one-card-per-user rule that follows.
 */
data class ExpenseCard(
    val id: String,
    val holderId: String,
    val holderName: String,
    val departmentId: String?,
    val companyId: String?,
    val status: CardStatus,
    val type: CardType,
    val lastFour: String?,
    val issuer: String?,
    val providerId: String?,
    val currency: String?,
    /** The authorised ceiling. Zero when none has been set. */
    val limit: Double,
    val monthlyLimit: Double?,
    /** What is left to spend, as the server last computed it. */
    val balance: Double?,
    /**
     * Receipt value already committed against this card — posted and pending.
     *
     * Backend-owned. The upload gate subtracts it from [limit]; a card with no
     * figure resolves to zero headroom rather than unlimited, which is the
     * fail-closed reading. See [UploadHeadroom].
     */
    val receiptsCommit: Double?,
    val bsControlCode: String?,
    val requestedBy: String?,
    val rejectedBy: String?,
    val rejectionReason: String?,
    val createdAt: Long?,
) {
    /** Spend so far, as the holder experiences it. */
    val spent: Double get() = (limit - (balance ?: limit)).coerceAtLeast(0.0)

    val consumedFraction: Float
        get() = if (limit <= 0) 0f else (spent / limit).toFloat().coerceIn(0f, 1f)
}

/** A line off the bank statement, matched (or not) to a receipt. */
data class CardTransaction(
    val id: String,
    val cardId: String?,
    val cardLastFour: String?,
    val holderId: String?,
    val holderName: String,
    val merchant: String,
    val description: String?,
    val amount: Double,
    val currency: String?,
    val date: Long?,
    val status: CardWorkflowStatus,
    val nominalCode: String?,
    val codeDescription: String?,
    val episode: String?,
    val vatAmount: Double,
    val matchStatus: MatchStatus,
    val receiptId: String?,
    val personal: Boolean,
)

/** A receipt a cardholder uploaded, before or after it is matched. */
data class CardReceipt(
    val id: String,
    val cardId: String?,
    val holderId: String?,
    val holderName: String,
    val description: String,
    val merchant: String?,
    val amount: Double,
    val currency: String?,
    val date: Long?,
    val status: CardWorkflowStatus,
    val matchStatus: MatchStatus,
    val transactionId: String?,
    val transactionMerchant: String?,
    val transactionAmount: Double?,
    val transactionDate: Long?,
    val transactionCardLastFour: String?,
    val nominalCode: String?,
    val codeDescription: String?,
    val episode: String?,
    val attachmentKey: String?,
    val urgent: Boolean,
    /** Confidence the engine put on its suggested statement match, 0..100. */
    val matchScore: Int?,
    val duplicateScore: Int?,
    val duplicateDismissed: Boolean,
    val personalScore: Int?,
    val personalDismissed: Boolean,
    val createdAt: Long?,
) {
    /**
     * What the Receipt Inbox shows in its status column.
     *
     * Reconciliation vocabulary wins over workflow vocabulary when it is the
     * more meaningful fact: an unmatched row is "Unreconciled" whatever its
     * approval state, and a matched row with no document yet is "Reconciled"
     * rather than the noise of "pending receipt". Once a document exists the
     * workflow state is what matters and this returns null so the caller falls
     * back to it. Ported from `receiptReconciliationBadge`.
     */
    fun reconciliationLabel(): String? = when {
        matchStatus == MatchStatus.Unmatched -> "Unreconciled"
        attachmentKey.isNullOrBlank() -> "Reconciled"
        else -> null
    }
}

/** A request to add funds to a card. */
data class CardTopUp(
    val id: String,
    val cardId: String?,
    val cardLastFour: String?,
    val holderId: String?,
    val holderName: String,
    val amount: Double,
    val currency: String?,
    val method: String?,
    val status: String,
    val createdAt: Long?,
)

/** Something the fraud/exception engine wants an accountant to look at. */
data class CardAlert(
    val id: String,
    val title: String,
    val description: String?,
    val severity: AlertSeverity,
    val status: String,
    val type: String?,
    /** Money the alert reckons is at stake. Null when it does not know. */
    val savings: Double?,
    val at: Long?,
)

enum class AlertSeverity(val wire: String, val label: String) {
    High("high", "High"),
    Medium("medium", "Medium"),
    Low("low", "Low"),
    ;

    companion object {
        fun from(wire: String?): AlertSeverity =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() } ?: Low
    }
}

/**
 * One line of an uploaded statement, before it becomes a transaction.
 *
 * Review happens here: a row is checked, assigned to whoever spent it, and
 * only then submitted — after which it is a transaction and this row is
 * history.
 */
data class StatementRow(
    val id: String,
    val merchant: String,
    val description: String?,
    val amount: Double,
    val currency: String?,
    val date: Long?,
    val cardLastFour: String?,
    /** Whom the matcher thinks it belongs to. Null when it could not tell. */
    val holderId: String?,
    val holderName: String?,
    val status: String,
) {
    /** Untouched, so still selectable for review. */
    val isNew: Boolean get() = status.equals(NEW, ignoreCase = true)

    /** Whether this row can be sent to someone for a receipt. */
    val canSubmit: Boolean get() = isNew && !holderId.isNullOrBlank()

    private companion object {
        const val NEW = "new"
    }
}

/**
 * A receipt ready to be processed in bulk, with the coding it would carry.
 *
 * The bulk screen exists because most card spend is routine: forty coffees and
 * a parking charge, all to the same code. Working them one at a time is the
 * job this replaces.
 */
data class BulkItem(
    val id: String,
    val holderId: String?,
    val holderName: String,
    val description: String,
    val merchant: String?,
    val amount: Double,
    val currency: String?,
    val date: Long?,
    val cardLastFour: String?,
    val nominalCode: String?,
    val codeDescription: String?,
    val episode: String?,
    val status: CardWorkflowStatus,
    val assignedTo: String?,
    val urgent: Boolean,
) {
    /**
     * Whether this row may be ticked.
     *
     * A row already assigned to someone else is theirs to process — bulk
     * posting it would take a decision out of their hands without telling
     * them.
     */
    fun selectableBy(viewerId: String): Boolean =
        assignedTo.isNullOrBlank() || assignedTo == viewerId
}

/**
 * The coding a bulk post applies.
 *
 * Every field is optional and **null means "leave each row's own"** — the
 * screen's default. That is the difference between correcting forty rows and
 * overwriting forty rows, and it is why these are nullable rather than blank
 * strings.
 */
data class BulkCoding(
    val nominalCode: String? = null,
    val departmentId: String? = null,
    val episode: String? = null,
    val taxType: String? = null,
    val topUp: TopUpMode = TopUpMode.Keep,
)

/**
 * What top-up to raise for each holder as part of a bulk post.
 *
 * Four choices rather than a tick box, because "restore the float to what it
 * was" and "top up by what was spent" are different amounts whenever a card
 * was not at its limit — and picking the wrong one leaves a holder short in
 * a way nobody notices until they try to spend.
 */
enum class TopUpMode(val wire: String, val label: String) {
    /** Leave each receipt's own setting alone. */
    Keep("keep", "Per receipt"),
    Restore("restore", "Restore float"),
    ByExpense("expense", "By expense amount"),
    None("none", "No top-up"),
}

/** What a bulk post did. */
data class BulkOutcome(val succeeded: Int, val failed: Int)

/** An uploaded bank statement, and how far through processing it is. */
data class StatementImport(
    val id: String,
    val filename: String?,
    val status: String,
    val rowCount: Int,
    val matchedCount: Int,
    val importedAt: Long?,
)

/** Who the viewer is inside the card module, per `GET /metadata`. */
data class CardMetadata(
    val isApprover: Boolean = false,
    val isCoordinator: Boolean = false,
    val isSenior: Boolean = false,
    val codingRequired: Boolean = false,
    val canOverride: Boolean = false,
    val postingLimit: Double? = null,
    val cardProviders: List<CardProvider> = emptyList(),
)

/** A card issuer configured for the production. */
data class CardProvider(val id: String, val name: String)

/** The production's card configuration. */
data class CardSettings(
    val codingRequired: Boolean = false,
    val requireSeniorSignOff: Boolean = false,
    val autoMatchEnabled: Boolean = true,
    /** Confidence at or above which the engine matches without asking, 0..100. */
    val autoMatchThreshold: Int = DEFAULT_MATCH_THRESHOLD,
    val duplicateDetection: Boolean = true,
    val personalSpendDetection: Boolean = true,
    val defaultCardLimit: Double? = null,
    val providers: List<CardProvider> = emptyList(),
)

/** The accountant's dashboard figures. */
data class CardOverview(
    val activeCards: Int = 0,
    val requestedCards: Int = 0,
    val inbox: Int = 0,
    val pendingCoding: Int = 0,
    val inApproval: Int = 0,
    val approved: Int = 0,
    val posted: Int = 0,
    val transactionCount: Int = 0,
    val totalSpend: Double = 0.0,
    val postedTotal: Double = 0.0,
    val vatEstimate: Double = 0.0,
    val cards: List<ExpenseCard> = emptyList(),
    val pendingTopUps: List<CardTopUp> = emptyList(),
)

/** Spend analysis over a period. */
data class CardAnalytics(
    val totalSpend: Double = 0.0,
    val transactionCount: Int = 0,
    val averageTransaction: Double = 0.0,
    val byCategory: List<AnalyticsSlice> = emptyList(),
    val byHolder: List<AnalyticsSlice> = emptyList(),
    val byMonth: List<AnalyticsSlice> = emptyList(),
)

data class AnalyticsSlice(val label: String, val amount: Double, val count: Int = 0)

/** One line of a card's or receipt's audit trail. */
data class CardHistoryEntry(
    val action: String,
    val userId: String?,
    val note: String?,
    val at: Long?,
)

/**
 * One coded split of a card receipt.
 *
 * The card service stores net and tax separately rather than deriving one from
 * the other, which is why this carries both rather than a rate — unlike the
 * cash module's line, where the wire keeps gross.
 */
data class ReceiptLine(
    val id: String?,
    val description: String,
    val nominalCode: String,
    val net: Double,
    val taxAmount: Double,
    val episode: String? = null,
) {
    val gross: Double get() = net + taxAmount
}

/** A new card request, as the form filled it in. */
data class NewCardRequest(
    val holderId: String,
    val limit: Double,
    val currency: String?,
    val type: CardType,
    val departmentId: String?,
    val companyId: String?,
    val providerId: String?,
    val bsControlCode: String?,
    val reason: String?,
)

/** A receipt on its way up, before the server has it. */
data class DraftCardReceipt(
    val description: String = "",
    val merchant: String = "",
    val amount: String = "",
    val date: Long? = null,
    val nominalCode: String = "",
    val attachmentKey: String? = null,
    val attachmentName: String? = null,
)

private const val DEFAULT_MATCH_THRESHOLD = 85
