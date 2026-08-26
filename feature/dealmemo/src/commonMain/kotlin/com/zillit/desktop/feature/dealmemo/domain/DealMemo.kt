package com.zillit.desktop.feature.dealmemo.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A deal memo: the agreed terms of one crew member's engagement.
 *
 * Everything downstream reads from it — the timecard's rates, payroll's
 * gross, the budget's commitment — so a deal that is wrong is wrong in four
 * places at once. That is why the tool has an acknowledgement step: the crew
 * member confirms the terms before they take effect.
 */
data class Deal(
    val id: String,
    val userId: String,
    val crewName: String,
    val email: String?,
    val departmentId: String?,
    val departmentName: String?,
    val designation: String?,
    val status: DealStatus,
    val currency: String?,
    val rates: DealRates,
    val startDate: Long?,
    val endDate: Long?,
    val unionName: String?,
    val agreementName: String?,
    val nominalCode: String?,
    val notes: String?,
    /** Set when the terms changed after the crew member last acknowledged. */
    val amendedAt: Long?,
    val acknowledgedAt: Long?,
    val createdAt: Long?,
    /**
     * Where a live amendment stands with the crew member — the wire's
     * `amendment_ack.status` (`DMDealPreviewPage.jsx:1427,1433`). [amendedAt]
     * and [acknowledgedAt] are the pre-port timestamps, kept as a fallback for
     * rows that carry them.
     */
    val amendmentAck: AmendmentAck = AmendmentAck.None,
    /**
     * The stored `crew_details.designation_identifier`, kept raw so a re-send
     * writes back the identifier the row already carries — [designation] is
     * the display string and must never reach the wire.
     */
    val designationIdentifier: String? = null,
) {
    /**
     * Whether the crew member has confirmed the terms as they stand now.
     *
     * The server's `amendment_ack.status` is the source
     * (`api/deal-memo/deal-memo.js:88-112`); the timestamp comparison is kept
     * for rows that predate it — an amendment after an acknowledgement
     * invalidates it, because the person agreed to different terms.
     */
    val acknowledged: Boolean
        get() = when (amendmentAck) {
            AmendmentAck.Acknowledged -> true
            AmendmentAck.Pending -> false
            AmendmentAck.None -> {
                val confirmed = acknowledgedAt
                val amended = amendedAt
                confirmed != null && (amended == null || confirmed >= amended)
            }
        }

    /** Terms changed and nobody has re-confirmed them. */
    val awaitingReacknowledgement: Boolean
        get() = amendmentAck == AmendmentAck.Pending ||
            (amendmentAck == AmendmentAck.None && acknowledgedAt != null && !acknowledged)
}

/**
 * The `amendment_ack.status` enum: an accountant amended a live deal's pay
 * rules in place, and the crew member has or has not confirmed the change
 * (`DMDealPreviewPage.jsx:1427`, `api/deal-memo/deal-memo.js:88-91`).
 */
enum class AmendmentAck(val wire: String) {
    None("none"),
    Pending("pending"),
    Acknowledged("acknowledged"),
    ;

    companion object {
        fun from(wire: String?): AmendmentAck {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: None
        }
    }
}

/**
 * What the deal pays.
 *
 * A weekly rate and a daily rate are both usual, and a deal often carries
 * both — six-day weeks with a seventh-day rate, for instance — so neither is
 * modelled as the other's fallback.
 */
data class DealRates(
    val weeklyRate: Double = 0.0,
    val dailyRate: Double = 0.0,
    val hourlyRate: Double = 0.0,
    val overtimeRate: Double = 0.0,
    /** Hours in a standard day before overtime starts. */
    val standardHours: Double = 0.0,
    val daysPerWeek: Double = 0.0,
    val boxRental: Double = 0.0,
    val vehicleAllowance: Double = 0.0,
) {
    /**
     * The best available estimate of a full week.
     *
     * The weekly rate when one is set; the daily rate times the agreed days
     * otherwise. Zero when neither is known — shown as an em dash rather than
     * as "£0.00", because those mean different things on a deal.
     */
    val estimatedWeek: Double
        get() = when {
            weeklyRate > 0 -> weeklyRate
            dailyRate > 0 && daysPerWeek > 0 -> dailyRate * daysPerWeek
            else -> 0.0
        }
}

/**
 * Where a deal is — the server's status set and lifecycle order, exactly as
 * the web's single source of truth publishes them (`dealStatus.js:18-36`,
 * `STATUS_ORDER` at `dealStatus.js:62-72`):
 * draft → issued → awaiting_approval → approved → active → completed, with
 * rejected / cancelled / deactivated as branch states.
 */
