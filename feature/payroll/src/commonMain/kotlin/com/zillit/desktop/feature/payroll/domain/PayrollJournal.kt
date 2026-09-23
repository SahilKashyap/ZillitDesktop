package com.zillit.desktop.feature.payroll.domain

import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

/**
 * One saved journal line, as `/journal-ledger/fetch` returns it and as
 * `/journal-ledger` takes it.
 *
 * `debit` and `credit` are the journal's two sides: both keys always travel,
 * the side that does not apply is null. A pay break's null debit means
 * "resolve this from the timecard"; a payroll account's credit is typed by the
 * accountant. [trackingCodes] and [tags] are carried as the server sent them,
 * because this port does not edit them and must not drop them on a save.
 */
data class JournalLine(
    val id: String? = null,
    val src: String,
    val groupKey: String,
    val identifier: String = "",
    val label: String = "",
    val splitParentId: String? = null,
    val debit: Double? = null,
    val credit: Double? = null,
    val nominalCode: String = "",
    val trackingCodes: JsonElement? = null,
    val tags: JsonElement? = null,
    val taxType: String = "",
    val taxRate: Double? = null,
    val ledgerDescription: String = "",
    /** Epoch millis of UTC midnight, or null. */
    val effectiveDate: Long? = null,
) {
    val key: String get() = "$src::$groupKey"
}

/** The saved coding: per timecard, and the run's own payroll-account lines. */
data class JournalCoding(
    val byTimecard: Map<String, List<JournalLine>> = emptyMap(),
    val runLines: List<JournalLine> = emptyList(),
)

/** A save or a post — `POST /payroll/runs/journal-ledger`. */
data class JournalSubmission(
    val post: Boolean,
    val weekStarting: Long,
    /** The post's default date; per-line dates win. Null on a save. */
    val effectiveDate: Long?,
    val timecards: Map<String, List<JournalLine>>,
    val runLines: List<JournalLine>,
)

/** What a post answered: the journal's number, when the server gave one. */
data class JournalPosted(val journalDisplay: String?, val message: String?)

/** Which side of the journal a row sits on and how it groups — the web's categories. */
enum class JournalCategory {
    Basic, Overtime, Premium, Penalty, Turnaround, Allowance, Rental, Extra, Claim, Deduction, Other, Tax, Account;

    companion object {
        /** The web's `classifyPayBreak` (`PayrollRunModule.jsx` 803-816). */
        fun of(identifier: String, src: String, isRental: Boolean): JournalCategory =
            ofSource(src, isRental) ?: ofRate(identifier.lowercase())

        /** Everything that is not a daily rate is named by where it came from. */
        private fun ofSource(src: String, isRental: Boolean): JournalCategory? = when (src) {
            Journal.SRC_ACCOUNT -> Account
            Journal.SRC_TAX -> Tax
            "claim" -> Claim
            "deduction" -> Deduction
            "allowances", "weekly" -> if (isRental) Rental else Allowance
            "extras", "daily_extras" -> Extra
            else -> null
        }

        /** A daily rate is named by its identifier; whatever is left is overtime. */
        private fun ofRate(id: String): JournalCategory = when {
            "penalt" in id || "meal_delay" in id -> Penalty
            "premium" in id || "night" in id -> Premium
            "bta" in id || "turnaround" in id -> Turnaround
            "basic" in id -> Basic
            else -> Overtime
        }
    }
}

/**
 * One row of the Journal Ledger.
 *
 * Pay breaks debit the expense nominals and their amount is the week's sum of
 * that break — derived, never typed. Payroll-account rows credit the control
 * accounts; their code is fixed by Payroll Entry Setup and their amount is
 * typed. A saved tax line is carried as it was saved, so a save from here
 * cannot delete it.
 */
data class JournalRow(
    val id: String,
    val timecardId: String?,
    val companyId: String?,
    val src: String,
    val groupKey: String,
    val identifier: String,
    val label: String,
    val category: JournalCategory,
    val description: String,
    val descriptionOverride: String,
    val amount: Double?,
    val code: String,
    /** `YYYY-MM-DD`, or null. */
    val effectiveDate: String?,
    val taxType: String = "",
    val taxRate: Double? = null,
    val trackingCodes: JsonElement? = null,
    val tags: JsonElement? = null,
    /** Saved split children, carried unchanged. */
    val splits: List<JournalLine> = emptyList(),
) {
    val isCredit: Boolean get() = src == Journal.SRC_ACCOUNT
    val codeLocked: Boolean get() = isCredit
    val amountEditable: Boolean get() = isCredit

    /** A closed period is read-only: the web's `!lockedDate || !effDate || effDate > lockedDate`. */
    fun editable(lockedDate: String?): Boolean =
        lockedDate.isNullOrBlank() || effectiveDate.isNullOrBlank() || effectiveDate > lockedDate
}

