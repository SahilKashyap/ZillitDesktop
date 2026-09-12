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
    /** What was asked for, before an accountant set the authorised [limit]. */
    val proposedLimit: Double?,
    val justification: String?,
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

/**
 * The production's card configuration.
 *
 * Five sections of one settings document, and **only** these five. The
 * desktop's first cut of this screen wrote `auto_match_enabled`,
 * `auto_match_threshold`, `duplicate_detection`, `personal_spend_detection`
 * and `default_card_limit` — none of which is a column on the settings row.
 * The update allowlist drops keys it does not know without complaint, so the
 * page reported "Settings saved" and changed nothing at all.
 *
 * The save is per section rather than whole-document, matching the web: a
 * PATCH merges, so sending one key leaves the rest alone, and sending the
 * whole document would let a stale copy of one section overwrite a change
 * somebody else made to another.
 */
data class CardSettings(
    val teamMembers: List<CardTeamMember> = emptyList(),
    val coordinators: List<DepartmentCoordinator> = emptyList(),
    val overrides: ApprovalOverrides = ApprovalOverrides(),
    val providers: List<CardProvider> = emptyList(),
    /** The most anyone may ask for on a card request. Null means no ceiling. */
    val requestCap: Double? = null,
)

/**
 * One member of the accounts team, and what they may do.
 *
 * The posting limit is three-valued and each value means something different:
 * **null** is unlimited, **zero** is "may post nothing — send it to a senior",
 * and any other figure is a ceiling. Collapsing null and zero is the bug that
 * would silently give an unlimited poster no access at all, so the model keeps
 * them apart and so does every screen that reads it.
 */
data class CardTeamMember(
    val userId: String,
    val postingLimit: Double? = null,
    val canOverride: Boolean = false,
    val isSenior: Boolean = false,
) {
    val unlimited: Boolean get() = postingLimit == null

    val blocked: Boolean get() = postingLimit == 0.0

    /**
     * A senior is unlimited and can override, always.
     *
     * Enforced here rather than only in the form, because the two flags are
     * saved independently and a senior with a ceiling is a contradiction the
     * server does not resolve.
     */
    fun normalised(): CardTeamMember =
        if (isSenior) copy(postingLimit = null, canOverride = true) else this
}

/** Who codes for a department, and whether that department has to. */
data class DepartmentCoordinator(
    val departmentId: String,
    val userIds: List<String> = emptyList(),
    val codingRequired: Boolean = false,
) {
    val complete: Boolean get() = departmentId.isNotBlank() && userIds.isNotEmpty()
}

/** The four switches that let an accountant short-circuit an approval chain. */
data class ApprovalOverrides(
    val overrideCardRequests: Boolean = false,
    val overrideReceipts: Boolean = false,
    val requireCoordinatorCoding: Boolean = false,
    val requireSeniorSignOff: Boolean = false,
)

/** Which part of the settings document a save is touching. */
enum class SettingsSection(val label: String) {
    Team("Accounts team"),
    Coordinators("Department coordinators"),
    Overrides("Approval rules"),
    Providers("Card providers"),
    RequestCap("Request ceiling"),
}

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

/**
 * A new card request, as the form filled it in.
 *
 * The limit is a **proposal**: a request states what the holder thinks they
 * need, and an accountant sets the authorised figure when they approve. The
 * wire keys differ for the same reason — see `CardRepositoryImpl.requestCard`.
 */
data class NewCardRequest(
    val holderId: String,
    val proposedLimit: Double,
    val currency: String?,
    val departmentId: String?,
    val companyId: String?,
    val providerId: String?,
    val issuer: String?,
    val bsControlCode: String?,
    val justification: String?,
)

/**
 * An accountant's edit of a card already on file.
 *
 * Separate from [NewCardRequest] because it is a different operation on a
 * different verb, and because it carries two fields a request cannot: the
 * authorised [limit] and the [balance] recomputed from the change to it. It
 * also stamps `status: pending`, which the server reads as a resubmit and
 * answers by wiping the card's collected approvals — correct for a card still
 * in its chain, which is the only kind this form opens.
 */
data class CardDetailsEdit(
    val limit: Double,
    val balance: Double,
    val currency: String?,
    val providerId: String?,
    val issuer: String?,
    val companyId: String?,
    val bsControlCode: String,
    val justification: String,
)

/**
 * A receipt on its way up, before the server has it.
 *
 * The three coding fields are optional and collapsed behind a disclosure on
 * the form: crew are asked for what they know — what, when, how much, and the
 * document — and the budget coding is the accounts team's job unless the
 * person uploading happens to know it.
 */
data class DraftCardReceipt(
    val description: String = "",
    val amount: String = "",
    val date: Long? = null,
    val category: ReceiptCategory = ReceiptCategory.Materials,
    val urgent: Boolean = false,
    val requestTopUp: Boolean = false,
    val costCode: String = "",
    val episode: String = "",
    val codedDescription: String = "",
    val attachmentKey: String? = null,
    val attachmentName: String? = null,
) {
    val amountValue: Double get() = amount.trim().toDoubleOrNull() ?: 0.0
}

/** What a receipt was spent on, as the upload form offers it. */
enum class ReceiptCategory(val wire: String, val label: String) {
    Materials("materials", "Materials"),
    Equipment("equipment", "Props / Equipment"),
    Stationery("stationery", "Consumables / Stationery"),
    Catering("catering", "Catering"),
    Fuel("fuel", "Fuel"),
    Parking("parking", "Parking"),
    Travel("travel", "Taxi / Travel"),
    Accommodation("accommodation", "Accommodation"),
    Other("other", "Other"),
    ;

    companion object {
        fun from(wire: String?): ReceiptCategory {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Materials
        }
    }
}

/**
 * The coding a receipt carries out of the coding queue.
 *
 * Sent whole on all three of that screen's actions — save, submit, and approve
 * and submit — because the server requires the card context alongside the
 * codes on any receipt write, and a body missing it is refused.
 */
data class ReceiptCoding(
    val nominalCode: String,
    val episode: String? = null,
    val codeDescription: String? = null,
    val cardId: String? = null,
    val currency: String? = null,
)

/** One person the module can name — a holder, an approver, an assignee. */
data class CardPerson(
    val id: String,
    val name: String,
    val designation: String = "",
    val department: String = "",
    val departmentId: String = "",
) {
    /** "Name · Role", the way every picker in the hub prints a person. */
    val pickerLabel: String
        get() = listOf(name.ifBlank { id }, designation).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A file chosen on this machine and stored, ready to be pointed at. */
data class CardAttachment(val key: String, val fileName: String)

