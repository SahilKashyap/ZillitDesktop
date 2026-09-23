package com.zillit.desktop.feature.cashexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
        Pending -> str(S.pending)
        Coding -> str(S.desktop_ce_with_coordinator)
        Coded -> str(S.desktop_ce_coded)
        InAudit -> str(S.desktop_ce_in_audit)
        AwaitingApproval -> str(S.dm_filter_status_pending)
        ReadyToPost -> str(S.ah_ready_to_post)
        UnderReview -> str(S.ah_under_review)
        Escalated -> str(S.ah_escalated)
        Posted -> str(S.ah_status_posted)
        Queried -> str(S.ah_queried)
        Rejected -> str(S.rejected)
        AcctOverride -> if (accountant) str(S.dm_nom_table_override) else str(S.ah_ready_to_post)
        Unknown -> str(S.pending)
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
            AwaitingApproval -> str(S.dm_filter_status_pending)
            Approved, AcctOverride -> str(S.approved)
            ReadyToCollect -> str(S.desktop_ce_ready_to_collect)
            Collected, Active -> str(S.desktop_ce_collected)
            Spending -> str(S.desktop_ce_spending)
            Spent -> str(S.ah_spent_label)
            PendingReturn -> str(S.desktop_ce_pending_return)
            Cancelled -> str(S.cancelled)
            Closed -> str(S.ah_status_closed)
            Rejected -> str(S.rejected)
            Unknown -> str(S.dm_filter_status_pending)
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
enum class ExpenseType(val wire: String, private val labelKey: String) {
    PettyCash("pc", S.desktop_petty_cash),
    OutOfPocket("oop", S.desktop_ce_out_of_pocket),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): ExpenseType =
            if (wire?.trim()?.lowercase() == OutOfPocket.wire) OutOfPocket else PettyCash
    }
}

/** How a settled batch is paid — or absorbed. */
enum class Settlement(val wire: String, private val labelKey: String) {
    Reimburse("REIMBURSE", S.desktop_ce_reimburse),
    ReduceFloat("REDUCE_FLOAT", S.desktop_ce_reduce_float),
    TopUpFloat("TOP_UP_FLOAT", S.desktop_ce_top_up_float),
    CloseFloat("CLOSE_FLOAT", S.desktop_ce_close_float),
    ;

    val label: String get() = str(labelKey)

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
enum class ExpenseCategory(val wire: String, private val labelKey: String) {
    Materials("materials", S.ah_materials),
    Equipment("equipment", S.ah_props_equipment),
    Stationery("stationery", S.ah_consumables_stationery),
    Catering("catering", S.catering),
    Fuel("fuel", S.ah_fuel),
    Parking("parking", S.ah_parking),
    Taxi("taxi", S.ah_taxi_travel),
    Accommodation("accommodation", S.ah_accommodation),
    Other("other", S.other),
    ;

    val label: String get() = str(labelKey)

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
        val STAGES: List<Stage>
            get() = listOf(
                Stage(str(S.ah_step_submitted), str(S.ah_step_submitted_desc)),
                Stage(str(S.ah_step_coordinator), str(S.ah_step_coordinator_desc)),
                Stage(str(S.ah_step_accounts), str(S.ah_step_accounts_desc)),
                Stage(str(S.ah_step_approval), str(S.ah_step_approval_desc)),
                Stage(str(S.ah_step_posted), str(S.ah_step_posted_desc)),
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
