package com.zillit.desktop.feature.cashexpenses.domain

/**
 * Where a receipt batch is in the cash workflow.
 *
 * Transcribed from the web's `BATCH_STATUS_MAP` (`cashExpenses/components/
 * helpers.js`). Modelled as an enum rather than the raw strings the wire
 * sends because every screen in this module branches on it, and a typo in a
 * string comparison is a batch that silently never appears in a queue.
 *
 * [Unknown] is the deliberate escape hatch: the backend adds statuses, and a
 * client that throws on an unfamiliar one would break the whole queue rather
 * than one row.
 */
enum class BatchStatus(val wire: String) {
    Pending("PENDING"),
    Coding("CODING"),
    Coded("CODED"),
    InAudit("IN_AUDIT"),
    AwaitingApproval("AWAITING_APPROVAL"),
    ReadyToPost("READY_TO_POST"),
    UnderReview("UNDER_REVIEW"),
    Escalated("ESCALATED"),
    Posted("POSTED"),
    Queried("QUERIED"),
    Rejected("REJECTED"),

    /**
     * An accountant pushed the batch past its approval chain.
     *
     * It behaves as "ready to post" for everyone downstream, which is why the
     * crew-facing label says so — but an accountant is shown "Override", because
     * to them the distinction is the whole point. See [label].
     */
    AcctOverride("ACCT_OVERRIDE"),

    Unknown(""),
    ;

    /**
     * What this status is called, for [accountant] or for everyone else.
     *
     * The one place the two audiences differ is [AcctOverride] — the web makes
     * the same split, and collapsing it either hides an override from the
     * person who performed it or tells crew their batch was overridden, which
     * is not their business and reads as a problem.
     */
    @Suppress("CyclomaticComplexMethod") // One label per status; a map would separate the two.
    fun label(accountant: Boolean = false): String = when (this) {
        Pending -> "Pending"
        Coding -> "With Coordinator"
        Coded -> "Coded"
        InAudit -> "In Audit"
        AwaitingApproval -> "Awaiting Approval"
        ReadyToPost -> "Ready to Post"
        UnderReview -> "Under Review"
        Escalated -> "Escalated"
        Posted -> "Posted"
        Queried -> "Queried"
        Rejected -> "Rejected"
        AcctOverride -> if (accountant) "Override" else "Ready to Post"
        Unknown -> "Pending"
    }

    /** Terminal: the money has moved and nothing further happens to this batch. */
    val isPosted: Boolean get() = this == Posted

    /** Needs the submitter to do something before it can move again. */
    val needsSubmitterAction: Boolean get() = this == Queried || this == Rejected

