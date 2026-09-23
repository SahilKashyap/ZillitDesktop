package com.zillit.desktop.feature.payroll.domain

/**
 * Builds the Journal Ledger's rows from the week's timecards and the saved
 * coding — the web's `buildJournalLedgerRows` and `buildPayrollAccountRows`
 * (`PayrollRunModule.jsx` 1050-1378).
 *
 * ## What a row is
 *
 * One pay break per timecard: every rate, allowance and extra with the same
 * identifier and label is summed across the week into one debit line, while
 * weekly allowances, extras, claims and deductions each keep their own line.
 * With the production's "group by category" setting on, every category
 * collapses to one line per crew member. The saved coding is laid over by
 * `src::group_key`, so a code or a date set last week comes back.
 *
 * Posted timecards are left out: once the whole week is posted the working
 * ledger is empty.
 */
class JournalBuilder(
    private val metadata: PayrollMetadata,
    /** The crew member's name for the description. */
    private val nameOf: (String) -> String,
    /** The category names the grouped setting prints — Basic, Overtime… — resolved by the caller. */
    private val categoryLabel: (JournalCategory) -> String,
    /** The label a tax line carries. */
    private val taxLabel: String,
    /** "Week", in the viewer's language — the description's lead word. */
    private val weekWord: String,
) {

    fun rows(timecards: List<PayrollTimecard>, coding: JournalCoding, weekStarting: Long): List<JournalRow> {
        val perCompany = timecards.filterNot { it.status.isPosted }
            .flatMap { rowsFor(it, coding.byTimecard[it.id].orEmpty(), weekStarting) }
            .groupBy { it.companyId.orEmpty() }
            .flatMap { (_, rows) -> seatTaxRows(rows.sortedBy { CATEGORY_ORDER.indexOf(it.category) }) }
        return perCompany + accountRows(coding.runLines)
    }

    /** One timecard's lines, keyed and summed the web's way. */
    private fun rowsFor(timecard: PayrollTimecard, saved: List<JournalLine>, weekStarting: Long): List<JournalRow> {
        val breaks = Breaks().apply { collect(timecard) }.byKey
        val byKey = saved.groupBy { it.key }
        val week = weekLabel(timecard.weekStarting ?: weekStarting)
        val name = nameOf(timecard.userId)
        val rows = breaks.map { (key, part) -> breakRow(timecard, key, part, byKey[key].orEmpty(), "$week $name") }
        return rows + savedTaxRow(timecard, byKey["${Journal.SRC_TAX}::${Journal.SRC_TAX}"].orEmpty(), week, name)
    }

    /** One pay break's row, the saved coding laid over it. [lead] is the description's week and crew name. */
    private fun breakRow(
        timecard: PayrollTimecard,
        key: String,
        part: Break,
        lines: List<JournalLine>,
        lead: String,
    ): JournalRow {
        val parent = lines.firstOrNull { it.splitParentId == null } ?: lines.firstOrNull()
        return JournalRow(
            id = "${timecard.id}-$key",
            timecardId = timecard.id,
            companyId = timecard.companyId,
            src = part.src,
            groupKey = part.groupKey,
            identifier = part.identifier,
            label = part.name,
            category = part.category,
            description = parent?.ledgerDescription?.takeIf { it.isNotEmpty() }
                ?: cased("$weekWord $lead ${part.name}"),
            descriptionOverride = parent?.ledgerDescription.orEmpty(),
            amount = PayrollTimecard.round2(part.amount),
            code = parent?.nominalCode ?: part.code,
            effectiveDate = (parent?.effectiveDate ?: timecard.effectiveDate)?.let(PayPeriod::isoDate),
            taxType = parent?.taxType.orEmpty(),
            taxRate = parent?.taxRate,
            trackingCodes = parent?.trackingCodes,
            tags = parent?.tags,
            splits = lines.filter { it.splitParentId != null },
        )
    }

    /** A timecard's pay breaks, in the order the web meets them, keyed `src::group_key`. */
    private inner class Breaks {
        val byKey = linkedMapOf<String, Break>()

        fun collect(timecard: PayrollTimecard) {
            timecard.days.forEach { day ->
                day.rates.forEach { add(it.label ?: it.identifier ?: BASIC_NAME, it.rateAmount, it, "rates_ots") }
                day.allowances.forEach { add(it.label ?: it.identifier.orEmpty(), it.rateAmount, it, "allowances") }
                day.extras.forEach { add(it.label ?: it.identifier.orEmpty(), it.lineAmount, it, "daily_extras") }
            }
            timecard.weeklyAllowances.forEachIndexed { index, line ->
                add(line.label ?: line.identifier.orEmpty(), line.lineAmount, line, "weekly", index = index)
            }
            timecard.weeklyExtras.forEachIndexed { index, line ->
                add(line.label ?: line.identifier.orEmpty(), line.lineAmount, line, "extras", index = index)
            }
            timecard.claims.forEachIndexed { index, claim ->
                val line = PayLine(id = claim.id, nominalCode = claim.nominalCode)
                add(claim.name, claim.amount, line, "claim", claim.id.orEmpty(), index)
            }
            timecard.deductions.forEachIndexed { index, deduction ->
                val line = PayLine(id = deduction.id, nominalCode = deduction.nominalCode)
                add(deduction.label, deduction.amount, line, "deduction", deduction.id.orEmpty(), index)
            }
        }

        private fun add(
            name: String,
            amount: Double,
            line: PayLine,
            src: String,
            identifier: String? = line.identifier,
            index: Int = 0,
        ) {
            val label = name.trim()
            val id = identifier?.trim().orEmpty()
            if (label.isEmpty() && id.isEmpty()) return
            val category = JournalCategory.of(id, src, line.isRental)
            val merged = metadata.journalGroupByCategory && category != JournalCategory.Account
            val groupKey = when {
                merged -> "cat:${category.wire}"
                src in PER_CODE_SOURCES -> "$id|$label"
                line.id != null -> "id:${line.id}"
                else -> "idx:$index"
            }
            val effSrc = if (merged) Journal.SRC_CATEGORY else src
            val key = "$effSrc::$groupKey"
            val current = byKey[key] ?: Break(
                src = effSrc,
                groupKey = groupKey,
                identifier = if (merged) category.wire else id,
                name = if (merged) categoryLabel(category) else label.ifEmpty { id },
                category = category,
            )
            byKey[key] = current.copy(
                amount = current.amount + amount,
                code = current.code.ifEmpty { line.nominalCode.orEmpty() },
            )
        }
    }

    /**
     * A tax line the accountant saved. Adding one is not part of this port,
     * but a saved one is carried — so a save from here cannot delete it.
     */
    private fun savedTaxRow(
        timecard: PayrollTimecard,
        lines: List<JournalLine>,
        week: String,
        name: String,
    ): List<JournalRow> {
        val parent = lines.firstOrNull { it.splitParentId == null } ?: return emptyList()
        return listOf(
            JournalRow(
                id = "tax::${timecard.id}",
                timecardId = timecard.id,
                companyId = timecard.companyId,
                src = Journal.SRC_TAX,
                groupKey = Journal.SRC_TAX,
                identifier = Journal.SRC_TAX,
                label = taxLabel,
                category = JournalCategory.Tax,
                description = parent.ledgerDescription.ifEmpty { cased("$weekWord $week $name $taxLabel") },
                descriptionOverride = parent.ledgerDescription,
                amount = parent.debit,
                code = parent.nominalCode,
                effectiveDate = parent.effectiveDate?.let(PayPeriod::isoDate),
                taxType = parent.taxType,
                taxRate = parent.taxRate,
                trackingCodes = parent.trackingCodes,
                tags = parent.tags,
            ),
        )
    }

    /**
     * The payroll accounts — the journal's credit side, one set per run. The
     * code comes from Payroll Entry Setup and cannot be changed here; the
     * amount is typed and never defaults to zero.
     */
    private fun accountRows(runLines: List<JournalLine>): List<JournalRow> {
        val byKey = runLines.groupBy { it.groupKey }
        return metadata.payrollAccounts.mapNotNull { account ->
            val code = account.code.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val groupKey = Journal.accountGroupKey(code)
            val lines = byKey[groupKey].orEmpty()
            val parent = lines.firstOrNull { it.splitParentId == null } ?: lines.firstOrNull()
            JournalRow(
                id = "acct::$groupKey",
                timecardId = null,
                companyId = null,
                src = Journal.SRC_ACCOUNT,
                groupKey = groupKey,
                identifier = code,
                label = account.name,
                category = JournalCategory.Account,
                description = parent?.ledgerDescription?.takeIf { it.isNotEmpty() } ?: cased(account.name),
                descriptionOverride = parent?.ledgerDescription.orEmpty(),
                amount = parent?.credit,
                code = code,
                effectiveDate = parent?.effectiveDate?.let(PayPeriod::isoDate),
                taxType = parent?.taxType.orEmpty(),
                taxRate = parent?.taxRate,
                trackingCodes = parent?.trackingCodes,
                tags = parent?.tags,
                splits = lines.filter { it.splitParentId != null },
            )
        }
    }

    /** UPPERCASE by default, Title Case when the production asks for it. */
    private fun cased(text: String): String = if (metadata.journalTitleCase) {
        text.split(' ').joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercaseChar() } }
    } else {
        text.uppercase()
    }

    /** Each tax line straight after the last line of its own timecard. */
    private fun seatTaxRows(rows: List<JournalRow>): List<JournalRow> {
        val tax = rows.filter { it.category == JournalCategory.Tax }
        if (tax.isEmpty()) return rows
        val rest = rows.filterNot { it.category == JournalCategory.Tax }
        val out = mutableListOf<JournalRow>()
        rest.forEachIndexed { index, row ->
            out += row
            if (row.timecardId != rest.getOrNull(index + 1)?.timecardId) {
                tax.firstOrNull { it.timecardId == row.timecardId }?.let { out += it }
            }
        }
        return out + tax.filterNot { it in out }
    }

    private data class Break(
        val src: String,
        val groupKey: String,
        val identifier: String,
        val name: String,
        val category: JournalCategory,
        val amount: Double = 0.0,
        val code: String = "",
    )

    companion object {
        private const val BASIC_NAME = "Basic"
        private val PER_CODE_SOURCES = setOf("rates_ots", "allowances", "daily_extras")
        private val CATEGORY_ORDER = listOf(
            JournalCategory.Basic, JournalCategory.Overtime, JournalCategory.Premium, JournalCategory.Penalty,
            JournalCategory.Turnaround, JournalCategory.Allowance, JournalCategory.Rental, JournalCategory.Extra,
            JournalCategory.Claim, JournalCategory.Deduction, JournalCategory.Other, JournalCategory.Tax,
            JournalCategory.Account,
        )

        /**
         * `4-10 May 2026`, or `28 Apr-4 May 2026` across a month — the web's
         * `fmtWeekRangeLabel`, day numbers unpadded.
         */
        fun weekLabel(weekStarting: Long): String {
            if (weekStarting <= 0) return ""
            val end = weekStarting + (DAYS - 1) * PayPeriod.DAY_MILLIS
            val (startDay, startMonth) = PayPeriod.dayMonth(weekStarting).split(' ')
            val (endDay, endMonth) = PayPeriod.dayMonth(end).split(' ')
            val year = PayPeriod.dayMonthYear(end).substringAfterLast(' ')
            val from = startDay.trimStart('0')
            val to = endDay.trimStart('0')
            return if (startMonth == endMonth) "$from-$to $endMonth $year" else "$from $startMonth-$to $endMonth $year"
        }

        private const val DAYS = 7

        /** The server-facing name of each category, as the grouped `group_key` spells it. */
        private val JournalCategory.wire: String get() = name.lowercase()
    }
}

