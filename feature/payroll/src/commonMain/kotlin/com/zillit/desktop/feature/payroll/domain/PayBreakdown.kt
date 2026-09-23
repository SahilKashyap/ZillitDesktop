package com.zillit.desktop.feature.payroll.domain

/**
 * Which pay bucket a line lands in — the web's `rateBucket`, which mirrors the
 * server's CSV bucketing so the export and the screen agree on what counts as
 * OT, a premium or a penalty.
 */
enum class PayBucket {
    Basic, Overtime, Premium, Turnaround, Penalty, Other, Allowance, Rental, Claim;

    /** Taxable earnings — what the payslip's Earnings section lists. */
    val isEarning: Boolean get() = this in EARNINGS

    /** OT, premiums, turnarounds and penalties: the amber figures. */
    val isEnhanced: Boolean get() = this == Overtime || this == Premium || this == Turnaround || this == Penalty

    companion object {
        private val EARNINGS = setOf(Basic, Overtime, Premium, Turnaround, Penalty, Other)

        fun of(line: PayLine): PayBucket = when {
            line.isBasic -> Basic
            else -> when (line.rateType?.lowercase()) {
                "overtime" -> Overtime
                "premium" -> Premium
                "turnaround" -> Turnaround
                "penalty" -> Penalty
                else -> Other
            }
        }

        fun ofAllowance(line: PayLine): PayBucket = if (line.isRental) Rental else Allowance
    }
}

/**
 * The deal memo's nominal codes, for the lines a timecard has not coded itself.
 *
 * The web reads the code off the week's own line first and falls back to the
 * matching row of the crew member's active deal (`nominalFor`,
 * `AccountantPayrollModule.jsx` 1507-1534). Row arrays match by `row_id` or
 * `source.id`; entitlement arrays by `id`.
 */
data class DealCoding(
    val basic: String? = null,
    val holidayPay: String? = null,
    /** `incl` — rates already include holiday pay; `excl` — paid on top. */
    val holidayPayTreatment: String? = null,
    val overtimes: Map<String, String> = emptyMap(),
    val premiums: Map<String, String> = emptyMap(),
    val turnarounds: Map<String, String> = emptyMap(),
    val penalties: Map<String, String> = emptyMap(),
    val extraFees: Map<String, String> = emptyMap(),
    val allowances: Map<String, String> = emptyMap(),
    val rentals: Map<String, String> = emptyMap(),
) {
    fun codeFor(bucket: PayBucket, identifier: String?, timecardBasic: String?): String {
        val id = identifier.orEmpty()
        val code = when {
            bucket == PayBucket.Basic -> timecardBasic?.takeIf { it.isNotBlank() } ?: basic
            id == HOLIDAY_PAY -> holidayPay
            bucket == PayBucket.Overtime -> overtimes[id]
            bucket == PayBucket.Premium -> premiums[id]
            bucket == PayBucket.Turnaround -> turnarounds[id]
            bucket == PayBucket.Penalty -> penalties[id]
            bucket == PayBucket.Allowance -> allowances[id] ?: extraFees[id]
            bucket == PayBucket.Rental -> rentals[id]
            else -> null
        }
        return code?.trim().orEmpty()
    }

    private companion object {
        const val HOLIDAY_PAY = "holiday_pay"
    }
}

/** One row of the Pay Code Breakdown table. */
data class BreakdownRow(
    /** "Mon 04 May", "Weekly", "Claims" — only on the first row of its group. */
    val day: String,
    val dayType: String?,
    val payCode: String,
    val nominal: String,
    val qty: String,
    val rate: Double,
    val gross: Double,
    val currency: String?,
    val bucket: PayBucket,
    /** CASH or MANUAL, on claim rows. */
    val source: String? = null,
)

