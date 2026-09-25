package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** Where a card is in its own lifecycle. */
enum class CardStatus(val wire: String, private val labelKey: String) {
    Requested("requested", S.av_chip_requested),
    /** "Pending Approval" on every card surface (`adminUi.jsx:120`, `CardRegisterPage.jsx:83`). */
    Pending("pending", S.desktop_ce_cards_pending_approval),
    Approved("approved", S.approved),

    /**
     * Approved by an accountant's override rather than the chain — the web's
     * register filter and card tile both treat it like `approved`: ready to
     * be activated (`CardRegisterPage.jsx:94`, `adminUi.jsx`).
     */
    Override("override", S.dm_nom_table_override),
    Rejected("rejected", S.rejected),
    Active("active", S.active),
    DigitalActive("digital_active", S.desktop_card_digital_active),
    InTransit("in_transit", S.desktop_card_in_transit),
    Suspended("suspended", S.desktop_suspended),
    Cancelled("cancelled", S.cancelled),
    Closed("closed", S.ah_status_closed),
    Unknown("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): CardStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

enum class CardType(val wire: String, private val labelKey: String) {
    Physical("physical", S.desktop_card_physical),
    Digital("digital", S.desktop_card_digital),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): CardType =
            if (wire?.trim()?.lowercase() == Digital.wire) Digital else Physical
    }
}

/** Where a transaction or receipt is in the coding/approval workflow. */
enum class CardWorkflowStatus(val wire: String, private val labelKey: String) {
    Imported("imported", S.desktop_imported),

    /** A statement line nobody has touched — All Transactions' "New" tile. */
    New("new", S.ah_txn_filter_new),

    /** A statement line in its approval chain — All Transactions' "In Approval" tile. */
    InApproval("in_approval", S.desktop_in_approval),
    PendingReceipt("pending_receipt", S.ah_txn_filter_pending_receipt),
    PendingCode("pending_code", S.ah_pending_coding),
    Submitted("submitted", S.txt_submitted),
    AwaitingApproval("awaiting_approval", S.av_subtab_awaiting_approval),
    Approved("approved", S.approved),
    ReadyToPost("ready_to_post", S.ah_ready_to_post),
    Posted("posted", S.ah_status_posted),
    Queried("queried", S.ah_queried),
    Rejected("rejected", S.rejected),
    Personal("personal", S.personal),

    /** Flagged a duplicate in the inbox (`ReceiptInboxPage.jsx:77`). */
    Duplicate("duplicate", S.dd_csv_status_duplicate),
    Overridden("overridden", S.desktop_overridden),
    Processing("processing", S.desktop_card_processing),

    /** Handed up by a non-senior with Submit for Review; a senior's Posting Review queue. */
    UnderReview("under_review", S.ah_under_review),

