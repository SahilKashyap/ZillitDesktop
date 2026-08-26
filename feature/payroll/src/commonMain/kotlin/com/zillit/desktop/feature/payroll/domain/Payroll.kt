package com.zillit.desktop.feature.payroll.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One pay-period week's timecards, as payroll works them.
 *
 * ## Why a week and not a "run"
 *
 * There is no run object on the server. Payroll is worked a week at a time:
 * the timecards for a week are listed, the approved ones are marked paid, and
 * the paid ones are posted to the ledger in a batch. Every figure below is
 * derived from [lines] rather than fetched, because the server publishes no
 * week-level aggregate and one computed here can never disagree with the rows
 * underneath it.
 */
data class PayrollWeek(
    /** Epoch millis at the start of the pay-period week. */
    val weekStarting: Long,
    val currency: String?,
    val lines: List<PayrollLine>,
) {
    val crewCount: Int get() = lines.size

    val grossTotal: Double get() = lines.sumOf { it.gross }

    val deductionsTotal: Double get() = lines.sumOf { it.deductions }

    val netTotal: Double get() = lines.sumOf { it.net }

    val queriedCount: Int get() = lines.count { it.status == TimecardStatus.Queried }

    /** Approved and not yet paid — what a "mark paid" batch would move. */
    val payableLines: List<PayrollLine> get() = lines.filter { it.status.isPayable }

    /** Paid and not yet posted — what a "post to ledger" batch would move. */
    val postableLines: List<PayrollLine> get() = lines.filter { it.status.isPostable }

    val paidCount: Int get() = lines.count { it.status == TimecardStatus.Paid }

    val postedCount: Int get() = lines.count { it.status == TimecardStatus.Posted }

    /**
     * Where the week is, read off its rows.
     *
     * Deliberately ordered worst-first: a single queried timecard makes the
     * week queried however many others are posted, because the queried one is
     * the thing somebody has to act on.
     */
    val status: WeekStatus
        get() = when {
            lines.isEmpty() -> WeekStatus.Empty
            queriedCount > 0 -> WeekStatus.Queried
            postedCount == crewCount -> WeekStatus.Posted
            postableLines.isNotEmpty() -> WeekStatus.ReadyToPost
            payableLines.isNotEmpty() -> WeekStatus.ReadyToPay
            else -> WeekStatus.InProgress
        }

    /**
     * What proportion of the week has reached the ledger, 0..1.
     *
     * Zero-safe: a week with nobody in it reads as no progress rather than as
     * complete, because an empty week is not finished, it is empty.
     */
    val postedFraction: Float
        get() = if (crewCount <= 0) 0f else (postedCount.toFloat() / crewCount).coerceIn(0f, 1f)
}

/**
 * One crew member's timecard as payroll sees it.
 *
 * [id] is the timecard id — the handle every action takes — while [crewId]
 * addresses the person, which is what the payslip and nominal routes are keyed
 * by. They are different identifiers and the two are not interchangeable.
 */
data class PayrollLine(
    val id: String,
    val crewId: String,
    val crewName: String,
    val departmentId: String?,
    val departmentName: String?,
    val designation: String?,
    val status: TimecardStatus,
    val currency: String?,
    val basicPay: Double,
    val overtimePay: Double,
    val allowances: Double,
    val deductions: Double,
    val gross: Double,
    val net: Double,
    val nominalCode: String?,
    val queryNote: String?,
) {
    /**
     * Whether the line's own figures add up.
     *
     * Shown rather than corrected. A line whose parts do not reach its gross
     * usually means an allowance was added after the total was computed, and
     * paying either figure without someone deciding which is right is how a
     * payroll goes out wrong.
     */
    val figuresDisagree: Boolean
        get() = kotlin.math.abs((basicPay + overtimePay + allowances) - gross) > PENNY

    private companion object {
        const val PENNY = 0.005
    }
}

