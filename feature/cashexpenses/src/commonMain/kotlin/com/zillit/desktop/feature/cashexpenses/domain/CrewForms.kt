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
 * The request cap, applied — the web's `useRequestCapGuard` over `requestCap.js`.
 *
 * Never blocks on uncertainty: no cap, a currency with no exchange rate on
 * either side, or a request raised on someone else's behalf all resolve to "not
 * enforceable" and let the request through; the server owns those cases, and a
 * wrong block stops a legitimate float. The flat `max_amount` is defined as the
 * project default currency, so only a weekly-sourced cap carries a currency of
 * its own; the request may be in any project currency and is converted too.
 */
object CrewRequestCap {

    /** Where a resolved cap came from (`resolveRequestCap`'s `source`). */
    enum class Source { Max, Weekly, Fallback }

    /**
     * The cap for one requester and one request currency.
     *
     * [amount] is in the cap's own [currency] (null currency = the default);
     * null means no cap. [unrated] names the currency whose missing rate makes
     * the cap unenforceable, if that is why it is.
     */
    data class Verdict(
        val amount: Double?,
        val currency: String?,
        val source: Source,
        val capInDefault: Double?,
        val enforceable: Boolean,
        val unrated: String?,
    )

    /**
     * THE RULE (`resolveRequestCap`): a weekly basis multiplies the requester's
     * weekly rate; no usable rate falls back to the flat ceiling. A rate of 0
     * must not become a 0 cap, which would block every request.
     */
    private fun resolve(cap: RequestCap, deal: RequesterDeal?): Verdict {
        val rate = deal?.weeklyRate ?: 0.0
        if (cap.isWeekly && rate > 0 && cap.salaryMultiplier > 0) {
            return Verdict(
                amount = rate * cap.salaryMultiplier,
                currency = deal?.currency?.takeIf { it.isNotBlank() },
                source = Source.Weekly,
                capInDefault = null,
                enforceable = false,
                unrated = null,
            )
        }
        return Verdict(
            amount = cap.maxAmount.takeIf { it > 0 },
            currency = null,
            source = if (cap.isWeekly) Source.Fallback else Source.Max,
            capInDefault = null,
            enforceable = false,
            unrated = null,
        )
    }

    /** The cap for one requester raising [requestCurrency]; [applies] is false on someone else's behalf. */
    fun verdict(
        cap: RequestCap?,
        deal: RequesterDeal?,
        requestCurrency: String,
        currencies: CashCurrencies,
        applies: Boolean = true,
    ): Verdict {
        if (cap == null || !cap.enabled) return NO_CAP
        val resolved = resolve(cap, deal)
        val amount = resolved.amount ?: return resolved
        val capUnrated = resolved.currency?.takeIf { rateMissing(it, currencies) }
        val requestUnrated = requestCurrency.takeIf { rateMissing(it, currencies) }
        return resolved.copy(
            capInDefault = resolved.currency?.let { currencies.toDefault(amount, it) } ?: amount,
            enforceable = applies && capUnrated == null && requestUnrated == null,
            unrated = capUnrated ?: requestUnrated,
        )
    }

    /** Whether [amount], raised in [requestCurrency], is over an enforceable cap (`check`). */
    fun exceeds(verdict: Verdict, amount: Double, requestCurrency: String, currencies: CashCurrencies): Boolean {
        val limit = verdict.capInDefault ?: return false
        if (!verdict.enforceable) return false
        return (currencies.toDefault(amount, requestCurrency) ?: amount) > limit
    }

    private val NO_CAP = Verdict(null, null, Source.Max, null, enforceable = false, unrated = null)

    /** A code other than the default with no usable rate (`rateMissing`). */
    private fun rateMissing(code: String, currencies: CashCurrencies): Boolean {
        val upper = code.trim().uppercase()
        return upper.isNotEmpty() && upper != currencies.default.uppercase() && currencies.rates[upper] == null
    }
}
