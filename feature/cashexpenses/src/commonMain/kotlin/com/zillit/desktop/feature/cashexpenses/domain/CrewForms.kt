package com.zillit.desktop.feature.cashexpenses.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// The crew side's pure rules — Submit Receipts' settlement choices and bank
// details, and the float request's field values — kept out of the pages so
// the payload and the screen read one answer.

/** How a reimbursement is paid — `settlement_details.payment_method`. */
enum class ReimbursementMethod(val wire: String) {
    Bacs("BACS"),
    Payroll("PAYROLL"),
}

/** One extra bank detail row — `{label, value, field_type}` (`BankAdditionalDetailsEditor`). */
data class ExtraBankField(
    val label: String = "",
    val value: String = "",
    val fieldType: String = FIELD_TYPE_TEXT,
) {
    companion object {
        const val FIELD_TYPE_TEXT = "text"

        /** `TypedValueInput.FIELD_TYPES`, in the web's order. */
        val FIELD_TYPES = listOf(FIELD_TYPE_TEXT, "number", "phone", "email", "url")
    }
}

/** The BACS details a reimbursement carries — `settlement_details.bank_details`. */
data class BankDetailsDraft(
    val accountName: String = "",
    /** Digits only; the dashes are display. */
    val sortCode: String = "",
    /** Digits only. */
    val accountNumber: String = "",
    val extras: List<ExtraBankField> = emptyList(),
)

/**
 * `settlement_details` as the web's submit builds it
 * (`PCSubmitClaimPage.jsx:622-640`, `OOPSubmitPage.jsx:66-73`).
 *
 * Petty cash always sends `follow_up`, null when no option was picked; out of
 * pocket never does — [includeFollowUp].
 */
data class SettlementDetails(
    val includeFollowUp: Boolean = true,
    /** `top_up`, `close`, or null. */
    val followUp: String? = null,
    /** Only with a `top_up` follow-up: the batch total, to 2dp. */
    val topUpAmount: Double? = null,
    /** Only on a reimbursement. */
    val paymentMethod: ReimbursementMethod? = null,
    /** Only for BACS. */
    val bankDetails: BankDetailsDraft? = null,
)

/** The two optional follow-ups a petty-cash batch can ask for. */
object FollowUp {
    const val TOP_UP = "top_up"
    const val CLOSE = "close"
}

/** Input shaping the web applies as the person types. */
object CrewInput {

    /** `sanitizeAmountInput`: digits and one point, two decimals at most. */
    fun amount(text: String): String {
        val kept = StringBuilder()
        var point = false
        var decimals = 0
        text.forEach { char ->
            when {
                char.isDigit() && (!point || decimals < 2) -> {
                    kept.append(char)
                    if (point) decimals++
                }
                char == '.' && !point -> {
                    point = true
                    kept.append(char)
                }
            }
        }
        return kept.toString()
    }

    /** Digits only, as `stripSortCode` / `stripAccountNumber` keep them. */
    fun digits(text: String, max: Int): String = text.filter(Char::isDigit).take(max)

    /** `formatSortCode`: `204891` → `20-48-91`, partial input grouped as far as it goes. */
    fun sortCode(digits: String): String = digits.chunked(2).joinToString("-")

    const val SORT_CODE_DIGITS = 6
    const val ACCOUNT_NUMBER_DIGITS = 8
}

/** Calendar dates as the cash service stores them: UTC midnight epoch millis. */
object CrewDates {

    /** `new Date("YYYY-MM-DD").getTime()` — UTC midnight — or null for anything else. */
    fun utcMillis(iso: String): Long? {
        val parts = iso.trim().split('-')
        if (parts.size != ISO_PARTS) return null
        val (year, month, day) = parts.map { it.toIntOrNull() ?: return null }
        return runCatching { LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }
            .getOrNull()
    }

    /** The UTC calendar date of [millis] as `YYYY-MM-DD`, the inverse of [utcMillis]. */
    fun utcIso(millis: Long?): String =
        millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString() }.orEmpty()

    /** Whether [iso] is after [todayIso] — both `YYYY-MM-DD`, which sort as text. */
    fun isAfter(iso: String, todayIso: String): Boolean = iso.isNotBlank() && iso > todayIso

    private const val ISO_PARTS = 3
}

/**
 * The first reason a request for [amount] breaks the project cap, or null —
 * the web's `useRequestCapGuard.check`, for the cases this client can judge.
 *
 * Only the flat `max_amount` basis, and only in the project default currency:
 * the weekly-salary basis needs the requester's active deal, and another
 * currency needs an exchange rate, and the web itself stands down (lets the
 * server decide) when it cannot convert.
 */
object CrewRequestCap {

    fun enforceable(cap: RequestCap?, requestCurrency: String, defaultCurrency: String): Boolean =
        cap != null && cap.enabled && !cap.isWeekly && cap.maxAmount > 0 &&
            requestCurrency.equals(defaultCurrency, ignoreCase = true)

    fun exceeds(cap: RequestCap?, amount: Double, requestCurrency: String, defaultCurrency: String): Boolean =
        enforceable(cap, requestCurrency, defaultCurrency) && amount > (cap?.maxAmount ?: 0.0)
}
