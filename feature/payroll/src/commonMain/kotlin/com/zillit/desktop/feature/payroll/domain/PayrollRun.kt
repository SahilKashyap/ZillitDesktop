package com.zillit.desktop.feature.payroll.domain

import kotlin.math.max

/**
 * The employment type the Run's sidebar filters on — the web's
 * `mapEmpStatusToEmpType` (`PayrollRunModule.jsx` 2487-2502), in its order.
 */
enum class Employment(val wire: String) {
    Paye("PAYE"), ScheduleD("SCHD"), LoanOut("LOAN"), BuyOut("BUYOUT");

    companion object {
        fun of(employmentStatus: String?, dealType: String?): Employment {
            val deal = dealType?.trim()?.lowercase()
            if (deal == "buyout" || deal == "buy-out") return BuyOut
            val id = employmentStatus?.trim()?.lowercase().orEmpty()
            return when {
                id.isEmpty() -> Paye
                "loanout" in id || "loan-out" in id || id == "ltd" || id == "ltd-inside" -> LoanOut
                "schedule" in id || "sch-d" in id || "1099" in id -> ScheduleD
                else -> Paye
            }
        }
    }
}

/**
 * The Run's coarse status bucket — the web's `STATUS_TO_OVERALL`, where
 * everything it does not name (including `final_approved` and `locked`) reads
 * as pending.
 */
enum class OverallStatus {
    Draft, Pending, Received, Queried, Approved, Paid, Unpaid, Processed;

    companion object {
        fun of(status: TimecardStatus): OverallStatus = when (status) {
            TimecardStatus.Draft -> Draft
            TimecardStatus.Submitted -> Received
            TimecardStatus.Queried, TimecardStatus.Rejected -> Queried
            TimecardStatus.Approved -> Approved
            TimecardStatus.Paid -> Paid
            TimecardStatus.Unpaid -> Unpaid
            TimecardStatus.Posted, TimecardStatus.Processed -> Processed
            else -> Pending
        }
    }
}

/**
 * One crew row of the Payroll Run grid (`mapTimecardToCrew`, 471-698).
 *
 * The figures are the web's, estimates included: where the server sends no
 * fringes the holiday pay is 12.07% of gross and employer NIC 15% above the
 * weekly threshold — the grid's own "where applicable" projection.
 */
data class RunRow(
    val timecard: PayrollTimecard,
    val name: String,
    /** A designation label key, or blank. */
    val role: String,
    /** A department label key, or blank for unassigned. */
    val department: String,
) {
    val id: String get() = timecard.id
    val status: TimecardStatus get() = timecard.status
    val overall: OverallStatus get() = OverallStatus.of(timecard.status)
    val employment: Employment get() = Employment.of(timecard.employmentStatus, timecard.dealType)
    val gross: Double get() = timecard.gross
    val claimsTotal: Double get() = timecard.claimsTotal
    val allowances: Double get() = PayrollTimecard.round2(timecard.totalAllowances + timecard.weeklyAllowRent)

    val holidayPay: Double
        get() = PayrollTimecard.round2(
            if (timecard.fringesTotal > 0) timecard.fringesTotal else gross * HOLIDAY_PAY_RATE,
        )

    val employerNic: Double
        get() = if (timecard.fringesTotal > 0) {
            0.0
        } else {
            PayrollTimecard.round2(
                max(gross - NIC_THRESHOLD * (timecard.totalDays / WORKING_DAYS.toDouble()), 0.0) * NIC_RATE,
            )
        }

    val totalCost: Double get() = PayrollTimecard.round2(gross + holidayPay + employerNic)

    /** Which of the week's seven days were worked. */
    val daysWorked: List<Boolean>
        get() {
            val start = timecard.weekStarting ?: return List(DAYS) { false }
            val worked = BooleanArray(DAYS)
            val summaries = timecard.daySummary.map { Triple(it.date, it.dayType, it.hasWork) }
            val entries = summaries.ifEmpty {
                timecard.days.map { day ->
                    Triple(
                        day.date,
                        day.dayType,
                        day.minutesWorked > 0 || day.rates.isNotEmpty(),
                    )
                }
            }
            entries.forEach { (date, type, hasWork) ->
                if (date == null || !hasWork) return@forEach
                if (type.isNullOrBlank() || type == TimecardDay.REST) return@forEach
                val index = kotlin.math.round((date - start) / PayPeriod.DAY_MILLIS.toDouble()).toInt()
                if (index in 0 until DAYS) worked[index] = true
            }
            return worked.toList()
        }

    private val DaySummary.hasWork: Boolean
        get() = minutesWorked > 0 || basic + ots + allowancesRentals > 0

    /** Crew, role and department, lower-cased — what the Run's search matches. */
    fun matches(query: String, roleLabel: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || name.lowercase().contains(q) || roleLabel.lowercase().contains(q)
    }

    companion object {
        const val DAYS = 7
        private const val HOLIDAY_PAY_RATE = 0.1207

        /** [HOLIDAY_PAY_RATE] as the Run's holiday-pay tile prints it. */
        const val HOLIDAY_PAY_LABEL = "12.07%"
        private const val NIC_THRESHOLD = 175.0
        private const val NIC_RATE = 0.15
        private const val WORKING_DAYS = 5
    }
}

