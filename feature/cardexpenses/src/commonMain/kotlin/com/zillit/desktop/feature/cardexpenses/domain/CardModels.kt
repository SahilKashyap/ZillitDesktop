package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
    /** The chain's sign-offs so far; see [ApprovalTiers]. */
    val approvals: List<CardApproval> = emptyList(),
    /** The sixteen digits of the virtual card, once activated as digital. */
    val digitalCardNumber: String? = null,
    /** The sixteen digits of the plastic, once one is assigned. */
    val physicalCardNumber: String? = null,
    /**
     * Spend as the server counts it — `/overview` publishes it per card, and it
     * is the only figure that accounts for top-ups (`lib/cardSpend.js`).
     */
    val serverSpent: Double? = null,
    val rejectedAt: Long? = null,
    /** The issuing bank's name (`bank_account.name`), which the export prints as the issuer. */
    val bankName: String? = null,
) {
    /**
     * Spend so far: the server's figure where the row carries one, else
     * `limit − balance`, which under-reports a topped-up card by the top-up —
     * so it is only the fallback for register rows, which carry no `spent`.
     */
    val spent: Double get() = serverSpent?.coerceAtLeast(0.0) ?: (limit - (balance ?: limit)).coerceAtLeast(0.0)

    /**
     * Live with a virtual number and no plastic yet — the web's "Digital
     * Active" (`adminUi.jsx:164`), which offers Assign Physical Card. Derived:
     * the server never stamps a `digital_active` status.
     */
    val isDigitalActive: Boolean
        get() = status == CardStatus.Active && !digitalCardNumber.isNullOrBlank() && physicalCardNumber.isNullOrBlank()

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
    /** What the process editor works on; filled by the detail read. See [ReceiptProcessing]. */
    val processing: ReceiptProcessing = ReceiptProcessing(),
    /** The accountant the receipt is assigned to process, if any. */
    val assignedTo: String? = null,
    val departmentId: String? = null,
    val cardLastFour: String? = null,
    /** The chain's sign-offs so far — the approval queue's "next approver" test. */
    val approvals: List<CardApproval> = emptyList(),
    /** Which of the inbox's four sections the receipt sits in; null for none of them. */
    val inboxSection: InboxSection? = null,
    /** The upload form's category wire value (`materials`, `fuel`, …); null when never set. */
    val category: String? = null,
    /** Why, by whom and when the receipt was last rejected — the Edit & Resubmit banner. */
    val rejectionReason: String? = null,
    val rejectedBy: String? = null,
    val rejectedAt: Long? = null,
    /** The stored file's own name, from the attachment model's `name`. */
    val attachmentName: String? = null,
) {
    /**
     * What the Receipt Inbox shows in its status column — the label of
     * [ReconciliationBadge.inbox], or null to fall back to the workflow badge.
     */
    fun reconciliationLabel(): String? = inboxBadge?.label
}

/**
 * The Receipt Inbox's four sections (`ReceiptInboxPage.jsx:642-727`).
 *
 * One section per receipt, personal and duplicate first — a flagged-personal
 * row keeps its old match status, and independent filters listed it twice
 * (`receiptReconciliation.js inboxSection`).
 */
enum class InboxSection(private val labelKey: String) {
    SystemMatched(S.desktop_card_inbox_system_matched),
    NoMatch(S.desktop_no_match),
    Duplicate(S.dd_csv_status_duplicate),
    Personal(S.personal),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /** The web's classifier, over the raw wire values. */
        fun of(matchStatus: String?, status: String?, transactionId: String?): InboxSection? {
            val match = matchStatus?.trim()?.lowercase()
            val workflow = status?.trim()?.lowercase()
            return when {
                match == PERSONAL || workflow == PERSONAL -> Personal
                match == DUPLICATE || workflow == DUPLICATE -> Duplicate
                match == SUGGESTED || match == MATCHED -> SystemMatched
                transactionId.isNullOrBlank() && (match == UNMATCHED || match.isNullOrEmpty()) -> NoMatch
                else -> null
            }
        }

        private const val PERSONAL = "personal"
        private const val DUPLICATE = "duplicate"
        private const val SUGGESTED = "suggested_match"
        private const val MATCHED = "matched"
        private const val UNMATCHED = "unmatched"
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
    /** The card's limit and balance, as the funding queue row carries them. */
    val cardLimit: Double? = null,
    val cardBalance: Double? = null,
    /** `urgent` when the receipt behind it was uploaded as urgent (`TopUpToDoPage.jsx:49`). */
    val uploadType: String? = null,
    /** The part-payment note, or the reason a row was raised. */
    val note: String? = null,
    /** The receipt that raised it — the "From:" line and the detail's Source Receipt. */
    val receiptMerchant: String? = null,
    val receiptAmount: Double? = null,
    val bsControlCode: String? = null,
    /** What was actually paid; the list's Issued column falls back to [amount]. */
    val issuedAmount: Double? = null,
    val entityId: String? = null,
    /** `card` or a cash float; the card page shows only its own (`TopUpToDoPage.jsx:141`). */
    val entityType: String? = null,
    /** The row's own audit trail, embedded as `history`; see [TopUpTrailStep]. */
    val trail: List<TopUpTrailStep> = emptyList(),
) {
    val urgent: Boolean get() = uploadType == "urgent"

    /**
     * Whether adding [amount] would carry the card past its limit.
     *
     * The web's funding guard (`TopUpToDoPage.jsx:171, 212`). A row without
     * both figures cannot be judged, so it does not block.
     */
    fun overfills(amount: Double): Boolean {
        val limit = cardLimit ?: return false
        val balance = cardBalance ?: return false
        return balance + amount > limit
    }
}