/** The week's pay-code breakdown, the seven cards over it, and the payslip. */
data class PayBreakdown(
    val rows: List<BreakdownRow>,
    val totals: Map<PayBucket, Double>,
) {
    fun total(bucket: PayBucket): Double = totals[bucket] ?: 0.0

    val gross: Double get() = totals.values.sum()

    /** Basic through penalties and other: the taxable gross. */
    val taxableGross: Double get() = PayBucket.entries.filter { it.isEarning }.sumOf { total(it) }

    val allowancesAndRentals: Double get() = total(PayBucket.Allowance) + total(PayBucket.Rental)

    companion object {
        /**
         * Builds the breakdown the way the web's `breakdown` memo does: each
         * day's rates, then its allowances, then the weekly allowances, then
         * the claims — the day named on its first row only.
         */
        fun of(timecard: PayrollTimecard, deal: DealCoding?, weeklyWord: String, claimsWord: String): PayBreakdown {
            val coder = Coder(timecard, deal)
            val rows = timecard.days.sortedBy { it.date ?: 0L }.flatMap { dayRows(it, coder) } +
                weeklyRows(timecard, coder, weeklyWord) + claimRows(timecard, claimsWord)
            val totals = rows.groupBy { it.bucket }.mapValues { (_, group) -> group.sumOf { it.gross } }
            return PayBreakdown(rows, totals)
        }

        /** A line's own code, else the deal's for its bucket, else the timecard's on the basic line. */
        private class Coder(private val timecard: PayrollTimecard, private val deal: DealCoding?) {
            fun code(bucket: PayBucket, line: PayLine): String =
                line.nominalCode?.trim()?.takeIf { it.isNotEmpty() }
                    ?: deal?.codeFor(bucket, line.identifier, timecard.nominalCode).orEmpty()
                        .ifEmpty { if (bucket == PayBucket.Basic) timecard.nominalCode?.trim().orEmpty() else "" }
        }

        /** A day's rates then its allowances — the day named on its first row only. */
        private fun dayRows(day: TimecardDay, coder: Coder): List<BreakdownRow> {
            val rates = day.rates.map { line ->
                val bucket = PayBucket.of(line)
                BreakdownRow(
                    day = "", dayType = null, payCode = line.displayLabel, nominal = coder.code(bucket, line),
                    qty = if (bucket == PayBucket.Basic) hours(day.basicHours) else qtyHours(line.workDuration),
                    rate = line.rateAmount, gross = line.rateAmount, currency = line.currency, bucket = bucket,
                )
            }
            val allowances = day.allowances.map { line ->
                val bucket = PayBucket.ofAllowance(line)
                BreakdownRow(
                    day = "", dayType = null, payCode = line.displayLabel, nominal = coder.code(bucket, line),
                    qty = line.qty?.let(::trimQty) ?: "1", rate = line.rateAmount, gross = line.rateAmount,
                    currency = line.currency, bucket = bucket,
                )
            }
            val all = rates + allowances
            val lead = all.firstOrNull() ?: return all
            return listOf(lead.copy(day = day.date?.let(PayPeriod::dayLabel).orEmpty(), dayType = day.dayType)) +
                all.drop(1)
        }

        private fun weeklyRows(timecard: PayrollTimecard, coder: Coder, weeklyWord: String) =
            timecard.weeklyAllowances.mapIndexed { index, line ->
                val bucket = PayBucket.ofAllowance(line)
                BreakdownRow(
                    day = if (index == 0) weeklyWord else "", dayType = null, payCode = line.displayLabel,
                    nominal = coder.code(bucket, line), qty = trimQty(line.qty ?: 1.0), rate = line.rateAmount,
                    gross = line.lineAmount, currency = line.currency, bucket = bucket,
                )
            }

        private fun claimRows(timecard: PayrollTimecard, claimsWord: String) =
            timecard.claims.mapIndexed { index, claim ->
                BreakdownRow(
                    day = if (index == 0) claimsWord else "", dayType = null, payCode = claim.name,
                    nominal = claim.nominalCode?.trim().orEmpty(), qty = "1", rate = claim.amount,
                    gross = claim.amount, currency = claim.currency, bucket = PayBucket.Claim,
                    source = if (claim.cashExpenseBatchId != null) CASH else MANUAL,
                )
            }

        const val CASH = "CASH"
        const val MANUAL = "MANUAL"

        /** `8.0h` for the basic line — the web's `basic_hours.toFixed(1)`. */
        private fun hours(value: Double): String = "${(kotlin.math.round(value * TENTHS) / TENTHS)}h"

        /** `1.5h` / `30m` / `—` — the web's `fmtQtyHours`. */
        fun qtyHours(minutes: Int): String = when {
            minutes <= 0 -> "—"
            minutes >= MINUTES_PER_HOUR ->
                "${kotlin.math.round(minutes / MINUTES_PER_HOUR.toDouble() * TENTHS) / TENTHS}h"
            else -> "${minutes}m"
        }

        /** `10h 5m` / `8h` / `30m` / blank — the web's `fmtHM`. */
        fun hoursMinutes(minutes: Int): String {
            if (minutes <= 0) return ""
            val hours = minutes / MINUTES_PER_HOUR
            val rest = minutes % MINUTES_PER_HOUR
            return when {
                hours > 0 && rest > 0 -> "${hours}h ${rest}m"
                hours > 0 -> "${hours}h"
                else -> "${rest}m"
            }
        }

        private fun trimQty(value: Double): String {
            val rounded = kotlin.math.round(value * HUNDREDTHS) / HUNDREDTHS
            return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
        }

        private const val TENTHS = 10.0
        private const val HUNDREDTHS = 100.0
        private const val MINUTES_PER_HOUR = 60
    }
}