/** Where a week is, derived from its timecards. */
enum class WeekStatus(val label: String) {
    Empty("No timecards"),
    InProgress("In progress"),
    Queried("Queried"),
    ReadyToPay("Ready to pay"),
    ReadyToPost("Ready to post"),
    Posted("Posted"),
}

/**
 * A timecard's own status.
 *
 * The two that matter to payroll are [Approved] — which may be marked paid —
 * and [Paid], which may be posted to the ledger. The server silently skips
 * anything else in a batch, so the transitions are gated here too: sending
 * rows that will be ignored makes a batch report fewer moved than selected
 * with nothing to explain the gap.
 */
enum class TimecardStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Submitted("submitted", "Submitted"),
    AwaitingApproval("awaiting_approval", "Awaiting approval"),
    Approved("approved", "Approved"),
    Queried("queried", "Queried"),
    Rejected("rejected", "Rejected"),
    Locked("locked", "Locked"),
    Paid("paid", "Paid"),
    Posted("posted", "Posted"),
    Unknown("", "Unknown"),
    ;

    /** Approved work may be paid. */
    val isPayable: Boolean get() = this == Approved

    /** Only a paid timecard reaches the ledger. */
    val isPostable: Boolean get() = this == Paid

    /** Still moving: somebody may yet change it. */
    val isOpen: Boolean
        get() = this == Draft || this == Submitted || this == AwaitingApproval || this == Queried

    companion object {
        fun from(wire: String?): TimecardStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** A department's share of a week, for the totals board. */
data class DepartmentTotal(
    val departmentId: String?,
    val departmentName: String,
    val crewCount: Int,
    val gross: Double,
    val net: Double,
) {
    companion object {
        /**
         * Rolls a week's lines up by department.
         *
         * Computed rather than fetched: there is no department-totals endpoint,
         * and the alternative — a second read that could disagree with the grid
         * beside it — is worse than the arithmetic.
         */
        fun from(lines: List<PayrollLine>): List<DepartmentTotal> =
            lines.groupBy { it.departmentId }
                .map { (departmentId, group) ->
                    DepartmentTotal(
                        departmentId = departmentId,
                        // The first row that names the department wins; rows
                        // often carry the id without the name.
                        departmentName = group.firstNotNullOfOrNull { it.departmentName }
                            ?: "Unassigned",
                        crewCount = group.size,
                        gross = group.sumOf { it.gross },
                        net = group.sumOf { it.net },
                    )
                }
                .sortedByDescending { it.gross }
    }
}

/**
 * A bank account the production settles from.
 *
 * Posting a batch to the ledger requires one. The server rejects a post with
 * no bank account, so this is not an optional refinement of the post dialog —
 * it is the reason the dialog exists.
 */
data class BankAccount(
    val id: String,
    val name: String,
    val accountNumber: String?,
    val currency: String?,
) {
    /** "Barclays Current ••••4471", or just the name where there is no number. */
    val display: String
        get() = accountNumber?.takeLast(ACCOUNT_TAIL)
            ?.let { "$name ••••$it" }
            ?: name

    private companion object {
        const val ACCOUNT_TAIL = 4
    }
}

/**
 * What a batch actually moved.
 *
 * The server skips rows in the wrong state rather than failing the call, so a
 * batch can succeed having moved nothing. Both numbers are reported so the
 * screen can say which happened.
 */
data class PostOutcome(val marked: Int, val skipped: Int)

/** Who is looking at the payroll tool. */
data class PayrollViewer(
    val userId: String,
    val departmentIdentifier: String?,
    val designationIdentifier: String?,
    /**
     * Whether the production's payroll-approvers list names this user.
     *
     * From `/api/v2/payroll/metadata`, and the only authority on it — the
     * seniority guess below is a shape, not a permission.
     */
    val isFinalApprover: Boolean = false,
    val enteredAsTool: Boolean = false,
) {
    val isAccountant: Boolean
        get() = !enteredAsTool && departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

    /**
     * Paying and posting are senior actions.
     *
     * Production Accountant and Financial Controller by role; matched on the
     * normalised value so the identifier and the translated name both work.
     */
    val isSenior: Boolean
        get() = designationIdentifier.orEmpty().lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .let { value -> SENIOR.any { value.contains(it) } }

    /** Producers read the board; they do not operate it. */
    val canOperate: Boolean get() = isAccountant

    /** Posting to the ledger is irreversible, so it takes the server's word. */
    val canPost: Boolean get() = isAccountant && (isFinalApprover || isSenior)

    private companion object {
        const val ACCOUNTS = "accounts"
        val SENIOR = setOf("production accountant", "financial controller")
    }
}

/** Everything the payroll tool asks the server for. */
interface PayrollRepository {

    /**
     * Socket announcements that the week's rows changed somewhere — a final
     * approval unlocking a timecard for payroll, or another client's lock,
     * paid, unpaid or post landing — answered with a reload of the week on
     * screen rather than an in-place patch (the web's `ah:payroll:list`
     * refetch pattern). Defaulted empty for tests and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    /**
     * Every timecard for one week, full documents.
     *
     * Full rather than the slim projection because the grid shows the pay
     * breakdown, and the slim shape carries only a status and a gross.
     */
    suspend fun week(weekStarting: Long): ZillitResult<PayrollWeek>

    /**
     * The current processing week, as the server reckons it.
     *
     * Used on open. The response names the week it answered with, so the
     * caller never has to compute one — which matters because the production's
     * pay period need not start on a Monday, and a week we guessed would show
     * an empty grid with nothing to say why.
     */
    suspend fun currentWeek(): ZillitResult<PayrollWeek>

    /** Whether this user is on the production's payroll-approvers list. */
    suspend fun isFinalApprover(): ZillitResult<Boolean>

    /** The accounts the production can settle from. */
    suspend fun bankAccounts(): ZillitResult<List<BankAccount>>

    /** Moves approved timecards to paid. Anything else is skipped server-side. */
    suspend fun markPaid(timecardIds: List<String>): ZillitResult<Unit>

    /** Puts one paid timecard back to approved. */
    suspend fun markUnpaid(timecardId: String): ZillitResult<Unit>

    /**
     * Posts paid timecards to the nominal ledger.
     *
     * [bankId] and [effectiveDate] are both required by the server — a post
     * without either is rejected, and the effective date is additionally
     * checked against the cost-report lock.
     */
    suspend fun markPosted(
        timecardIds: List<String>,
        bankId: String,
        effectiveDate: Long,
    ): ZillitResult<PostOutcome>

    /**
     * The nominal split behind one crew member's week.
     *
     * Read separately from the line because it is only ever looked at when
     * someone questions a figure — fetching it for every row would be a
     * request per person per week.
     */
    suspend fun nominalSplit(weekStarting: Long, crewId: String): ZillitResult<List<NominalAllocation>>

    /** Rewrites that split. The whole set goes; the server replaces it. */
    suspend fun saveNominalSplit(
        weekStarting: Long,
        crewId: String,
        allocations: List<NominalAllocation>,
    ): ZillitResult<Unit>

    /** The payslip breakdown for one crew member, as payroll would issue it. */
    suspend fun payslip(weekStarting: Long, crewId: String): ZillitResult<Payslip?>
}

/** Where one crew member's cost is charged. */
data class NominalAllocation(
    val id: String?,
    val nominalCode: String,
    val description: String,
    val amount: Double,
    val departmentId: String? = null,
)

/**
 * What one crew member is paid this week, itemised.
 *
 * Read-only: the payslip is derived from the timecard and the deal, and
 * correcting it means correcting one of those rather than the slip.
 */
data class Payslip(
    val crewId: String,
    val crewName: String,
    val currency: String?,
    val lines: List<PayslipLine>,
    val gross: Double,
    val deductions: Double,
    val net: Double,
)

data class PayslipLine(
    val label: String,
    val amount: Double,
    /** True for anything taken off rather than added. */
    val isDeduction: Boolean = false,
)