    companion object {
        fun from(wire: String?): BatchStatus {
            val value = wire?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/**
 * Where a petty-cash float is in its own, separate lifecycle.
 *
 * From `FLOAT_STATUS_MAP`. [Active] is the legacy alias of [Collected] — the
 * server treats them identically and flips either to [Spending] on the first
 * receipt — so both are accepted and both read as cash in hand.
 */
enum class FloatStatus(val wire: String) {
    AwaitingApproval("AWAITING_APPROVAL"),
    Approved("APPROVED"),
    AcctOverride("ACCT_OVERRIDE"),
    ReadyToCollect("READY_TO_COLLECT"),
    Collected("COLLECTED"),
    Active("ACTIVE"),
    Spending("SPENDING"),
    Spent("SPENT"),
    PendingReturn("PENDING_RETURN"),
    Cancelled("CANCELLED"),
    Closed("CLOSED"),
    Rejected("REJECTED"),
    Unknown(""),
    ;

    val label: String
        get() = when (this) {
            AwaitingApproval -> "Awaiting Approval"
            Approved, AcctOverride -> "Approved"
            ReadyToCollect -> "Ready to Collect"
            Collected, Active -> "Collected"
            Spending -> "Spending"
            Spent -> "Spent"
            PendingReturn -> "Pending Return"
            Cancelled -> "Cancelled"
            Closed -> "Closed"
            Rejected -> "Rejected"
            Unknown -> "Awaiting Approval"
        }

    /**
     * Whether the holder physically has spendable cash right now.
     *
     * The window is exactly COLLECTED/ACTIVE → SPENDING, and it is the gate on
     * the Submit Receipts form. Before collection there is no cash to have
     * spent; after the balance reaches zero the server moves the float to
     * SPENT and the leftover return is what remains. Ported verbatim from the
     * web's `SUBMITTABLE_FLOAT_STATUSES`, where the comment explaining it is
     * longer than the list — because getting it wrong lets crew submit
     * receipts against cash they were never handed.
     */
    val isSubmittable: Boolean get() = this == Collected || this == Active || this == Spending

    /** Cash is out of the office and not yet accounted for. */
    val isOutstanding: Boolean
        get() = this == Collected || this == Active || this == Spending || this == PendingReturn

    companion object {
        fun from(wire: String?): FloatStatus {
            val value = wire?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }

        /** The first float in [floats] that receipts may be submitted against. */
        fun submittable(floats: List<CashFloat>): CashFloat? =
            floats.firstOrNull { it.status.isSubmittable }
    }
}

/** Which of the two cash pipelines a batch belongs to. */
enum class ExpenseType(val wire: String, val label: String) {
    PettyCash("pc", "Petty Cash"),
    OutOfPocket("oop", "Out of Pocket"),
    ;

    companion object {
        fun from(wire: String?): ExpenseType =
            if (wire?.trim()?.lowercase() == OutOfPocket.wire) OutOfPocket else PettyCash
    }
}

/** How a settled batch is paid — or absorbed. */
enum class Settlement(val wire: String, val label: String) {
    Reimburse("REIMBURSE", "Reimburse"),
    ReduceFloat("REDUCE_FLOAT", "Reduce Float"),
    TopUpFloat("TOP_UP_FLOAT", "Top Up Float"),
    CloseFloat("CLOSE_FLOAT", "Close Float"),
    ;

    companion object {
        fun from(wire: String?): Settlement? {
            val value = wire?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wire == value }
        }

        /** The label, falling back to a humanised form of an unmapped value. */
        fun label(wire: String?): String =
            from(wire)?.label
                ?: wire?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
                    ?.takeIf { it.isNotBlank() }
                ?: "—"
    }
}

/** What a receipt was for. Free text on the wire; these are the offered set. */
enum class ExpenseCategory(val wire: String, val label: String) {
    Materials("materials", "Materials"),
    Equipment("equipment", "Props / Equipment"),
    Stationery("stationery", "Consumables / Stationery"),
    Catering("catering", "Catering"),
    Fuel("fuel", "Fuel"),
    Parking("parking", "Parking"),
    Taxi("taxi", "Taxi / Travel"),
    Accommodation("accommodation", "Accommodation"),
    Other("other", "Other"),
    ;

    companion object {
        fun label(wire: String?): String =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }?.label
                ?: wire?.takeIf { it.isNotBlank() }
                ?: "—"
    }
}

/**
 * The five stages a batch passes through, and where one is now.
 *
 * Rendered as the progress bar on every claim detail. A batch that has been
 * queried or rejected is *not* somewhere along this line — it has fallen out
 * of it and is waiting on the submitter — so those two are their own answer
 * rather than a step index, which is what the web encodes as the magic values
 * -1 and -2.
 */
sealed interface Lifecycle {

    /** Step [index] of [STAGES] is in progress; everything before it is done. */
    data class At(val index: Int) : Lifecycle

    /** Every stage complete. */
    data object Complete : Lifecycle

    /** Out of the line, waiting on the person who submitted it. */
    data class NeedsAction(val reason: BatchStatus) : Lifecycle

    companion object {
        val STAGES = listOf(
            Stage("Submitted", "Receipts sent"),
            Stage("Coordinator", "Budget coding"),
            Stage("Accounts", "Audit & verify"),
            Stage("Approval", "Sign-off"),
            Stage("Posted", "Ledger / payment"),
        )

        @Suppress("MagicNumber") // The stage indices are the stages; naming them adds nothing.
        fun of(status: BatchStatus): Lifecycle = when (status) {
            BatchStatus.Queried, BatchStatus.Rejected -> NeedsAction(status)
            BatchStatus.Posted -> Complete
            BatchStatus.Pending, BatchStatus.Unknown -> At(0)
            BatchStatus.Coding, BatchStatus.Coded -> At(1)
            BatchStatus.InAudit -> At(2)
            BatchStatus.AwaitingApproval -> At(3)
            BatchStatus.ReadyToPost,
            BatchStatus.UnderReview,
            BatchStatus.Escalated,
            BatchStatus.AcctOverride,
            -> At(4)
        }
    }

    data class Stage(val label: String, val detail: String)
}
