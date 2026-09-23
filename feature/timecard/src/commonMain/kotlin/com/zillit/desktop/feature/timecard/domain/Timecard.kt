package com.zillit.desktop.feature.timecard.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/**
 * One crew member's week.
 *
 * The unit of everything in this tool: hours are entered against a week,
 * approved as a week, and paid as a week. A day never moves on its own.
 */
@Serializable
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
    /**
     * True when this row came from a route that only ever answers with the
     * caller's own weeks. The `my-summary` projection carries **no user id at
     * all** (the web's `toWeekRow` reads `_id`, `week_starting`, totals and
     * status and nothing about the owner — `MyTimecardsModule.jsx:126-167`),
     * so ownership there is by construction, not by comparing ids.
     */
    val ownedByViewer: Boolean = false,
    /**
     * Set when this week exists only on this computer so far — saved while
     * offline and waiting in the outbox. It has no server id ([id] is the
     * local operation's) and nothing can be done to it but wait or submit it
     * into the same queue.
     */
    val local: LocalWeek? = null,
) {
    val isLocalOnly: Boolean get() = local != null

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

/** A week that has not reached the server: where it is in the outbox. */
@Serializable
data class LocalWeek(
    val operationId: String,
    /** True once the server refused it and it needs the user; false while it waits or sends. */
    val failed: Boolean,
    val error: String? = null,
    /** True when a submit is queued behind the save. */
    val submitQueued: Boolean = false,
)

/** One day of a week. */
@Serializable
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

/**
 * What kind of day this was, which decides how it is paid.
 *
 * The wire vocabulary is the web's, where day types fall into three
 * categories (`timecardDayCalc.js:32-57`): shoot days (`SWD`/`CWD`/`SCWD`),
 * flat-pay days (`Travel`, `Prep`, …) and non-paid days (`REST`, `Holiday`,
 * `Sick`). On top of those, eight timecard-only types travel as codes while
 * the web UI keeps their labels (`dayTypeWire.js:16-25` — `IDLE_DAY`,
 * `SICK_PAID`, `BANK_HOLIDAY`, …). An untouched day has **no** type at all:
 * the web's create skeleton sends `day_type: null`
 * (`WeeklyTimecardModule.jsx:4796`), which is what [NotWorked] serialises to.
 */
@Serializable
enum class DayType(val wire: String?, private val labelKey: String?) {
    /** A standard working (shoot) day — the web's `SWD`. */
    Worked("SWD", S.desktop_day_type_worked),
    Travel("Travel", S.desktop_day_type_travel),
    Holiday("Holiday", S.desktop_holiday),
    // Both phones renamed the display string; the wire code is untouched
    // (web `data/dayTypes.js:43`, Android timecard).
    Rest("REST", S.desktop_day_type_day_off),
    Sick("Sick", S.desktop_day_type_sick),
    /** The timecard-only `IDLE_DAY` code (`dayTypeWire.js:18`). */
    Idle("IDLE_DAY", S.desktop_day_type_idle),
    /** No type picked: the day exists in the grid but says nothing yet. */
    NotWorked(null, S.desktop_day_type_not_worked),
    Unknown(null, null),
    ;

    val label: String get() = labelKey?.let { str(it) } ?: "—"

    /** Whether hours entered on this day count towards pay. */
    val isPaidWork: Boolean get() = this == Worked || this == Travel

    /**
     * Whether this day is *known* to pay nothing.
     *
     * The web's `NON_PAID_DAY_TYPES` names four and no more — `REST`,
     * `Holiday`, `Sick`, `Sick (Unpaid)`. Everything else it knows about
     * pays, including the flat-pay `Prep`/`Wrap`/`Post`/`Turnaround` family
     * this port has no bucket for and files under [Unknown].
     *
     * Deliberately not `!isPaidWork`: a day whose type this port does not
     * model is not a day nobody worked, and refusing what it does not
     * understand blocked a whole week from being filed over a prep-day
     * allowance.
     */
    val isKnownUnpaid: Boolean get() = this == Rest || this == Holiday || this == Sick

    companion object {
        /**
         * Reads codes and legacy labels alike: the wire carries codes for the
         * eight timecard-only types and pass-through labels for everything
         * else (`dayTypeWire.js:10-13`), and older rows saved label-first.
         * Web-only vocabulary this port has no bucket for — the flat-pay
         * `Prep`/`Wrap`/`Post` family, custom deal codes — degrades to
         * [Unknown] rather than mislabelling the day.
         */
        fun from(wire: String?): DayType {
            val value = wire?.trim()?.uppercase().orEmpty()
            if (value.isEmpty()) return NotWorked
            return when (value) {
                "SWD", "CWD", "SCWD", "HALF_DAY", "HALF DAY" -> Worked
                "TRAVEL" -> Travel
                "HOLIDAY", "BANK_HOLIDAY", "BANK HOLIDAY", "PUBLIC_HOLIDAY", "PUBLIC HOLIDAY" -> Holiday
                "REST" -> Rest
                "SICK", "SICK_PAID", "SICK_UNPAID", "SICK_SSP",
                "SICK (PAID)", "SICK (UNPAID)", "SICK (SSP)",
                -> Sick

                "IDLE_DAY", "IDLE DAY", "IDLE" -> Idle
                else -> Unknown
            }
        }
    }
}

/** A per-day or per-week payment on top of the rate. */
@Serializable
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
@Serializable
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
@Serializable
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
@Serializable
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
@Serializable
data class Deduction(
    val id: String?,
    val label: String,
    val amount: Double,
    val reason: String?,
)

/**
 * Where a timecard is in the approval chain.
 *
 * The wire vocabulary is the server enum the web's status maps are keyed on
 * (`lib/timecardStatus.js:12-51`): draft → awaiting_approval / submitted /
 * pending → queried / rejected → approved → final_approved → locked → paid →
 * unpaid / posted. There is no `sent_to_payroll` status — the web's
 * send-to-payroll-run action flips the week to `approved`
 * (`timecards.js:194-212`).
 */
@Serializable
enum class TimecardStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    Submitted("submitted", S.txt_submitted),
    AwaitingApproval("awaiting_approval", S.av_subtab_awaiting_approval),
    /** The submitted/awaiting bucket some surfaces roll up to (`timecardStatus.js:20-22`). */
    Pending("pending", S.pending),
    Approved("approved", S.approved),
    /** The web renders this "ACCT Approved"; the wire enum stays `final_approved`. */
    FinalApproved("final_approved", S.cs_status_final_approved),
    Queried("queried", S.ah_queried),
    Rejected("rejected", S.rejected),
    Locked("locked", S.docusign_prop_locked),
    Paid("paid", S.desktop_paid),
    /** A previously paid week whose payment was reversed (`timecardStatus.js:34`). */
    Unpaid("unpaid", S.desktop_unpaid),
    /** Journal entries written to the General Ledger — terminal (`timecardStatus.js:35-39`). */
    Posted("posted", S.ah_step_posted),
    Unknown("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    val isEditable: Boolean get() = this == Draft || this == Queried || this == Rejected

    /** Cleared every gate and is ready for a payroll run. */
    val isPayable: Boolean get() = this == FinalApproved || this == Locked

    val isPaid: Boolean get() = this == Paid || this == Posted

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
    /**
     * The department guess OR-ed with the server's own `is_accountant` flag,
     * the way the web merges its auth signal with `timecards/metadata`
     * (`TimecardMetadataContext.jsx:96-99`). Entering from the tools grid
     * still means acting as crew, whatever either source says.
     */
    val isAccountant: Boolean
        get() = !enteredAsTool &&
            (departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true || metadata.isAccountant)

    /** A head of department, who approves their department's weeks. */
    val isApprover: Boolean get() = metadata.isApprover

    /** Payroll, who take the final decision and lock the week. */
    val isFinalApprover: Boolean get() = metadata.isFinalApprover || isAccountant

    private companion object {
        const val ACCOUNTS = "accounts"
    }
}

/**
 * What the server says this viewer may do.
 *
 * Merged from two reads the way the web spreads them across
 * `TimecardMetadataContext` and `usePayrollMetadata`: `GET
 * payroll/timecards/metadata` carries `is_approver` / `is_accountant` /
 * `is_completer` and `pay_period` (`TimecardMetadataContext.jsx:25-43`), while
 * `is_final_approver` — the payroll-approvers allowlist — only ever comes from
 * `GET payroll/metadata` (`usePayrollMetadata.js:54-75`).
 */
@Serializable
data class TimecardMetadata(
    val isApprover: Boolean = false,
    val isFinalApprover: Boolean = false,
    val isCompleter: Boolean = false,
    /** The server's own accountant flag, OR-ed into [TimecardViewer.isAccountant]. */
    val isAccountant: Boolean = false,
    /**
     * The production's pay-period start day, ISO 1=Mon … 7=Sun
     * (`pay_period.start_day_of_week`, defaulted exactly as the web defaults
     * it — `TimecardMetadataContext.jsx:41-43`, `usePayrollMetadata.js:172-175`).
     */
    val payPeriodStartDay: Int = 1,
    /** Whether the production requires a second, final approval at all. */
    val requiresFinalApproval: Boolean = false,
    val disputesEnabled: Boolean = false,
    /** What may be claimed on this production. Empty until config loads. */
    val allowanceTypes: List<AllowanceType> = emptyList(),
)

/** A week as the crew member filled it in. */
@Serializable
data class TimecardDraft(
    val timecardId: String?,
    val weekStarting: Long?,
    val days: List<TimecardDay>,
    val notes: String = "",
    /**
     * The owner's department, sent on CREATE only: the web stamps
     * `department_id` so department-scoped approval tiers resolve
     * (`WeeklyTimecardModule.jsx:4788-4791`). Null is the web's own case for
     * a user without one.
     */
    val departmentId: String? = null,
) {
    val workedHours: Double get() = days.sumOf { it.workedHours }

    /** What the claimed allowances come to across the week. */
    val allowanceTotal: Double get() = days.sumOf { day -> day.allowances.sumOf { it.total } }

    /** The first reason this week cannot be submitted, or null. */
    fun validationError(): String? = when {
        weekStarting == null -> str(S.desktop_timecard_pick_week_error)
        days.isEmpty() -> str(S.desktop_timecard_needs_one_day)
        // A week of nothing but rest days is a real submission — a runner on
        // standby still files one — so hours are not required outright. Times
        // are, on any day claimed as worked.
        days.any { it.dayType.isPaidWork && it.workedHours <= 0 } ->
            str(S.desktop_timecard_worked_day_needs_hours)

        // An allowance claimed at nothing is a claim payroll cannot pay and
        // cannot query — it looks deliberate. Caught here rather than there.
        days.any { day -> day.allowances.any { it.amount <= 0 } } ->
            str(S.desktop_timecard_allowance_needs_amount)

        // An allowance on a day nobody worked is almost always a mis-click on
        // the row above, and it is paid before anyone notices. Only days this
        // port *knows* pay nothing count — see [DayType.isKnownUnpaid]; an
        // unmodelled flat-pay type must not block the week.
        days.any { day ->
            (day.dayType.isKnownUnpaid || day.dayType == DayType.NotWorked) && day.allowances.isNotEmpty()
        } ->
            str(S.desktop_timecard_allowance_on_worked_day)

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

    /**
     * Socket announcements that a week changed somewhere — another client's
     * submit, decision, lock or payment, answered with a reload of whatever
     * page is open rather than an in-place patch (the web's `ah:timecard:*`
     * refetch pattern). Defaulted empty for tests and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    suspend fun metadata(): ZillitResult<TimecardMetadata>

    /** This viewer's own weeks. */
    suspend fun myTimecards(): ZillitResult<List<Timecard>>

    /** Weeks routed to this viewer to approve. */
    suspend fun approvalQueue(): ZillitResult<List<Timecard>>

    /**
     * Every week payroll is processing for [weekStarting] — **epoch millis**
     * of the pay-period start's midnight in the caller's zone, exactly the
     * value the web puts in the path (`timecards.js:68-70`, computed via
     * `startOfPeriodTz(now, browserTz(), payPeriodStartDay)` —
     * `AccountantPayrollModule.jsx:949-969`). An ISO date string here is
     * refused as `timecard_invalid_week_starting`.
     */
    suspend fun payrollProcessing(weekStarting: Long): ZillitResult<List<Timecard>>

    /** Weeks that have not been filed at all — the chase list. */
    suspend fun outstanding(): ZillitResult<List<Timecard>>

    suspend fun timecard(id: String): ZillitResult<Timecard>

    suspend fun history(id: String): ZillitResult<List<TimecardHistoryEntry>>

    /**
     * Creates or updates a week; answers the server id of the saved week when
     * it is known, so a queued submit — or a note — can follow it.
     */
    suspend fun save(draft: TimecardDraft): ZillitResult<String?>

    /**
     * Appends one note to a week (`POST /weekly/:id/notes`, body `{note}` —
     * `timecards.js:114-126`). Owner-only server-side; notes never ride the
     * save body.
     */
    suspend fun addNote(id: String, note: String): ZillitResult<Unit>

    suspend fun submit(id: String): ZillitResult<Unit>

    suspend fun approve(id: String, note: String?): ZillitResult<Unit>

    suspend fun reject(id: String, reason: String): ZillitResult<Unit>

    suspend fun query(id: String, note: String): ZillitResult<Unit>

    /** Payroll's own approval, after the department's. */
    suspend fun finalApprove(id: String): ZillitResult<Unit>

    /** Freezes the week so nothing can change under a payroll run. */
    suspend fun lock(id: String): ZillitResult<Unit>

    suspend fun markPaid(id: String): ZillitResult<Unit>

    /**
     * Adds one deduction row. The wire takes a rate, not an amount —
     * `{label, rate_type, rate_amount, nominal_code}` — and the server
     * computes `actual_amount` from it (`timecards.js:138-151`,
     * `AddDeductionModal.jsx:127-132`). There is no reason field.
     */
    suspend fun addDeduction(id: String, label: String, amount: Double, nominalCode: String?): ZillitResult<Unit>

    suspend fun removeDeduction(id: String, deductionId: String): ZillitResult<Unit>

    /** Approve or reject several weeks at once — a department at a time. */
    suspend fun approveAll(ids: List<String>): ZillitResult<Unit>

    suspend fun lockAll(ids: List<String>): ZillitResult<Unit>

    /** What this production allows to be claimed, from its timecard config. */
    suspend fun allowanceTypes(): ZillitResult<List<AllowanceType>>
}