/** A bulk transition the Run toolbar offers. */
enum class RunAction { FinalApprove, FinalApproveAndLock, Lock, MarkPaid }

/**
 * What the ticked rows could move, by raw status — the web's `selectionStats`
 * (`PayrollRunModule.jsx` 6403-6447).
 */
data class RunSelection(
    val total: Int,
    val approvedIds: List<String>,
    val finalApprovedIds: List<String>,
    val lockedIds: List<String>,
    val unpaidIds: List<String>,
) {
    /** Locked and unpaid rows are both ready to mark paid; the server takes both. */
    val paidEligibleIds: List<String> get() = lockedIds + unpaidIds

    /**
     * The approval button a viewer is offered for this selection, with how
     * many rows it would move — or null when it would move none. The web's
     * `PayrollRunToolbar` matrix (2617-2647).
     */
    fun approvalFor(viewer: PayrollViewer): Pair<RunAction, Int>? {
        if (total == 0) return null
        val offer = when {
            viewer.isFinalApprover && viewer.isPayrollAccountant -> approverAccountantOffer()
            viewer.isFinalApprover -> approvedIds.offer(RunAction.FinalApprove)
            viewer.isPayrollAccountant -> finalApprovedIds.offer(RunAction.Lock)
            else -> null
        }
        return offer?.takeIf { it.second > 0 }
    }

    /** Both roles: approve-and-lock what is approved, else lock what is final-approved. */
    private fun approverAccountantOffer(): Pair<RunAction, Int>? =
        approvedIds.offer(RunAction.FinalApproveAndLock) ?: finalApprovedIds.offer(RunAction.Lock)

    private fun List<String>.offer(action: RunAction): Pair<RunAction, Int>? =
        takeIf { it.isNotEmpty() }?.let { action to it.size }

    /** Mark Paid shows for any selection holding a locked or unpaid row — ungated on the web. */
    val offersMarkPaid: Boolean get() = total > 0 && paidEligibleIds.isNotEmpty()

    /** "Mark Locked → Paid" when nothing unpaid is in the selection. */
    val markPaidFromLockedOnly: Boolean get() = unpaidIds.isEmpty()

    fun count(action: RunAction): Int = when (action) {
        RunAction.FinalApprove, RunAction.FinalApproveAndLock -> approvedIds.size
        RunAction.Lock -> finalApprovedIds.size
        RunAction.MarkPaid -> paidEligibleIds.size
    }

    companion object {
        val None = RunSelection(0, emptyList(), emptyList(), emptyList(), emptyList())

        fun of(rows: List<RunRow>, selected: Set<String>): RunSelection {
            val picked = rows.filter { it.id in selected }
            fun ids(status: TimecardStatus) = picked.filter { it.status == status }.map { it.id }
            return RunSelection(
                total = selected.size,
                approvedIds = ids(TimecardStatus.Approved),
                finalApprovedIds = ids(TimecardStatus.FinalApproved),
                lockedIds = ids(TimecardStatus.Locked),
                unpaidIds = ids(TimecardStatus.Unpaid),
            )
        }
    }
}

/** The one action a Run row's button performs — first match wins. */
enum class RowAction { Override, FinalApprove, FinalApproveAndLock, Lock, MarkPaid, MarkUnpaid, View }

/**
 * The row's action, in the web's first-match order (`PayrollRunModule.jsx`
 * 3630-3771): override an unapproved week, final-approve (and lock) an
 * approved one, lock an accountant-approved one, pay a locked one — anyone —
 * or an unpaid one — the approver — and reverse a paid one.
 */
fun rowActionFor(status: TimecardStatus, viewer: PayrollViewer, canOverride: Boolean): RowAction = when {
    status.isAwaitingApproval && canOverride -> RowAction.Override
    status == TimecardStatus.Approved && viewer.isFinalApprover ->
        if (viewer.isPayrollAccountant) RowAction.FinalApproveAndLock else RowAction.FinalApprove
    status == TimecardStatus.FinalApproved && viewer.isPayrollAccountant -> RowAction.Lock
    status == TimecardStatus.Locked -> RowAction.MarkPaid
    status == TimecardStatus.Unpaid && viewer.isFinalApprover -> RowAction.MarkPaid
    status == TimecardStatus.Paid && viewer.isFinalApprover -> RowAction.MarkUnpaid
    else -> RowAction.View
}

/**
 * Whether [viewer] may perform [action] on a row at [status] — the rule the
 * view model re-checks before any row write, so an event that reaches the
 * handler without the button cannot do what the button would not.
 */
fun rowActionAllowed(action: RowAction, status: TimecardStatus, viewer: PayrollViewer, canOverride: Boolean): Boolean =
    action != RowAction.View && rowActionFor(status, viewer, canOverride) == action