    /** Handed up with a reason; also on the senior's Posting Review queue. */
    Escalated("escalated", S.ah_escalated),
    Unknown("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    val isPosted: Boolean get() = this == Posted

    companion object {
        fun from(wire: String?): CardWorkflowStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/**
 * Whether a statement line may still be removed.
 *
 * Approved means someone signed it off and posted means it reached the ledger —
 * removing either is a bookkeeping event, not a tidy-up. Everything short of
 * approval stays deletable, including what came back from the chain
 * (`transactionQuery.js:55-58`).
 */
val CardWorkflowStatus.canDelete: Boolean
    get() = this != CardWorkflowStatus.Approved && this != CardWorkflowStatus.Posted

/** Whether a receipt has been tied to a statement line. */
enum class MatchStatus(val wire: String, private val labelKey: String) {
    Matched("matched", S.desktop_matched),

    /**
     * The matcher's unconfirmed guess — what `POST /receipts/:id/confirm-match`
     * exists to clear. Kept apart from [Matched]: folding it in drew a green
     * "Reconciled" on a row one Attach short of done (`receiptReconciliation.js:28-36`).
     */
    Suggested("suggested_match", S.desktop_ce_inbox_match_suggested),
    Unmatched("unmatched", S.desktop_dm_unmatched),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /**
         * Reads the wire value.
         *
         * A **missing** value is unmatched, because the inbox groups rows with
         * no status under "no match" and a "Reconciled" badge inside that
         * section would contradict its own heading. `suggested_match` is its
         * own state; anything else (`matched`, and the `personal` /
         * `duplicate` a flagged row may carry) reads as matched, as the web's
         * badge rule does.
         */
        fun from(wire: String?): MatchStatus {
            val value = wire?.trim()?.lowercase()
            return when {
                value.isNullOrEmpty() -> Unmatched
                value == Unmatched.wire -> Unmatched
                value == Suggested.wire -> Suggested
                else -> Matched
            }
        }
    }
}

/**
 * The one-card-per-user rule, and the two exceptions to it.
 *
 * Enforced on both surfaces that offer a new card — the holder's own
 * self-service request and the accountant's holder dropdown — from this one
 * definition, because the web kept them separate and they drifted.
 */
object CardRules {

    /** Terminal states that release the holder to be issued another card. */
    private val FREEING_STATUSES = setOf(CardStatus.Suspended, CardStatus.Cancelled, CardStatus.Closed)

    /** Statuses whose card request can still be corrected and resubmitted. */
    private val EDITABLE_STATUSES = setOf(CardStatus.Requested, CardStatus.Pending, CardStatus.Rejected)

    /**
     * Whether the card's spendable balance is gone.
     *
     * A card with **no** balance figure is treated as *not* exhausted — the
     * fail-closed reading, because an unfunded or unknown card should still
     * honour the one-card rule rather than wrongly freeing its holder.
     */
    fun balanceExhausted(card: ExpenseCard): Boolean {
        val balance = card.balance ?: return false
        return balance <= 0
    }

    /**
     * Whether this card stops its holder requesting another.
     *
     * An *active* card whose limit is spent no longer blocks: the holder may
     * ask for a replacement while the maxed-out card stays live until the
     * accounts team rotates it. Everything else live — requested, pending,
     * approved, rejected, or an active card with balance left — blocks.
     */
    fun blocksNewRequest(card: ExpenseCard): Boolean {
        if (card.status in FREEING_STATUSES) return false
        if (card.status == CardStatus.Active && balanceExhausted(card)) return false
        return true
    }

    /** Whether [holderId] may be issued a new card, given every card on file. */
    fun canRequestCard(cards: List<ExpenseCard>, holderId: String): Boolean =
        cards.none { it.holderId == holderId && blocksNewRequest(it) }

    /**
     * Whether this viewer may open the edit form for a card request.
     *
     * The `requestedBy` blank check is load-bearing: without it a card with no
     * requester and a caller with no id compare equal, and every viewer is
     * treated as the owner.
     */
    fun canEditRequest(card: ExpenseCard, viewerId: String?, isAccountant: Boolean): Boolean {
        if (card.status !in EDITABLE_STATUSES) return false
        if (isAccountant) return true
        return !card.requestedBy.isNullOrBlank() && card.requestedBy == viewerId
    }

    /**
     * Whether this viewer may delete a card request: only the person who
     * raised it, and only before it is a card (`CardDetailModal.jsx:250`) —
     * being an accountant does not make someone else's request theirs to bin.
     */
    fun canDeleteRequest(card: ExpenseCard, viewerId: String?): Boolean =
        card.status in EDITABLE_STATUSES && !card.requestedBy.isNullOrBlank() && card.requestedBy == viewerId

    /** Statuses that will never post anything, so there is no control code to correct. */
    private val BS_CODE_DEAD = setOf(CardStatus.Rejected, CardStatus.Suspended, CardStatus.Cancelled, CardStatus.Closed)

    /**
     * Whether an accountant may still correct the card's control code.
     *
     * Until the first receipt exists against it — after that, a new code would
     * re-point spend already posted or on its way (`CardDetailModal.jsx:219-243`).
     * [receiptsProvenEmpty] must be a completed, successful read that found
     * none; "not loaded yet" is not "none", and failing open is the one
     * mistake this guard exists to prevent.
     */
    fun canCorrectBsCode(card: ExpenseCard, receiptsProvenEmpty: Boolean, isAccountant: Boolean): Boolean =
        isAccountant && card.status !in BS_CODE_DEAD && receiptsProvenEmpty
}

/**
 * The card receipt upload gate.
 *
 * `available = limit − receiptsCommit`, both defaulting to zero, so a card
 * with no limit set — or one loaded before the backend shipped the commit
 * figure — resolves to no headroom and refuses the upload. That is deliberate:
 * over-committing a card is a real overspend, and failing closed makes it
 * visible immediately rather than at reconciliation.
 *
 * Unlike the cash float's settlement, this one *does* block: a card's limit is
 * an authorisation the production granted, not an estimate.
 */
data class UploadHeadroom(
    val cardLimit: Double,
    val receiptsCommit: Double,
    val available: Double,
) {
    val exhausted: Boolean get() = available <= 0

    /**
     * Whether a whole new batch fits.
     *
     * Exactly hitting the limit is allowed — strictly greater blocks.
     */
    fun batchExceeds(batchTotal: Double): Boolean = batchTotal > available

    /**
     * Whether *editing* a receipt to [newAmount] fits.
     *
     * Only the net increase counts: the receipt's current amount is already
     * inside the commitment. Lowering an amount, or leaving it alone, never
     * blocks — even on an over-committed card — so attaching an image to an
     * already-committed receipt is always possible.
     */
    fun editExceeds(currentAmount: Double, newAmount: Double): Boolean =
        (newAmount - currentAmount) > maxOf(available, 0.0)

    companion object {
        fun of(card: ExpenseCard?): UploadHeadroom {
            val limit = card?.limit ?: 0.0
            val commit = card?.receiptsCommit ?: 0.0
            return UploadHeadroom(cardLimit = limit, receiptsCommit = commit, available = limit - commit)
        }
    }
}

/**
 * Who is looking at the card module.
 *
 * The same two inputs as the cash module — the production profile and this
 * module's own metadata — resolved the same way, for the same reasons. See
 * `CashViewer`, whose documentation applies unchanged.
 */
data class CardViewer(
    val userId: String,
    val departmentIdentifier: String?,
    val designationIdentifier: String?,
    val metadata: CardMetadata = CardMetadata(),
    /**
     * Whether the tool was opened on its own — from the Film Tools grid —
     * rather than inside the Account Hub.
     *
     * The web's `?entry=tool` (`useIsCardAccountant.js`): an accountant who
     * opens the tile is a crew member for this module, filing their own
     * receipts, and the same accountant arriving through the hub gets the
     * console. Set per composition from `LocalHostedBy`; see
     * `CardExpensesToolProvider`.
     */
    val enteredAsTool: Boolean = false,
    /**
     * A television production — the only kind with episodes, so the Episode
     * field and the "Ep N" line show only here (`useIsTelevisionProject`).
     */
    val isTelevision: Boolean = false,
) {
    /** In the accounts department, whichever door they came in by. */
    val isAccountsRole: Boolean
        get() = departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

    /** THE accountant gate for this module: the role, entered through the hub. */
    val isAccountant: Boolean
        get() = !enteredAsTool && isAccountsRole

    /** See the cash module's `CashViewer.isSeniorAccountant` for the matching. */
    val isSeniorAccountant: Boolean
        get() = designationIdentifier.normalisedRole().let { value ->
            value.isNotEmpty() && SENIOR_DESIGNATIONS.any { value.contains(it) }
        }

    val isSenior: Boolean get() = metadata.isSenior || isSeniorAccountant

    val isApprover: Boolean get() = metadata.isApprover

    val isCoordinator: Boolean get() = metadata.isCoordinator

    /** Settings rewrites everyone's rights, so it is a senior accountant's page. */
    val canOpenSettings: Boolean get() = isAccountant && isSenior

    /**
     * Whether this accountant may override a card request's chain.
     *
     * Two halves, both the web's (`CardRegisterPage.jsx:187`): the person — a
     * senior, or granted `can_override` — and the production's own switch,
     * `card_override`. Seniority replaces the person half only.
     */
    val canOverrideCard: Boolean
        get() = isAccountant && (isSenior || metadata.canOverride) && metadata.cardOverride

    /** The receipt twin of [canOverrideCard] (`ApprovalQueuePage.jsx:90`). */
    val canOverrideReceipt: Boolean
        get() = isAccountant && (isSenior || metadata.canOverride) && metadata.receiptOverride

    fun canPost(amount: Double): Boolean {
        val limit = metadata.postingLimit ?: return true
        return amount <= limit
    }

    private companion object {
        const val ACCOUNTS = "accounts"
        val SENIOR_DESIGNATIONS = setOf("production accountant", "financial controller")
    }
}

/** Lowercased words, from either an identifier or a display name. */
internal fun String?.normalisedRole(): String =
    orEmpty().lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()