/** One payslip line: a pay code summed across the week. */
data class PayslipLine(
    val label: String,
    val bucket: PayBucket,
    val amount: Double,
    val minutes: Int,
    val currency: String? = null,
    val nominalCode: String? = null,
)

/** The payslip preview — earnings, non-taxable allowances and claims. */
data class PayslipPreview(
    val earnings: List<PayslipLine>,
    val allowances: List<PayslipLine>,
    val claims: List<PayslipLine>,
) {
    val grossPay: Double get() = earnings.sumOf { it.amount }
    val total: Double get() = (earnings + allowances + claims).sumOf { it.amount }

    companion object {
        /**
         * Aggregated per pay code the way the web's `payslipLines` memo does:
         * keyed by bucket, identifier and label, with hours summed alongside.
         */
        fun of(timecard: PayrollTimecard): PayslipPreview {
            val byKey = linkedMapOf<String, PayslipLine>()
            fun add(key: String, line: PayslipLine) {
                val current = byKey[key]
                byKey[key] = current?.copy(
                    amount = current.amount + line.amount,
                    minutes = current.minutes + line.minutes,
                    currency = line.currency ?: current.currency,
                ) ?: line
            }
            timecard.days.forEach { day ->
                day.rates.forEach { line ->
                    val bucket = PayBucket.of(line)
                    val minutes = if (bucket == PayBucket.Basic) {
                        kotlin.math.round(day.basicHours * MINUTES_PER_HOUR).toInt()
                    } else {
                        line.workDuration
                    }
                    add(
                        "$bucket::${line.identifier.orEmpty()}::${line.displayLabel}",
                        PayslipLine(line.displayLabel, bucket, line.rateAmount, minutes),
                    )
                }
                day.allowances.forEach { line ->
                    val bucket = PayBucket.ofAllowance(line)
                    add(
                        "$bucket::${line.identifier.orEmpty()}::${line.displayLabel}",
                        PayslipLine(line.displayLabel, bucket, line.rateAmount, 0),
                    )
                }
            }
            timecard.weeklyAllowances.forEach { line ->
                val bucket = PayBucket.ofAllowance(line)
                add(
                    "$bucket::${line.identifier.orEmpty()}::${line.displayLabel}",
                    PayslipLine(line.displayLabel, bucket, line.lineAmount, 0, currency = line.currency),
                )
            }
            timecard.claims.forEach { claim ->
                add(
                    "claim::${claim.id ?: claim.cashExpenseBatchId ?: claim.name}::${claim.name}",
                    PayslipLine(
                        claim.name, PayBucket.Claim, claim.amount, 0,
                        currency = claim.currency, nominalCode = claim.nominalCode?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
            val all = byKey.values.toList()
            return PayslipPreview(
                earnings = all.filter { it.bucket.isEarning },
                allowances = all.filter { it.bucket == PayBucket.Allowance || it.bucket == PayBucket.Rental },
                claims = all.filter { it.bucket == PayBucket.Claim },
            )
        }

        private const val MINUTES_PER_HOUR = 60
    }
}
