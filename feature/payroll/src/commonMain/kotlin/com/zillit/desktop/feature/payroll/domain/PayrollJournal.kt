package com.zillit.desktop.feature.payroll.domain

import kotlin.math.abs

/**
 * One saved journal line, as `/journal-ledger/fetch` returns it and as
 * `/journal-ledger` takes it.
 *
 * `debit` and `credit` are the journal's two sides: both keys always travel,
 * the side that does not apply is null. A pay break's null debit means
 * "resolve this from the timecard"; a payroll account's credit is typed by the
 * accountant. [trackingCodes] is one code per tracking set, keyed by set id,
 * and [tags] the production's account tags — both editable, and both written
 * back whole, so a line saved elsewhere keeps what it had.
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
    val trackingCodes: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
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
    val trackingCodes: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    /** Saved split children, carried unchanged. */
    val splits: List<JournalLine> = emptyList(),
    /**
     * A tax line's base — the sum of its timecard's other lines. The line's
     * money follows `rate × base` until someone types over it.
     */
    val taxBase: Double? = null,
    /**
     * A tax line the server already holds coding for.
     *
     * The builder constructs every timecard's tax row whether or not one
     * exists, because only it knows the week, the name and the base. A row
     * with no saved coding is shown once the accountant adds one — nothing on
     * the wire says "this timecard has no tax", so without the distinction a
     * deleted line would come back on the next read.
     */
    val taxSaved: Boolean = false,
) {
    val isTax: Boolean get() = src == Journal.SRC_TAX

    val isCredit: Boolean get() = src == Journal.SRC_ACCOUNT
    val codeLocked: Boolean get() = isCredit
    /**
     * Only two kinds of row carry a typed figure: a payroll account, which has
     * no timecard to sum, and a tax line, whose derived figure stays
     * overridable. Every other row's money is the timecard's.
     */
    val amountEditable: Boolean get() = isCredit || isTax

    /** A tax line is one line by definition, so splitting it is not offered. */
    val splittable: Boolean get() = !isTax

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
    /** The line's own wording, where the accountant has replaced the derived one. */
    val description: String? = null,
    val taxType: String? = null,
    val taxRate: Double? = null,
    /** Null leaves the row's saved allocations; an empty list un-splits it. */
    val splits: List<JournalSplit>? = null,
    /** Null leaves the row's own; an empty map clears every layer. */
    val trackingCodes: Map<String, String>? = null,
    val tags: List<String>? = null,
)

/**
 * One allocation of a split line.
 *
 * The parent stays derived and read-only; its children carry fixed amounts
 * that must sum to it, because the server does not rebalance them.
 */
data class JournalSplit(
    val id: String,
    val amount: Double,
    val nominalCode: String = "",
    val description: String = "",
    val effectiveDate: String? = null,
    /** Each allocation carries its own layers and tags, cloned from the parent. */
    val trackingCodes: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
)

/**
 * A Layers set and its codes — the production's tracking dimensions, as
 * Production Setup defines them.
 *
 * Declared here rather than shared, which is how the other finance modules
 * carry it too: each states the shape its own screens read, and the parse at
 * the edge is what keeps them agreeing with the service.
 */
data class TrackingSet(val id: String, val name: String, val nodes: List<TrackingNode>)

data class TrackingNode(val code: String, val label: String)

/** What the journal's Layers and Tags cells are drawn against. */
data class JournalReference(
    val trackingSets: List<TrackingSet> = emptyList(),
    /** Production Setup → Account Tags. */
    val assetTags: List<String> = emptyList(),
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
    private const val PERCENT = 100.0

    const val SRC_ACCOUNT = "payroll_account"
    const val SRC_TAX = "tax"
    const val SRC_CATEGORY = "category"

    fun accountGroupKey(code: String): String = "acct:${code.trim().uppercase()}"

    /** Only locked and paid timecards post; the server moves the paid ones to posted. */
    fun isPostable(status: TimecardStatus): Boolean = status == TimecardStatus.Paid || status == TimecardStatus.Locked

    /**
     * The row's money. A tax line with nothing typed follows the picked rate,
     * so clearing the field falls back to the derived figure rather than
     * sticking at whatever was last typed.
     */
    fun amountOf(row: JournalRow, edit: JournalEdit?): Double? = when {
        edit?.amountCleared == true -> derivedTax(row, edit)
        edit?.amount != null -> edit.amount
        row.isTax && row.amount == null -> derivedTax(row, edit)
        else -> row.amount
    }

    private fun derivedTax(row: JournalRow, edit: JournalEdit?): Double? =
        if (row.isTax) taxAmount(row.taxBase, rateOf(row, edit)) else null

    /**
     * `base × rate ÷ 100`, or nothing when either is missing — the web's
     * `taxLineAmount`. A zero rate is no tax rather than a zero line.
     */
    fun taxAmount(base: Double?, rate: Double?): Double? {
        if (base == null || rate == null || rate == 0.0) return null
        return PayrollTimecard.round2(base * rate / PERCENT)
    }

    fun descriptionOf(row: JournalRow, edit: JournalEdit?): String = edit?.description ?: row.description

    fun rateOf(row: JournalRow, edit: JournalEdit?): Double? = edit?.taxRate ?: row.taxRate

    fun layersOf(row: JournalRow, edit: JournalEdit?): Map<String, String> = edit?.trackingCodes ?: row.trackingCodes

    fun tagsOf(row: JournalRow, edit: JournalEdit?): List<String> = edit?.tags ?: row.tags

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
            row.isTax -> amount to null
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
            trackingCodes = layersOf(row, edit),
            tags = tagsOf(row, edit),
            taxType = edit?.taxType ?: row.taxType,
            taxRate = rateOf(row, edit),
            ledgerDescription = edit?.description ?: row.descriptionOverride,
            effectiveDate = date,
        )
        val children = JournalSplits.splitsOf(row, edit).map { child ->
            JournalLine(
                id = child.id,
                src = row.src,
                groupKey = row.groupKey,
                identifier = row.identifier,
                label = row.label,
                splitParentId = row.id,
                // An allocation sits on the same side as its parent.
                debit = child.amount.takeUnless { row.isCredit },
                credit = child.amount.takeIf { row.isCredit },
                // A locked code is configuration: children clone it and cannot
                // be repointed, or Split would be a way around the lock.
                nominalCode = if (row.codeLocked) row.code.trim() else child.nominalCode.trim(),
                trackingCodes = child.trackingCodes,
                tags = child.tags,
                ledgerDescription = child.description,
                effectiveDate = child.effectiveDate?.let(PayPeriod::parseIsoDate) ?: date,
            )
        }
        return listOf(parent) + children
    }
}

