package com.zillit.desktop.feature.timecard.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * One crew member's week.
 *
 * The unit of everything in this tool: hours are entered against a week,
 * approved as a week, and paid as a week. A day never moves on its own.
 */
data class Timecard(
    val id: String,
    val userId: String,
    val crewName: String,
    val departmentId: String?,
    val designation: String?,
    /** Monday of the week, epoch millis. */
    val weekStarting: Long?,
    val weekNumber: Int?,
    val status: TimecardStatus,
    val currency: String?,
    val days: List<TimecardDay> = emptyList(),
    val basicPay: Double = 0.0,
    val overtimePay: Double = 0.0,
    val totalAllowances: Double = 0.0,
    val additionalFees: Double = 0.0,
    val deductions: List<Deduction> = emptyList(),
    val totalPay: Double = 0.0,
    val totalDays: Double = 0.0,
    val notes: String?,
    val queryNote: String?,
    val rejectionReason: String?,
    val lastApprovedBy: String?,
    val paidAt: Long?,
    val updatedAt: Long?,
    val locked: Boolean = false,
) {
    /** Hours actually worked, across the week. */
    val workedHours: Double get() = days.sumOf { it.workedHours }

    /** What the crew member is owed before deductions. */
    val gross: Double get() = basicPay + overtimePay + totalAllowances + additionalFees

    val deductionTotal: Double get() = deductions.sumOf { it.amount }

    /**
     * Gross less deductions.
     *
     * Computed rather than trusted from [totalPay]: the server sends the field
     * on some endpoints and omits it on others, and a blank total on a payroll
     * screen reads as "nothing owed".
     */
    val net: Double get() = if (totalPay > 0) totalPay else gross - deductionTotal

    /** Whether this week can still be edited by the person it belongs to. */
    val isEditable: Boolean get() = !locked && status.isEditable
}

/** One day of a week. */
data class TimecardDay(
    val date: Long?,
    val dayType: DayType,
    val callTime: String?,
    val wrapTime: String?,
    val breakMinutes: Int = 0,
    val workedHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val allowances: List<Allowance> = emptyList(),
    val note: String?,
)

/** What kind of day this was, which decides how it is paid. */
enum class DayType(val wire: String, val label: String) {
    Worked("worked", "Worked"),
    Travel("travel", "Travel"),
    Holiday("holiday", "Holiday"),
    Rest("rest", "Rest day"),
    Sick("sick", "Sick"),
    Idle("idle", "Idle"),
    NotWorked("not_worked", "Not worked"),
    Unknown("", "—"),
    ;

    /** Whether hours entered on this day count towards pay. */
    val isPaidWork: Boolean get() = this == Worked || this == Travel

