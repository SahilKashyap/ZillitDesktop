package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.currencyCode
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.BankAccount
import com.zillit.desktop.feature.payroll.domain.BatchOutcome
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollAccount
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `/payroll/metadata` merged with the hub's `/payroll-settings` — the web's
 * `fetchMetadata`, where the settings document wins for the two journal
 * settings and is the only source of the payroll accounts.
 *
 * The flag is read in both spellings: dev answers `is_final_approver` where
 * the web documents `isFinalApprover`, and a flag that silently stays false
 * takes the approver's buttons away with nothing to explain it.
 */
internal fun parseMetadata(meta: JsonObject?, settings: JsonObject?, chart: List<JsonObject>): PayrollMetadata {
    val setting = settings?.let { it.obj("value") ?: it }
    val period = meta?.obj("pay_period") ?: meta?.obj("payPeriod") ?: setting?.obj("pay_period")
    val names = chart.mapNotNull { row ->
        val code = row.text("code") ?: return@mapNotNull null
        code.trim().uppercase() to (row.text("name") ?: code)
    }.toMap()
    val format = setting?.text("journal_description_format") ?: meta?.text("journal_description_format")
    val grouped = setting?.boolean("journal_group_by_category") ?: meta?.boolean("journal_group_by_category")
    val startDay = period?.number("start_day_of_week")?.toInt()
    return PayrollMetadata(
        isFinalApprover = meta?.boolean("is_final_approver") ?: meta?.boolean("isFinalApprover") ?: false,
        payPeriodStartDay = startDay?.takeIf { it in PayPeriod.MONDAY..PayPeriod.SUNDAY_ISO } ?: PayPeriod.MONDAY,
        payrollAccounts = setting?.array("payroll_accounts").orEmpty().mapNotNull { it.toPayrollAccount(names) },
        journalGroupByCategory = grouped == true,
        journalTitleCase = format.equals("title", ignoreCase = true),
    )
}

/** One `payroll_accounts` entry — a bare code or `{code}` — named from the chart of accounts. */
private fun JsonElement.toPayrollAccount(names: Map<String, String>): PayrollAccount? {
    val code = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> text("code")
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return PayrollAccount(code = code, name = names[code.uppercase()] ?: code)
}

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
    it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull()
}

/**
 * One settling account. The currency is a code or the whole currency object —
 * decoding it as a string failed the entire list once.
 */
internal fun JsonObject.toBankAccount(): BankAccount? {
    val id = identifier() ?: return null
    return BankAccount(
        id = id,
        name = text("name", "account_name") ?: str(S.desktop_unnamed_bank),
        accountNumber = text("account_number"),
        currency = this["currency"].currencyCode(),
        holderName = text("account_holder_name"),
    )
}

/** `GET /lock-period` — `lockedDate` on the read route, `last_cr_locked_date` where the write route spells it. */
internal fun JsonElement?.routeLockDate(): String? {
    val body = obj()?.let { it.obj("value") ?: it } ?: return null
    return normaliseLockDate(body["lockedDate"]) ?: normaliseLockDate(body["last_cr_locked_date"])
}

/** The combined project-settings document's `settings.last_cr_locked_date`. */
internal fun JsonElement?.settingsLockDate(): String? {
    val body = obj()?.let { it.obj("value") ?: it } ?: return null
    val settings = body.obj("settings") ?: body
    return normaliseLockDate(settings["last_cr_locked_date"])
}

/**
 * `YYYY-MM-DD` from whichever shape the boundary arrives in — the web's
 * `normalizeLockedYmd`: a date string, an ISO datetime (its date part), or
 * epoch milliseconds as a number or a numeric string, sliced in UTC.
 */
internal fun normaliseLockDate(raw: JsonElement?): String? {
    val text = (raw as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    ISO_DATE_PREFIX.find(text)?.let { return it.groupValues[1] }
    val millis = text.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return null
    return PayPeriod.isoDate(millis)
}

private val ISO_DATE_PREFIX = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:$|T)")

/**
 * A stated `status: 0` is a refusal. The envelope client passes it through as
 * a success, and a transition that "succeeded" having done nothing is the one
 * failure a payroll screen must never hide.
 */
internal fun ZillitResult<ApiEnvelope>.refusedOnStatusZero(): ZillitResult<ApiEnvelope> = when (this) {
    is ZillitResult.Success -> if (data.status == 0) {
        ZillitResult.Failure(
            ZillitError.Http(
                status = REFUSED,
                serverMessage = data.message,
                messageElements = data.messageElements.orEmpty(),
            ),
        )
    } else {
        this
    }
    is ZillitResult.Failure -> this
}

private const val REFUSED = 200

/** `{ marked: [ids], skipped: [{id, status, reason}] }` — either side may be a count instead. */
internal fun ApiEnvelope.toBatchOutcome(): BatchOutcome {
    val body = data.obj()
    val marked = body?.array("marked").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val skipped = body?.number("skipped")?.toInt() ?: body?.array("skipped")?.size ?: 0
    return BatchOutcome(marked = marked, skipped = skipped, message = message)
}