/** Something the fraud/exception engine wants an accountant to look at. */
data class CardAlert(
    val id: String,
    val title: String,
    val description: String?,
    val severity: AlertSeverity,
    /** `active`, `investigating`, `resolved`, `dismissed` or `auto_closed`; blank reads as active. */
    val status: String,
    val type: String?,
    /** Money the alert reckons is at stake. Null when it does not know. */
    val savings: Double?,
    val at: Long?,
    /** What the accountant who resolved it found. */
    val resolution: String? = null,
    /** The statement lines the engine flagged together (`relatedTxns`). */
    val relatedTxns: List<AlertTxn> = emptyList(),
) {
    /** Still somebody's to act on — the web's open set (`SmartAlertsPage.jsx:124`). */
    val isOpen: Boolean get() = status == ACTIVE || status == INVESTIGATING

    val isInvestigating: Boolean get() = status == INVESTIGATING

    companion object {
        const val ACTIVE = "active"
        const val INVESTIGATING = "investigating"
        const val RESOLVED = "resolved"
        const val DISMISSED = "dismissed"
    }
}

/** One transaction an alert points at: merchant, reference, holder id, amount. */
data class AlertTxn(
    val merchant: String?,
    val ref: String?,
    val holder: String?,
    val amount: Double?,
    val currency: String? = null,
)

enum class AlertSeverity(val wire: String, private val labelKey: String) {
    High("high", S.desktop_weather_uv_high),
    Medium("medium", S.medium),
    Low("low", S.desktop_weather_uv_low),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): AlertSeverity =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() } ?: Low
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
    /** A Production Setup tax type's identifier; null keeps each receipt's own. */
    val taxType: String? = null,
    /** That type's rate, percent — sent with it as `{tax_type, tax_rate}` (`BulkProcessPage.jsx:444-451`). */
    val taxRate: Double? = null,
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
enum class TopUpMode(val wire: String, private val labelKey: String) {
    /** Leave each receipt's own setting alone. */
    Keep("keep", S.desktop_card_per_receipt),
    Restore("restore", S.desktop_card_restore_float),
    ByExpense("expense", S.desktop_card_by_expense_amount),
    None("none", S.desktop_card_no_top_up),
    ;

    val label: String get() = str(labelKey)
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
    /** The production's approval chains, per department; see [ApprovalTiers]. */
    val tierConfigs: List<TierConfig> = emptyList(),
    /** The production's own switches for overriding a chain — separate from the person's grant. */
    val cardOverride: Boolean = false,
    val receiptOverride: Boolean = false,
    /** The crew's copy of the request ceiling; an accountant reads `/settings`' (`CardExpensesModule.jsx:137`). */
    val requestCap: RequestCap? = null,
)

/**
 * A card issuer configured for the production.
 *
 * Each provider binds one bank to one company and carries its own custodian
 * account and float nominal codes (`CardProvidersEditor.jsx`). The desktop
 * used to read only the id and the name, and saving the list sent only those —
 * which wiped every bank binding somebody had set up on the web. The other
 * five fields round-trip whether or not this client edits them.
 */
data class CardProvider(
    val id: String,
    val name: String,
    val bankId: String = "",
    val companyId: String = "",
    val custodianAccount: String = "",
    val floatMin: String = "",
    val floatMax: String = "",
) {
    /** Nothing typed at all: the web drops such rows rather than saving them. */
    val blank: Boolean
        get() = listOf(name, bankId, custodianAccount, floatMin, floatMax).all { it.isBlank() }
}

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
    /** The most anyone may ask for on a card request; see [RequestCap]. */
    val requestCap: RequestCap = RequestCap(),
    /**
     * The hub's rules filed under `card_expenses`, which `/settings` echoes
     * (`SettingsPage.jsx:326`). Read here; written through the hub's own
     * routes, never through the settings PATCH.
     */
    val assignmentRules: List<CardAssignmentRule> = emptyList(),
)

/**
 * The request ceiling, as the wire holds it: an **object**, not a figure.
 *
 * `{ enabled, basis, max_amount, salary_multiplier }` (`requestCap.js`). The
 * desktop typed it as a string, so a production whose web settings had saved
 * the object failed to decode the whole `/settings` document — and with it the
 * team, the coordinators and the providers. An older row holding a bare number
 * reads as an enabled flat cap of that amount.
 */