    companion object {
        fun from(wire: String?): DayType {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** A per-day or per-week payment on top of the rate. */
data class Allowance(
    val code: String,
    val label: String,
    val amount: Double,
    val quantity: Double = 1.0,
) {
    val total: Double get() = amount * quantity
}

/**
 * An allowance the production offers, as its configuration defines it.
 *
 * The catalogue matters because allowances are claimed by code, not by name:
 * "meal penalty" on one production is `MP` and on another `MEAL_PEN`, and a
 * free-text claim is one payroll cannot process.
 */
data class AllowanceType(
    val code: String,
    val label: String,
    /** The production's set amount. Null where the claimant states it. */
    val defaultAmount: Double?,
    val basis: AllowanceBasis = AllowanceBasis.Day,
    val appliesTo: AllowanceScope = AllowanceScope.Any,
    /** Where the production posts it. Carried through to payroll's coding. */
    val nominalCode: String? = null,
) {
    /**
     * Whether a second claim on the same day is meaningful.
     *
     * Only a per-unit allowance is: mileage at 45p a mile is claimed by the
     * quantity, whereas a meal penalty is a thing that either happened that day
     * or did not.
     */
    val perUnit: Boolean get() = basis == AllowanceBasis.Mile

    /** A fresh claim of this allowance, at its configured amount. */
    fun claim(): Allowance = Allowance(
        code = code,
        label = label,
        amount = defaultAmount ?: 0.0,
        quantity = 1.0,
    )
}

/**
 * How often an allowance is paid.
 *
 * `3in5` is the one that surprises people: a five-day week that includes three
 * qualifying days pays the whole week's allowance, not three days of it.
 */
enum class AllowanceBasis(val wire: String) {
    Day("day"),
    Week("week"),
    ThreeInFive("3in5"),
    Mile("mile"),
    ;

    companion object {
        /**
         * Unknown values become [Day] rather than dropping the row: the
         * production retired Per Hour, Per Night and Per Event in July 2026 and
         * saved rows still carry them, so a strict read would hide allowances
         * the crew is still owed.
         */
        fun from(wire: String?): AllowanceBasis =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Day
    }
}

/** Which days an allowance may be claimed on. */
enum class AllowanceScope(val wire: String) {
    Shoot("shoot"),
    NonShoot("non_shoot"),
    Any("shoot_non_shoot"),
    ;

    companion object {
        fun from(wire: String?): AllowanceScope =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Any
    }
}

/** Something taken off the week's gross. */
data class Deduction(
    val id: String?,
    val label: String,
    val amount: Double,
    val reason: String?,
)

/** Where a timecard is in the approval chain. */
enum class TimecardStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Submitted("submitted", "Submitted"),
    AwaitingApproval("awaiting_approval", "Awaiting approval"),
    Approved("approved", "Approved"),
    FinalApproved("final_approved", "Final approved"),
    Queried("queried", "Queried"),
    Rejected("rejected", "Rejected"),
    Locked("locked", "Locked"),
    SentToPayroll("sent_to_payroll", "With payroll"),
    Paid("paid", "Paid"),
    Unknown("", "Unknown"),
    ;

    val isEditable: Boolean get() = this == Draft || this == Queried || this == Rejected

    /** Cleared every gate and is ready for a payroll run. */
    val isPayable: Boolean get() = this == FinalApproved || this == Locked || this == SentToPayroll

    val isPaid: Boolean get() = this == Paid

    companion object {
        fun from(wire: String?): TimecardStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/** Who is looking at the timecard tool. */
data class TimecardViewer(
    val userId: String,
    val departmentIdentifier: String?,
    val designationIdentifier: String?,
    val metadata: TimecardMetadata = TimecardMetadata(),
    val enteredAsTool: Boolean = false,
) {
    val isAccountant: Boolean
        get() = !enteredAsTool && departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

    /** A head of department, who approves their department's weeks. */
    val isApprover: Boolean get() = metadata.isApprover

    /** Payroll, who take the final decision and lock the week. */
    val isFinalApprover: Boolean get() = metadata.isFinalApprover || isAccountant

    private companion object {
        const val ACCOUNTS = "accounts"
    }
}

/** What the server says this viewer may do, per `GET timecards/metadata`. */
data class TimecardMetadata(
    val isApprover: Boolean = false,
    val isFinalApprover: Boolean = false,
    val isCompleter: Boolean = false,
    /** Whether the production requires a second, final approval at all. */
    val requiresFinalApproval: Boolean = false,
    val disputesEnabled: Boolean = false,
    /** What may be claimed on this production. Empty until config loads. */
    val allowanceTypes: List<AllowanceType> = emptyList(),
)

/** A week as the crew member filled it in. */
data class TimecardDraft(
    val timecardId: String?,
    val weekStarting: Long?,
    val days: List<TimecardDay>,
    val notes: String = "",
) {
    val workedHours: Double get() = days.sumOf { it.workedHours }

    /** What the claimed allowances come to across the week. */
    val allowanceTotal: Double get() = days.sumOf { day -> day.allowances.sumOf { it.total } }

    /** The first reason this week cannot be submitted, or null. */
    fun validationError(): String? = when {
        weekStarting == null -> "Pick the week this timecard covers."
        days.isEmpty() -> "A timecard needs at least one day."
        // A week of nothing but rest days is a real submission — a runner on
        // standby still files one — so hours are not required outright. Times
        // are, on any day claimed as worked.
        days.any { it.dayType.isPaidWork && it.workedHours <= 0 } ->
            "Every worked day needs its hours."

        // An allowance claimed at nothing is a claim payroll cannot pay and
        // cannot query — it looks deliberate. Caught here rather than there.
        days.any { day -> day.allowances.any { it.amount <= 0 } } ->
            "Every allowance needs an amount."

        // An allowance on a day nobody worked is almost always a mis-click on
        // the row above, and it is paid before anyone notices.
        days.any { day -> !day.dayType.isPaidWork && day.allowances.isNotEmpty() } ->
            "Allowances can only be claimed on a day that was worked."

        else -> null
    }
}

/** One line of a timecard's audit trail. */
data class TimecardHistoryEntry(
    val action: String,
    val userId: String?,
    val note: String?,
    val at: Long?,
)

/** Everything the timecard tool asks the server for. */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface TimecardRepository {

    suspend fun metadata(): ZillitResult<TimecardMetadata>

    /** This viewer's own weeks. */
    suspend fun myTimecards(): ZillitResult<List<Timecard>>

    /** Weeks routed to this viewer to approve. */
    suspend fun approvalQueue(): ZillitResult<List<Timecard>>

    /** Every week payroll is processing for [weekStarting]. */
    suspend fun payrollProcessing(weekStarting: String): ZillitResult<List<Timecard>>

    /** Weeks that have not been filed at all — the chase list. */
    suspend fun outstanding(): ZillitResult<List<Timecard>>

    suspend fun timecard(id: String): ZillitResult<Timecard>

    suspend fun history(id: String): ZillitResult<List<TimecardHistoryEntry>>

    suspend fun save(draft: TimecardDraft): ZillitResult<Unit>

    suspend fun submit(id: String): ZillitResult<Unit>

    suspend fun approve(id: String, note: String?): ZillitResult<Unit>

    suspend fun reject(id: String, reason: String): ZillitResult<Unit>

    suspend fun query(id: String, note: String): ZillitResult<Unit>

    /** Payroll's own approval, after the department's. */
    suspend fun finalApprove(id: String): ZillitResult<Unit>

    /** Freezes the week so nothing can change under a payroll run. */
    suspend fun lock(id: String): ZillitResult<Unit>

    suspend fun markPaid(id: String): ZillitResult<Unit>

    suspend fun addDeduction(id: String, label: String, amount: Double, reason: String?): ZillitResult<Unit>

    suspend fun removeDeduction(id: String, deductionId: String): ZillitResult<Unit>

    /** Approve or reject several weeks at once — a department at a time. */
    suspend fun approveAll(ids: List<String>): ZillitResult<Unit>

    suspend fun lockAll(ids: List<String>): ZillitResult<Unit>

    /** What this production allows to be claimed, from its timecard config. */
    suspend fun allowanceTypes(): ZillitResult<List<AllowanceType>>
}
