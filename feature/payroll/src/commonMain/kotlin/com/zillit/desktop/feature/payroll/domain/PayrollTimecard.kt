package com.zillit.desktop.feature.payroll.domain

/**
 * One weekly timecard as payroll reads it.
 *
 * ## Three shapes, one model
 *
 * The payroll service answers the same timecard three ways: the full document
 * (`/timecards/weekly/{id}`, the run's `/weekly/{ws}/processing`), the
 * processing projection (`/weekly/processing`, which trades `days[]` for a
 * `day_summary[]`), and the slim paid list (`/weekly/{ws}/paid`, scalars only).
 * Every field a projection leaves out is empty here rather than guessed, and
 * the figures below say which they fall back to — the web's own order.
 */
data class PayrollTimecard(
    val id: String,
    val userId: String,
    val status: TimecardStatus,
    val weekStarting: Long? = null,
    val companyId: String? = null,
    val currency: String? = null,
    /** Stamped when the timecard was posted; null before. */
    val effectiveDate: Long? = null,
    /** The basic-pay nominal code, per week. */
    val nominalCode: String? = null,
    /** `buyout` for a buy-out deal. */
    val dealType: String? = null,
    /** The deal's employment status (`paye`, `schedule_d`, `loanout`…). */
    val employmentStatus: String? = null,
    /** An invoice the crew member attached — a Schedule D or loan-out bill. */
    val attachment: String? = null,
    // -- scalars (slim projections) --
    val basicPay: Double = 0.0,
    val overtimePay: Double = 0.0,
    val totalAllowances: Double = 0.0,
    val totalDays: Int = 0,
    val fringesTotal: Double = 0.0,
    val claimsTotalScalar: Double? = null,
    val deductionsTotalScalar: Double? = null,
    val weeklyAllowancesRentalsTotal: Double? = null,
    /** The outstanding projection's split scalars — it carries no day axis at all. */
    val outstanding: OutstandingFigures? = null,
    val daySummary: List<DaySummary> = emptyList(),
    // -- the full document --
    val days: List<TimecardDay> = emptyList(),
    val weeklyAllowances: List<PayLine> = emptyList(),
    val weeklyExtras: List<PayLine> = emptyList(),
    val claims: List<ClaimLine> = emptyList(),
    val deductions: List<DeductionLine> = emptyList(),
    val fringes: List<FringeLine> = emptyList(),
    val history: List<AuditEvent> = emptyList(),
) {
    /** Claims: the server's total when it sends one, else the rows. */
    val claimsTotal: Double get() = claimsTotalScalar ?: claims.sumOf { it.amount }

    /** Deductions: the server's total when it sends one, else each row's `actual_amount`. */
    val deductionsTotal: Double get() = deductionsTotalScalar ?: deductions.sumOf { it.amount }

    /** Weekly allowances and rentals, `rate × qty` per row, else the server's scalar. */
    val weeklyAllowRent: Double
        get() = if (weeklyAllowances.isNotEmpty()) {
            weeklyAllowances.sumOf { it.lineAmount }
        } else {
            weeklyAllowancesRentalsTotal ?: 0.0
        }

    /** Upgrades and extras, weekly and daily. */
    val extrasTotal: Double
        get() = weeklyExtras.sumOf { it.lineAmount } + days.sumOf { day -> day.extras.sumOf { it.lineAmount } }

    /**
     * The week's gross as the run grid computes it (`PayrollRunModule.jsx`
     * 543-553): the day summaries when the projection carries them, else the
     * three scalars — plus weekly allowances, extras and claims either way.
     */
    val gross: Double
        get() {
            val dayPart = if (daySummary.isNotEmpty()) {
                daySummary.sumOf { it.basic + it.ots + it.allowancesRentals }
            } else {
                basicPay + overtimePay + totalAllowances
            }
            return round2(dayPart + weeklyAllowRent + extrasTotal + claimsTotal)
        }

    /** Gross less deductions. */
    val net: Double get() = round2(gross - deductionsTotal)

    /**
     * The history queue's gross (`AccountantPayrollModule.jsx` 280-284): the
     * slim paid list carries only the three scalars and the claims.
     */
    val slimGross: Double get() = basicPay + overtimePay + totalAllowances + claimsTotal

    companion object {
        fun round2(value: Double): Double = kotlin.math.round(value * HUNDRED) / HUNDRED
        private const val HUNDRED = 100.0
    }
}

/**
 * The outstanding list's scalars (`/payroll-processing/outstanding`): OT,
 * daily and weekly allowances and rentals, each pre-summed by the server.
 */
data class OutstandingFigures(
    val ots: Double,
    val dailyAllowances: Double,
    val dailyRentals: Double,
    val weeklyAllowances: Double,
    val weeklyRentals: Double,
) {
    val allowancesAndRentals: Double get() = dailyAllowances + dailyRentals + weeklyAllowances + weeklyRentals
}

