package com.zillit.desktop.feature.cardexpenses.domain

/** Where a card is in its own lifecycle. */
enum class CardStatus(val wire: String, val label: String) {
    Requested("requested", "Requested"),
    Pending("pending", "Pending"),
    Approved("approved", "Approved"),
    Rejected("rejected", "Rejected"),
    Active("active", "Active"),
    DigitalActive("digital_active", "Digital active"),
    InTransit("in_transit", "In transit"),
    Suspended("suspended", "Suspended"),
    Cancelled("cancelled", "Cancelled"),
    Closed("closed", "Closed"),
    Unknown("", "Unknown"),
    ;

    companion object {
        fun from(wire: String?): CardStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

enum class CardType(val wire: String, val label: String) {
    Physical("physical", "Physical"),
    Digital("digital", "Digital"),
    ;

    companion object {
        fun from(wire: String?): CardType =
            if (wire?.trim()?.lowercase() == Digital.wire) Digital else Physical
    }
}

/** Where a transaction or receipt is in the coding/approval workflow. */
enum class CardWorkflowStatus(val wire: String, val label: String) {
    Imported("imported", "Imported"),
    PendingReceipt("pending_receipt", "Pending receipt"),
    PendingCode("pending_code", "Pending coding"),
    Submitted("submitted", "Submitted"),
    AwaitingApproval("awaiting_approval", "Awaiting approval"),
    Approved("approved", "Approved"),
    ReadyToPost("ready_to_post", "Ready to post"),
    Posted("posted", "Posted"),
    Queried("queried", "Queried"),
    Rejected("rejected", "Rejected"),
    Personal("personal", "Personal"),
    Overridden("overridden", "Overridden"),
    Processing("processing", "Processing"),
    Unknown("", "Unknown"),
    ;

    val isPosted: Boolean get() = this == Posted

    companion object {
        fun from(wire: String?): CardWorkflowStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** Whether a receipt has been tied to a statement line. */
enum class MatchStatus(val wire: String, val label: String) {
    Matched("matched", "Matched"),
    Unmatched("unmatched", "Unmatched"),
    ;

    companion object {
        /**
         * Reads the wire value, treating anything unexpected as matched.
         *
         * The backend contract is now only `matched` / `unmatched`, but older
         * rows carry `suggested_match`. Product direction is that leftovers
         * behave as matched; a **missing** value is unmatched, because the
         * inbox groups rows with no status under "no match" and a "Reconciled"
         * badge inside that section would contradict its own heading.
         */
        fun from(wire: String?): MatchStatus {
            val value = wire?.trim()?.lowercase()
            return when {
                value.isNullOrEmpty() -> Unmatched
                value == Unmatched.wire -> Unmatched
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
    val enteredAsTool: Boolean = false,
) {
    val isAccountant: Boolean
        get() = !enteredAsTool && departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

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