enum class DealStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),

    /** Issued to the crew member, waiting on their details (`dealStatus.js:24`). */
    Issued("issued", "Issued"),
    AwaitingApproval("awaiting_approval", "Awaiting Approval"),
    Approved("approved", "Approved"),
    Rejected("rejected", "Rejected"),
    Active("active", "Active"),
    Completed("completed", "Completed"),
    Cancelled("cancelled", "Cancelled"),

    /** Crew let go mid-engagement — terminal (`dealStatus.js:31-35`). */
    Deactivated("deactivated", "Deactivated"),
    Unknown("", "Unknown"),
    ;

    /** Terms may still be changed without an amendment trail. */
    val isEditable: Boolean get() = this == Draft

    /**
     * The deal is in force and downstream tools should read it — the web's
     * timecard reads `active` deals only (`deal-memo.js:50-53`).
     */
    val isLive: Boolean get() = this == Active

    companion object {
        fun from(wire: String?): DealStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** A union the production works under. */
data class Union(val id: String, val name: String, val agreementCount: Int = 0)

/** A collective agreement, which sets the floor for the rates on a deal. */
data class Agreement(
    val id: String,
    val name: String,
    val unionId: String?,
    val effectiveFrom: Long?,
)

/** Who is looking at the deal memo tool. */
data class DealViewer(
    val userId: String,
    val departmentIdentifier: String?,
    val designationIdentifier: String?,
    val enteredAsTool: Boolean = false,
    /**
     * `posting_access` on the deal-memo tool, admin override applied — the
     * other half of the web's write gate (`useDealMemoRights.js:48-66`).
     */
    val hasPostingRights: Boolean = false,
) {
    val isAccountant: Boolean
        get() = !enteredAsTool && departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

    /**
     * Whether this person may write deals — the web's gate, exactly
     * (`useDealMemoRights.js:48-66`): posting rights on the deal-memo tool
     * OR any accounts-department member; admins fold in through the rights
     * override. No designation filter — an earlier port restricted this to
     * senior accountants, which hid Create from assistant accountants the
     * web shows it to. Everyone else sees their own deal and nothing more —
     * a crew member browsing the production's pay rates is exactly what
     * this tool must not allow.
     */
    val canWriteDeals: Boolean
        get() = isAccountant || (!enteredAsTool && hasPostingRights)

    private companion object {
        const val ACCOUNTS = "accounts"
    }
}

/**
 * A deal as the form filled it in.
 *
 * [departmentId] and [designation] carry the rate-card *identifiers*
 * ("department_camera", "designation_gaffer_…") — the authoritative columns
 * the wire stores them under (`crew_details.department_identifier` /
 * `designation_identifier`, `toDealMemoPayload.js:796-800`), not Zillit-master
 * Mongo ids.
 */
data class NewDeal(
    val userId: String,
    val crewName: String,
    val departmentId: String?,
    val designation: String?,
    val currency: String?,
    val rates: DealRates,
    val startDate: Long?,
    val endDate: Long?,
    val unionId: String?,
    val agreementId: String?,
    val nominalCode: String?,
    val notes: String?,
) {
    /** The first reason this deal cannot be created, or null. */
    fun validationError(): String? = when {
        userId.isBlank() -> "Choose the crew member this deal is for."
        rates.weeklyRate <= 0 && rates.dailyRate <= 0 ->
            "A deal needs a weekly or a daily rate."

        startDate != null && endDate != null && endDate < startDate ->
            "The end date cannot be before the start date."

        else -> null
    }
}

/** One line of a deal's audit trail. */
data class DealHistoryEntry(
    val action: String,
    val userId: String?,
    val note: String?,
    val at: Long?,
)

/** Everything the deal memo tool asks the server for. */
interface DealMemoRepository {

    /**
     * Socket announcements that a deal changed somewhere — another client's
     * create, decision or termination, answered with a reload of whatever
     * page is open rather than an in-place patch (the web's `ah:deal_memo:*`
     * refetch pattern). Defaulted empty for tests and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    /** Every deal on the production. Requires write access. */
    suspend fun deals(status: DealStatus?): ZillitResult<List<Deal>>

    /** This viewer's own deal, for the crew portal. */
    suspend fun myDeal(): ZillitResult<Deal?>

    suspend fun deal(id: String): ZillitResult<Deal>

    suspend fun history(id: String): ZillitResult<List<DealHistoryEntry>>

    suspend fun create(deal: NewDeal, notify: Boolean): ZillitResult<Unit>

    suspend fun update(id: String, deal: NewDeal, notify: Boolean): ZillitResult<Unit>

    /** The crew member confirming the terms as they stand. */
    suspend fun acknowledge(id: String): ZillitResult<Unit>

    suspend fun unions(): ZillitResult<List<Union>>

    suspend fun agreements(unionId: String?): ZillitResult<List<Agreement>>

    // -- rate cards --------------------------------------------------------

    /** Every published rate under a union or branch, for the rate-card browser. */
    suspend fun rateCard(
        unionId: String?,
        departmentIdentifier: String?,
        productionType: String?,
    ): ZillitResult<List<RateCardEntry>>

    /**
     * The single rate row for one crew selection.
     *
     * Experience is deliberately not a parameter: the resolver has no
     * experience axis, and passing one would be silently ignored — which reads
     * on the caller's side as a filter that did nothing.
     */
    suspend fun resolveRate(
        departmentIdentifier: String,
        designationIdentifier: String,
        productionType: String,
        agreementId: String?,
        unionId: String?,
        budget: Double?,
    ): ZillitResult<RateCardEntry?>

    /** The agreement's own scale, which roles fall back to. See [RateCascade]. */
    suspend fun basicRateDetails(agreementId: String): ZillitResult<BasicRateDetails?>
}