/** One day of the processing projection. */
data class DaySummary(
    val date: Long?,
    val dayType: String?,
    val basic: Double,
    val ots: Double,
    val allowancesRentals: Double,
    val minutesWorked: Int = 0,
)

/** One day of the full timecard. */
data class TimecardDay(
    val date: Long?,
    /** 1-7 within the week. */
    val dayNumber: Int? = null,
    val dayType: String?,
    val basicHours: Double = 0.0,
    /** Where the times came from — GPS, manual. */
    val source: String? = null,
    val callTime: Long? = null,
    val wrapTime: Long? = null,
    val loginTime: Long? = null,
    val logoutTime: Long? = null,
    val minutesWorked: Int = 0,
    val rates: List<PayLine> = emptyList(),
    val allowances: List<PayLine> = emptyList(),
    val extras: List<PayLine> = emptyList(),
    val status: String? = null,
) {
    val basicPay: Double get() = rates.filter { it.isBasic }.sumOf { it.rateAmount }
    val otTotal: Double get() = rates.filterNot { it.isBasic }.sumOf { it.rateAmount }
    val allowTotal: Double get() = allowances.sumOf { it.rateAmount }
    val dayTotal: Double get() = basicPay + otTotal + allowTotal

    /** A day off — no day type, or `REST`. */
    val isOff: Boolean get() = dayType.isNullOrBlank() || dayType.equals(REST, ignoreCase = true)

    companion object {
        const val REST = "REST"
    }
}

/**
 * One pay line — a rate, an OT, an allowance, a rental or an extra.
 *
 * [rateAmount] is the line's money for a daily line; weekly lines multiply it
 * by [qty] — the web's `lineAmount`, where a missing quantity is one and a
 * genuine zero stays zero.
 */
data class PayLine(
    val id: String? = null,
    val identifier: String? = null,
    val label: String? = null,
    val rawLabel: String? = null,
    val rateType: String? = null,
    val rateAmount: Double = 0.0,
    val qty: Double? = null,
    /** Minutes worked for an OT line. */
    val workDuration: Int = 0,
    val currency: String? = null,
    val nominalCode: String? = null,
    val isRental: Boolean = false,
) {
    val isBasic: Boolean get() = identifier.equals(BASIC, ignoreCase = true)

    val lineAmount: Double get() = rateAmount * (qty ?: 1.0)

    /** What the line is called: its label, the raw label, or the pay code. */
    val displayLabel: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: rawLabel?.takeIf { it.isNotBlank() }
            ?: PayCode.label(identifier)

    companion object {
        const val BASIC = "basic"
    }
}

/** A claim attached to the week — a cash-expense batch or a manual line. */
data class ClaimLine(
    val id: String?,
    val name: String,
    val amount: Double,
    val currency: String?,
    val nominalCode: String?,
    /** Set when the claim came from a cash-expense batch. */
    val cashExpenseBatchId: String?,
)

/** A deduction row. [amount] is the server's `actual_amount`. */
data class DeductionLine(
    val id: String?,
    val label: String,
    /** `flat` or `percentage`. */
    val rateType: String,
    val rateAmount: Double,
    val amount: Double,
    val nominalCode: String?,
    /** False for a deal-memo deduction, which cannot be removed here. */
    val isCustom: Boolean = true,
) {
    val isPercentage: Boolean get() = rateType.equals(PERCENTAGE, ignoreCase = true)

    companion object {
        const val PERCENTAGE = "percentage"
    }
}

/** An employer fringe — holiday pay, NIC — from the timecard. */
data class FringeLine(val label: String, val amount: Double)

/** One entry of the timecard's own history — the audit trail. */
data class AuditEvent(
    val at: Long?,
    val byUserId: String?,
    val action: String?,
    val from: String?,
    val to: String?,
    val note: String?,
    val detail: String?,
) {
    /**
     * What happened, in the web's words: the server's `detail` when it wrote
     * one, else the action with its from/to and note (`AccountantPayrollModule.jsx`
     * 1930-1934).
     */
    fun describe(fromWord: String, toWord: String): String =
        detail?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                action?.takeIf { it.isNotBlank() },
                from?.takeIf { it.isNotBlank() }?.let { "$fromWord $it" },
                to?.takeIf { it.isNotBlank() }?.let { "$toWord $it" },
                note?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
}

/** Pay-code short names — the web's `PAYCODE_LABEL`. */
object PayCode {
    private val LABELS = mapOf(
        "basic" to "BASIC",
        "overtime" to "OT",
        "camera_ot" to "CAM-OT",
        "non_camera_ot" to "NC-OT",
        "pre_call_ot" to "PRE-OT",
        "meal_penalty" to "MEAL",
        "meal_break_curtailed" to "MBC",
        "broken_turnaround" to "BTA",
        "ot_7th_day" to "7TH-OT",
        "ot_6th_day" to "6TH-OT",
        "night_premium" to "NIGHT",
    )

    fun label(identifier: String?): String {
        val key = identifier.orEmpty().lowercase()
        return LABELS[key] ?: key.uppercase().replace('_', '-').ifEmpty { "—" }
    }
}