/** What the accountant has typed over a row. Nulls leave the row's own value. */
data class JournalEdit(
    val nominalCode: String? = null,
    val effectiveDate: String? = null,
    val amount: Double? = null,
    val amountCleared: Boolean = false,
)

/** The journal's debit and credit totals. */
data class JournalBalance(val debit: Double, val credit: Double) {
    val variance: Double get() = PayrollTimecard.round2(debit - credit)
    val balanced: Boolean get() = abs(variance) <= TOLERANCE

    private companion object {
        const val TOLERANCE = 0.01
    }
}

/** A line that cannot post yet, and what it is missing. */
data class IncompleteLine(val description: String, val missingCode: Boolean, val missingDate: Boolean)

/** The journal's rules, in one place so the screen and the view model read the same ones. */
object Journal {
    const val SRC_ACCOUNT = "payroll_account"
    const val SRC_TAX = "tax"
    const val SRC_CATEGORY = "category"

    fun accountGroupKey(code: String): String = "acct:${code.trim().uppercase()}"

    /** Only locked and paid timecards post; the server moves the paid ones to posted. */
    fun isPostable(status: TimecardStatus): Boolean = status == TimecardStatus.Paid || status == TimecardStatus.Locked

    fun amountOf(row: JournalRow, edit: JournalEdit?): Double? = when {
        edit?.amountCleared == true -> null
        edit?.amount != null -> edit.amount
        else -> row.amount
    }

    fun codeOf(row: JournalRow, edit: JournalEdit?): String =
        if (row.codeLocked) row.code else edit?.nominalCode ?: row.code

    fun dateOf(row: JournalRow, edit: JournalEdit?): String? = edit?.effectiveDate ?: row.effectiveDate

    /** Sums the rows on screen, each on its own side; a null amount is nothing. */
    fun balance(rows: List<JournalRow>, edits: Map<String, JournalEdit>): JournalBalance {
        var debit = 0.0
        var credit = 0.0
        rows.forEach { row ->
            val value = amountOf(row, edits[row.id]) ?: 0.0
            if (row.isCredit) credit += value else debit += value
        }
        return JournalBalance(PayrollTimecard.round2(debit), PayrollTimecard.round2(credit))
    }

    /**
     * Lines in the post's scope missing a code or a date — the web's
     * `findIncompletePostLines`: run-level rows and the postable timecards' rows.
     */
    fun incomplete(
        rows: List<JournalRow>,
        edits: Map<String, JournalEdit>,
        postable: Set<String>,
    ): List<IncompleteLine> =
        rows.filter { it.timecardId == null || it.timecardId in postable }.mapNotNull { row ->
            val edit = edits[row.id]
            val missingCode = codeOf(row, edit).isBlank()
            val missingDate = dateOf(row, edit).isNullOrBlank()
            if (!missingCode && !missingDate) return@mapNotNull null
            IncompleteLine(row.description.ifBlank { row.label.ifBlank { row.code } }, missingCode, missingDate)
        }

    /**
     * The wire line for a row — the web's `buildJournalLedgerLines`: a pay
     * break sends no amount (the server resolves it from the timecard), an
     * account row its typed credit, a saved tax line its saved debit; the
     * code is the configured one on account rows; the date is epoch millis.
     */
    fun lineFor(row: JournalRow, edit: JournalEdit?): List<JournalLine> {
        val amount = amountOf(row, edit)
        val sided = when {
            row.isCredit -> null to amount
            row.src == SRC_TAX -> amount to null
            else -> null to null
        }
        val date = dateOf(row, edit)?.let(PayPeriod::parseIsoDate)
        val parent = JournalLine(
            id = row.id,
            src = row.src,
            groupKey = row.groupKey,
            identifier = row.identifier,
            label = row.label,
            debit = sided.first,
            credit = sided.second,
            nominalCode = codeOf(row, edit).trim(),
            trackingCodes = row.trackingCodes,
            tags = row.tags,
            taxType = row.taxType,
            taxRate = row.taxRate,
            ledgerDescription = row.descriptionOverride,
            effectiveDate = date,
        )
        val children = row.splits.map { child ->
            child.copy(
                src = row.src,
                groupKey = row.groupKey,
                identifier = row.identifier,
                label = row.label,
                splitParentId = row.id,
                nominalCode = if (row.codeLocked) row.code.trim() else child.nominalCode.trim(),
                effectiveDate = child.effectiveDate ?: date,
            )
        }
        return listOf(parent) + children
    }
}