/**
 * Splitting a journal line into ledger allocations.
 *
 * The parent's figure stays derived — it is the timecard's — and its children
 * carry fixed amounts that must sum to it, because the server does not
 * rebalance them. Every rule here exists to keep that sum exact: an even cut,
 * the remainder on the last allocation, and a collapse back to whole when only
 * one survivor is left.
 */
object JournalSplits {

    /** The row's allocations: the accountant's, where they have split it, else the saved ones. */
    fun splitsOf(row: JournalRow, edit: JournalEdit?): List<JournalSplit> =
        edit?.splits ?: row.splits.filter { it.splitParentId != null }.map { it.toSplit() }

    /**
     * Splits a row, or adds another allocation to one already split — the
     * web's `splitJournalRow`. The first split cuts the total in two; each one
     * after adds a sibling and evens them all out, the last taking the
     * rounding so they always sum to the parent exactly.
     *
     * A new allocation clones the parent's coding, because that is what the
     * accountant is starting from.
     */
    fun split(row: JournalRow, edit: JournalEdit?, newId: () -> String): List<JournalSplit> {
        val current = splitsOf(row, edit)
        val count = if (current.isEmpty()) TWO else current.size + 1
        val amounts = spread(Journal.amountOf(row, edit) ?: 0.0, count)
        return List(count) { index ->
            current.getOrNull(index)?.copy(amount = amounts[index])
                ?: JournalSplit(
                    id = newId(),
                    amount = amounts[index],
                    nominalCode = Journal.codeOf(row, edit),
                    description = Journal.descriptionOf(row, edit),
                    effectiveDate = Journal.dateOf(row, edit),
                    trackingCodes = Journal.layersOf(row, edit),
                    tags = Journal.tagsOf(row, edit),
                )
        }
    }

    /**
     * One allocation's amount, its siblings re-spread so they still sum to the
     * parent — the remainder falling on the last of them, as the web's
     * `editSplitAmount` does.
     */
    fun editSplitAmount(row: JournalRow, edit: JournalEdit?, childId: String, amount: Double): List<JournalSplit> {
        val total = Journal.amountOf(row, edit) ?: 0.0
        val current = splitsOf(row, edit)
        val siblings = current.filterNot { it.id == childId }
        if (siblings.isEmpty()) return current.map { it.copy(amount = amount) }
        val amounts = spread(PayrollTimecard.round2(total - amount), siblings.size)
        var index = 0
        return current.map { child ->
            if (child.id == childId) child.copy(amount = amount) else child.copy(amount = amounts[index++])
        }
    }

    /**
     * Removes an allocation. One survivor is not a split, so the row collapses
     * back to whole rather than leaving a single child carrying the total.
     */
    fun removeSplit(row: JournalRow, edit: JournalEdit?, childId: String): List<JournalSplit> {
        val remaining = splitsOf(row, edit).filterNot { it.id == childId }
        if (remaining.size <= 1) return emptyList()
        val amounts = spread(Journal.amountOf(row, edit) ?: 0.0, remaining.size)
        return remaining.mapIndexed { index, child -> child.copy(amount = amounts[index]) }
    }

    /** A saved allocation, as the screen edits it. */
    private fun JournalLine.toSplit() = JournalSplit(
        id = id.orEmpty(),
        amount = debit ?: credit ?: 0.0,
        nominalCode = nominalCode,
        description = ledgerDescription,
        effectiveDate = effectiveDate?.let(PayPeriod::isoDate),
        trackingCodes = trackingCodes,
        tags = tags,
    )

    /** [total] across [count] shares, the last carrying the rounding. */
    private fun spread(total: Double, count: Int): List<Double> {
        if (count <= 0) return emptyList()
        val per = PayrollTimecard.round2(total / count)
        return List(count) { index ->
            if (index == count - 1) PayrollTimecard.round2(total - per * (count - 1)) else per
        }
    }

    private const val TWO = 2
}