data class RequestCap(
    val enabled: Boolean = false,
    val basis: RequestCapBasis = RequestCapBasis.MaxAmount,
    /** The flat ceiling; under a salary basis, the fallback for someone with no weekly rate. */
    val maxAmount: Double = 0.0,
    val salaryMultiplier: Double = 1.0,
)

enum class RequestCapBasis(val wire: String, private val labelKey: String) {
    MaxAmount("max_amount", S.desktop_card_cap_max_amount),
    WeeklySalary("weekly_salary", S.desktop_card_cap_weekly_salary),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): RequestCapBasis = if (wire == WeeklySalary.wire) WeeklySalary else MaxAmount
    }
}

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
enum class SettingsSection(private val labelKey: String) {
    Team(S.ah_accounts_team),
    Coordinators(S.desktop_card_department_coordinators),
    Overrides(S.desktop_card_approval_rules),
    Providers(S.desktop_card_providers),
    RequestCap(S.desktop_card_request_ceiling),
    ;

    val label: String get() = str(labelKey)
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
    val byDepartment: List<AnalyticsSlice> = emptyList(),
    val activeCards: Int = 0,
    val postedTotal: Double = 0.0,
    val totalCards: Int = 0,
    val topDepartmentId: String? = null,
    /** Days, as the service averages them; the page prints them with a `d`. */
    val avgImportToCoded: Double = 0.0,
    val avgCodedToApproved: Double = 0.0,
    val avgApprovedToPosted: Double = 0.0,
    val receiptsMissingPct: Double = 0.0,
    val autoReconciledPct: Double = 0.0,
)

/**
 * One bar of a breakdown.
 *
 * [label] is what the server named it; [userId] and [departmentId] are what
 * the current `/analytics/overview` sends instead (`by_holder`,
 * `by_department`), and the screen names those through the crew.
 */
data class AnalyticsSlice(
    val label: String,
    val amount: Double,
    val count: Int = 0,
    val userId: String? = null,
    val departmentId: String? = null,
    val cardLastFour: String? = null,
    /** A holder slice's card figures — Issued, and what is left (`AnalyticsPage.jsx:250-253`). */
    val cardLimit: Double? = null,
    val balance: Double? = null,
    val currency: String? = null,
)

/** One line of a card's or receipt's audit trail. */
data class CardHistoryEntry(
    val action: String,
    val userId: String?,
    val note: String?,
    val at: Long?,
)

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
    /** The stored file whole, sent as the receipt's `AttachmentModel`; see [CardAttachment]. */
    val attachment: CardAttachment? = null,
) {
    val amountValue: Double get() = amount.trim().toDoubleOrNull() ?: 0.0
}

/** What a receipt was spent on, as the upload form offers it. */
enum class ReceiptCategory(val wire: String, private val labelKey: String) {
    Materials("materials", S.ah_materials),
    Equipment("equipment", S.ah_props_equipment),
    Stationery("stationery", S.ah_consumables_stationery),
    Catering("catering", S.catering),
    Fuel("fuel", S.ah_fuel),
    Parking("parking", S.ah_parking),
    Travel("travel", S.ah_taxi_travel),
    Accommodation("accommodation", S.ah_accommodation),
    Other("other", S.other),
    ;

    val label: String get() = str(labelKey)

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
    /** `department_accounts` marks the accounts team; blank where the host does not say. */
    val departmentIdentifier: String = "",
) {
    /**
     * In the accounts team — who the web's assign and team pickers offer
     * (`ACCOUNTS_TEAM_USERS`). The display-name fallback is the hub's own, for
     * a host that does not pass the identifier.
     */
    val isAccountsTeam: Boolean
        get() = departmentIdentifier.equals(ACCOUNTS_DEPARTMENT, ignoreCase = true) ||
            department.contains(ACCOUNT, ignoreCase = true)

    private companion object {
        const val ACCOUNTS_DEPARTMENT = "department_accounts"
        const val ACCOUNT = "account"
    }

    /** "Name · Role", the way every picker in the hub prints a person. */
    val pickerLabel: String
        get() = listOf(name.ifBlank { id }, designation).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * A file chosen on this machine and stored, ready to be pointed at.
 *
 * The storage fields beyond the key are the web's `AttachmentModel`
 * (`attachmentUpload.js:53-61`): a receipt is stored as that whole object, and
 * one sent as a bare key string reads back on the web as no attachment at all.
 * Defaulted so a host that only knows the key still compiles and still works.
 */
data class CardAttachment(
    val key: String,
    val fileName: String,
    val bucket: String = "",
    val region: String = "",
    /** `content_type` — the MIME type the store was given. */
    val contentType: String = "",
    /** `content_subtype` — the file's extension. */
    val contentSubtype: String = "",
)

