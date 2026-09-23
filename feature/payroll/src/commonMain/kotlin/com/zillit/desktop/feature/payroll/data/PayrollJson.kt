package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.currencyCode
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.AuditEvent
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.DaySummary
import com.zillit.desktop.feature.payroll.domain.DealCoding
import com.zillit.desktop.feature.payroll.domain.DeductionLine
import com.zillit.desktop.feature.payroll.domain.FringeLine
import com.zillit.desktop.feature.payroll.domain.OutstandingFigures
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One timecard, whichever projection it came in.
 *
 * Returns null for a row with no id or no crew member, which the web renders as
 * a blank row nobody can act on.
 */
internal fun JsonObject.toTimecard(): PayrollTimecard? {
    val id = identifier() ?: return null
    val userId = text("user_id", "crew_id") ?: return null
    return PayrollTimecard(
        id = id,
        userId = userId,
        status = TimecardStatus.from(text("status")),
        weekStarting = millis("week_starting"),
        companyId = text("company_id"),
        // A code or the whole currency object, on either key — the web's timecardDocCurrency.
        currency = this["pay_currency"].currencyCode() ?: this["currency"].currencyCode(),
        effectiveDate = millis("effective_date"),
        nominalCode = text("nominal_code"),
        dealType = text("deal_type"),
        employmentStatus = obj("crew_details")?.text("emp_status"),
        attachment = text("attachment"),
        basicPay = amount("basic_pay"),
        overtimePay = amount("overtime_pay"),
        totalAllowances = amount("total_allowances"),
        totalDays = number("total_days")?.toInt() ?: 0,
        fringesTotal = amount("fringes_total"),
        claimsTotalScalar = number("attached_claims_total"),
        deductionsTotalScalar = number("deductions_total"),
        weeklyAllowancesRentalsTotal = number("weekly_allowances_rentals_total"),
        // The outstanding projection is told apart by a numeric `ots_total` and no day summary.
        outstanding = number("ots_total")?.takeIf { this["day_summary"] == null }?.let { ots ->
            OutstandingFigures(
                ots = ots,
                dailyAllowances = amount("daily_allowances_total"),
                dailyRentals = amount("daily_rentals_total"),
                weeklyAllowances = amount("weekly_allowances_total"),
                weeklyRentals = amount("weekly_rentals_total"),
            )
        },
        daySummary = objects("day_summary").map { it.toDaySummary() },
        days = objects("days").map { it.toDay() },
        weeklyAllowances = objects("weekly_allowances").map { it.toPayLine() },
        weeklyExtras = objects("weekly_additional_fees").ifEmpty { objects("additional_fees") }
            .map { it.toPayLine() },
        claims = objects("attached_claims").map { it.toClaim() },
        deductions = objects("deductions").map { it.toDeduction() },
        fringes = objects("fringes").mapNotNull { it.toFringe() },
        history = objects("history").map { it.toAuditEvent() },
    )
}

private fun JsonObject.toDaySummary() = DaySummary(
    date = millis("date"),
    dayType = text("day_type"),
    basic = amount("basic"),
    ots = amount("ots"),
    allowancesRentals = amount("allowances_rentals"),
    minutesWorked = number("minutes_worked")?.toInt() ?: 0,
)

private fun JsonObject.toDay() = TimecardDay(
    date = millis("date"),
    dayNumber = number("day_number")?.toInt(),
    dayType = text("day_type"),
    basicHours = amount("basic_hours"),
    source = obj("login_details")?.text("source"),
    callTime = millis("call_time"),
    wrapTime = millis("wrap_time"),
    loginTime = obj("login_details")?.millis("time"),
    logoutTime = obj("logout_details")?.millis("time"),
    minutesWorked = number("minutes_worked")?.toInt() ?: 0,
    rates = objects("rates_ots").map { it.toPayLine() },
    allowances = objects("allowances").map { it.toPayLine() },
    extras = objects("additional_fees").map { it.toPayLine() },
    status = text("status"),
)

internal fun JsonObject.toPayLine() = PayLine(
    id = identifier(),
    identifier = text("identifier"),
    label = text("label"),
    rawLabel = text("raw_label"),
    rateType = text("rate_type"),
    rateAmount = amount("rate_amount"),
    // A missing quantity is one; a genuine zero stays zero — the web's roundQty.
    qty = number("qty"),
    workDuration = number("work_duration")?.toInt() ?: 0,
    currency = this["currency"].currencyCode(),
    nominalCode = text("nominal_code"),
    isRental = flag("is_rental"),
)

private fun JsonObject.toClaim() = ClaimLine(
    id = identifier(),
    name = text("claim_name", "label", "description") ?: str(S.desktop_payroll_claim),
    amount = amount("claim_amount"),
    currency = this["claim_currency"].currencyCode(),
    nominalCode = text("nominal_code"),
    cashExpenseBatchId = text("cash_expense_batch_id"),
)

private fun JsonObject.toDeduction() = DeductionLine(
    id = identifier(),
    label = text("label") ?: str(S.desktop_payroll_deduction),
    rateType = text("rate_type") ?: "flat",
    rateAmount = amount("rate_amount"),
    // `actual_amount` is the server's figure; rows captured on dev also carry a bare `amount`.
    amount = number("actual_amount", "amount") ?: amount("rate_amount"),
    nominalCode = text("nominal_code"),
    isCustom = (this["is_custom"] as? JsonPrimitive)?.booleanOrNull != false,
)

private fun JsonObject.toFringe(): FringeLine? {
    val amount = amount("actual_amount").takeIf { it > 0 } ?: return null
    val source = obj("source")
    return FringeLine(label = source?.text("label", "raw_label") ?: str(S.desktop_fringe), amount = amount)
}

private fun JsonObject.toAuditEvent() = AuditEvent(
    at = millis("action_at"),
    byUserId = text("action_by"),
    action = text("action"),
    from = text("from"),
    to = text("to"),
    note = text("note"),
    detail = text("detail"),
)

/**
 * The deal memo's nominal codes (`GET /deal-memo/deals/active/{userId}`).
 *
 * Row arrays are keyed by `row_id` or `source.id`, entitlement arrays by `id`
 * — the two shapes the web's `findRowCode` and `findEntitlementCode` read.
 */
internal fun JsonObject.toDealCoding(): DealCoding {
    fun rows(key: String): Map<String, String> = objects(key).mapNotNull { row ->
        val code = row.text("nominal_code") ?: return@mapNotNull null
        val id = row.text("row_id") ?: row.obj("source")?.text("id") ?: return@mapNotNull null
        id to code
    }.toMap()
    fun entitlements(key: String): Map<String, String> = objects(key).mapNotNull { row ->
        val code = row.text("nominal_code") ?: return@mapNotNull null
        (row.text("id") ?: return@mapNotNull null) to code
    }.toMap()
    return DealCoding(
        basic = obj("rates")?.text("nominal_code"),
        holidayPay = obj("holiday_pay")?.text("nominal_code"),
        holidayPayTreatment = obj("holiday_pay")?.text("treatment") ?: obj("rates")?.text("hp_treatment"),
        overtimes = rows("overtimes"),
        premiums = rows("premiums"),
        turnarounds = rows("turnarounds"),
        penalties = rows("penalties"),
        extraFees = rows("extra_fees"),
        allowances = entitlements("allowances"),
        rentals = entitlements("rentals"),
    )
}
