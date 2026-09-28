package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalBuilder
import com.zillit.desktop.feature.payroll.domain.JournalCategory
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalRow
import com.zillit.desktop.feature.payroll.domain.JournalSplit
import com.zillit.desktop.feature.payroll.domain.JournalSplits
import com.zillit.desktop.feature.payroll.domain.JournalSubmission
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.RunSelection
import com.zillit.desktop.feature.payroll.domain.TimecardStatus

/**
 * The Journal Ledger's behaviour — the web's journal portal inside the Run.
 *
 * ## Save codes the week; post sends it to the ledger
 *
 * One endpoint does both. A save upserts the coding; a post upserts it and
 * snapshots the postable timecards — paid and locked — into a journal, the
 * server moving the paid ones to posted. Before a post the week has to be
 * ready: every timecard with lines locked or paid (the lock gate), and every
 * line in scope carrying a nominal code and an effective date.
 */
internal class JournalActions(private val vm: PayrollViewModel, private val run: RunActions) {

    private val journal: JournalState get() = vm.ui.run.journal

    init {
        // After the lock gate's "…& Post" confirm lands, the post is tried again.
        run.afterConfirm = { openPost() }
    }

    /** Numbers the allocations this session makes; the server names the saved ones. */
    private var splitSequence = 0

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: JournalEvent) {
        when (event) {
            is JournalEvent.Code -> editRow(event.rowId) { row, edit ->
                if (row.codeLocked) edit else edit.copy(nominalCode = event.code)
            }
            is JournalEvent.Date -> editRow(event.rowId) { _, edit -> edit.copy(effectiveDate = clamp(event.date)) }
            is JournalEvent.Credit -> editRow(event.rowId) { row, edit -> credit(row, edit, event.amount) }
            is JournalEvent.Describe -> editRow(event.rowId) { _, edit -> edit.copy(description = event.description) }
            is JournalEvent.HeaderDate -> headerDate(event.date)
            is JournalEvent.AddTax -> addTax(event.timecardId)
            is JournalEvent.RemoveTax -> removeTax(event.timecardId)
            is JournalEvent.TaxType -> editRow(event.rowId) { _, edit -> tax(edit, type = event.type) }
            is JournalEvent.TaxRate -> editRow(event.rowId) { _, edit -> tax(edit, rate = event.rate) }
            is JournalEvent.Layers -> editRow(event.rowId) { _, edit -> edit.copy(trackingCodes = event.codes) }
            is JournalEvent.Tags -> editRow(event.rowId) { _, edit -> edit.copy(tags = event.tags) }
            is JournalEvent.SplitLayers ->
                editSplit(event.rowId, event.childId) { it.copy(trackingCodes = event.codes) }
            is JournalEvent.SplitTags -> editSplit(event.rowId, event.childId) { it.copy(tags = event.tags) }
            is JournalEvent.Split -> editRow(event.rowId) { row, edit ->
                if (row.splittable) edit.copy(splits = JournalSplits.split(row, edit, ::newSplitId)) else edit
            }
            is JournalEvent.SplitAmount -> editRow(event.rowId) { row, edit ->
                val amount = event.amount.trim().toDoubleOrNull() ?: return@editRow edit
                edit.copy(splits = JournalSplits.editSplitAmount(row, edit, event.childId, amount))
            }
            is JournalEvent.SplitCode -> editSplit(event.rowId, event.childId) { it.copy(nominalCode = event.code) }
            is JournalEvent.SplitDescribe ->
                editSplit(event.rowId, event.childId) { it.copy(description = event.description) }
            is JournalEvent.SplitDate ->
                editSplit(event.rowId, event.childId) { it.copy(effectiveDate = clamp(event.date)) }
            is JournalEvent.RemoveSplit -> editRow(event.rowId) { row, edit ->
                edit.copy(splits = JournalSplits.removeSplit(row, edit, event.childId))
            }
            JournalEvent.Save -> save()
            JournalEvent.Post -> openPost()
            is JournalEvent.PostDate -> editJournal {
                copy(post = post?.copy(effectiveDate = clamp(event.date), error = null))
            }
            JournalEvent.ConfirmPost -> confirmPost()
            JournalEvent.DismissPost -> editJournal { copy(post = null) }
            JournalEvent.DismissAlert -> editJournal { copy(alert = null) }
            JournalEvent.FixAndPost -> journal.alert?.fix?.let { fix ->
                editJournal { copy(alert = null) }
                run.present(fix)
            }
        }
    }

    /** A typed credit, on an account row only; a cleared field is no amount rather than zero. */
    private fun credit(row: JournalRow, edit: JournalEdit, typed: String): JournalEdit {
        if (!row.amountEditable) return edit
        val text = typed.trim()
        return edit.copy(amount = text.toDoubleOrNull(), amountCleared = text.isEmpty())
    }

    /**
     * Adds the timecard's tax line, and forgets any removal of it — the two
     * sets are opposites, so a line added back is not also a line removed.
     */
    private fun addTax(timecardId: String) {
        if (!vm.ui.viewer.seesAccountantViews || timecardId.isBlank()) return
        editJournal { copy(taxAdded = taxAdded + timecardId, taxRemoved = taxRemoved - timecardId) }
    }

    /**
     * Removes it, and drops what was typed on it: a line that comes back after
     * a refetch starts clean rather than inheriting a rate just deleted.
     */
    private fun removeTax(timecardId: String) {
        if (!vm.ui.viewer.seesAccountantViews || timecardId.isBlank()) return
        editJournal {
            copy(
                taxAdded = taxAdded - timecardId,
                taxRemoved = taxRemoved + timecardId,
                edits = edits - "${Journal.SRC_TAX}::$timecardId",
            )
        }
    }

    /**
     * The picked tax, and the money it derives. Choosing a type or a rate
     * drops any typed figure, because the figure now follows the rate again —
     * leaving it would show 20% against an amount that is not 20% of the base.
     */
    private fun tax(edit: JournalEdit, type: String? = null, rate: String? = null): JournalEdit = edit.copy(
        taxType = type ?: edit.taxType,
        taxRate = rate?.trim()?.toDoubleOrNull()?.coerceIn(0.0, TAX_RATE_MAX) ?: edit.taxRate.takeIf { rate == null },
        amount = null,
        amountCleared = false,
    )

    /** One allocation's field, leaving its siblings' amounts alone. */
    private fun editSplit(rowId: String, childId: String, change: (JournalSplit) -> JournalSplit) {
        editRow(rowId) { row, edit ->
            val splits = JournalSplits.splitsOf(row, edit)
            edit.copy(splits = splits.map { if (it.id == childId) change(it) else it })
        }
    }

    /** Unique within the session; the server assigns the saved line's own id. */
    private fun newSplitId(): String = "split-${vm.ui.now}-${splitSequence++}"

    /** A closed period is read-only; everything else takes the edit. */
    private fun editRow(rowId: String, change: (JournalRow, JournalEdit) -> JournalEdit) {
        val ui = vm.ui
        if (!ui.viewer.seesAccountantViews) return
        val row = ui.journalRows().firstOrNull { it.id == rowId } ?: return
        if (!row.editable(ui.lockedDate)) return
        editJournal {
            copy(
                edits = edits + (rowId to change(row, edits[rowId] ?: JournalEdit())),
                flagged = flagged - rowId,
            )
        }
    }

    /**
     * The header's date fills every editable row — the web's handler writes it
     * into each row's edit buffer. A date on or before the lock snaps to the
     * first open day, as the web's input does.
     */
    private fun headerDate(date: String) {
        val ui = vm.ui
        if (!ui.viewer.seesAccountantViews) return
        val value = clamp(date)
        val rows = ui.journalRows().filter { it.editable(ui.lockedDate) }
        editJournal {
            copy(
                headerDate = value,
                edits = edits + rows.associate { row ->
                    row.id to (edits[row.id] ?: JournalEdit()).copy(effectiveDate = value)
                },
            )
        }
    }

    private fun clamp(date: String): String {
        val earliest = vm.ui.earliestEffectiveDate ?: return date
        return if (PayPeriod.parseIsoDate(date) != null && date < earliest) earliest else date
    }

    /** Saves the coding for every row on screen, and the run's account lines. */
    private fun save() {
        val ui = vm.ui
        val week = ui.run.weekStarting ?: return
        if (!ui.viewer.seesAccountantViews || journal.saving || journal.edits.isEmpty()) return
        val rows = ui.journalRows()
        val submission = submission(rows, post = false, week = week, effectiveDate = null)
        if (submission.timecards.isEmpty() && submission.runLines.isEmpty()) return
        editJournal { copy(saving = true) }
        vm.launchWork {
            when (val result = vm.repository.journal.submit(submission)) {
                is ZillitResult.Success -> {
                    val sent = rows.map { it.id }.toSet()
                    editJournal { copy(saving = false, edits = edits.filterKeys { it !in sent }) }
                    vm.notify(result.data.message?.localisedMessage() ?: JOURNAL_SAVED_KEY.localisedMessage())
                    run.reload(silent = true)
                }
                is ZillitResult.Failure -> {
                    editJournal { copy(saving = false) }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    /**
     * The web's `openPostModal`, in its order: the lock gate, then "nothing to
     * post", then the missing fields, and only then the dialog.
     */
    private fun openPost() {
        val ui = vm.ui
        if (!ui.viewer.seesAccountantViews) return
        val rows = ui.journalRows()
        val withRows = rows.mapNotNull { it.timecardId }.toSet()
        val cards = ui.run.timecards.filter { it.id in withRows }
        val blockers = cards.filter { !it.status.isPosted && !Journal.isPostable(it.status) }
        if (blockers.isNotEmpty()) {
            editJournal { copy(alert = lockGateAlert(ui, blockers.map { it.id to it.status })) }
            return
        }
        val postable = cards.filter { Journal.isPostable(it.status) }.map { it.id }.toSet()
        if (postable.isEmpty()) {
            editJournal {
                copy(alert = JournalAlert(
                    str(S.desktop_payroll_nothing_to_post),
                    str(S.desktop_payroll_nothing_to_post_message),
                ))
            }
            return
        }
        val incomplete = Journal.incomplete(rows, journal.edits, postable)
        if (incomplete.isNotEmpty()) {
            val flagged = rows.filter { row ->
                (row.timecardId == null || row.timecardId in postable) &&
                    (
                        Journal.codeOf(row, journal.edits[row.id]).isBlank() ||
                            Journal.dateOf(row, journal.edits[row.id]).isNullOrBlank()
                    )
            }.map { it.id }.toSet()
            editJournal {
                copy(
                    alert = JournalAlert(str(S.desktop_payroll_cannot_post_yet), describe(incomplete)),
                    flagged = flagged,
                )
            }
            return
        }
        val inScope = rows.filter { it.timecardId in postable }
        val earliest = ui.earliestEffectiveDate
        editJournal {
            copy(
                post = JournalPostDialog(
                    timecardIds = postable.toList(),
                    lineCount = inScope.size,
                    gross = ui.run.timecards.filter { it.id in postable }.sumOf { it.gross },
                    currency = ui.run.timecards.firstOrNull { it.id in postable }?.currency,
                    effectiveDate = if (earliest != null && ui.todayIso < earliest) earliest else ui.todayIso,
                ),
            )
        }
    }

    /**
     * Posts the postable timecards' lines and every account line. Each line's
     * own date wins; the dialog's is the default for lines without one.
     */
    private fun confirmPost() {
        val ui = vm.ui
        val dialog = journal.post ?: return
        val week = ui.run.weekStarting ?: return
        if (dialog.saving || !ui.viewer.seesAccountantViews) return
        val date = openDate(dialog.effectiveDate)
        if (date == null) {
            val refusal = ui.earliestEffectiveDate?.let { str(S.desktop_payroll_date_in_locked_period, it) }
                ?: str(S.desktop_payroll_choose_effective_date)
            editJournal { copy(post = dialog.copy(error = refusal)) }
            return
        }
        val postable = dialog.timecardIds.toSet()
        val rows = ui.journalRows().filter { it.timecardId == null || it.timecardId in postable }
        val submission = submission(rows, post = true, week = week, effectiveDate = date)
        editJournal { copy(post = dialog.copy(saving = true, error = null)) }
        vm.launchWork {
            when (val result = vm.repository.journal.submit(submission)) {
                is ZillitResult.Success -> {
                    editJournal { copy(post = null, edits = emptyMap(), headerDate = "") }
                    vm.notify(result.data.describe())
                    run.reload(silent = true)
                }
                is ZillitResult.Failure -> editJournal {
                    copy(post = post?.copy(saving = false, error = result.error.localised()))
                }
            }
        }
    }

    /** The date as epoch millis, when it parses and falls after the cost-report lock. */
    private fun openDate(text: String): Long? {
        val earliest = vm.ui.earliestEffectiveDate
        return PayPeriod.parseIsoDate(text)?.takeIf { earliest == null || text >= earliest }
    }

    /** "Posted as PR-0007", or the server's own words. */
    private fun com.zillit.desktop.feature.payroll.domain.JournalPosted.describe(): String =
        journalDisplay?.let { str(S.desktop_payroll_posted_as, it) }
            ?: message?.localisedMessage()
            ?: TIMECARDS_POSTED_KEY.localisedMessage()

    private fun submission(rows: List<JournalRow>, post: Boolean, week: Long, effectiveDate: Long?): JournalSubmission {
        val edits = journal.edits
        return JournalSubmission(
            post = post,
            weekStarting = week,
            effectiveDate = effectiveDate,
            timecards = rows.filter { it.timecardId != null }
                .groupBy { it.timecardId.orEmpty() }
                .mapValues { (_, list) -> list.flatMap { Journal.lineFor(it, edits[it.id]) } },
            runLines = rows.filter { it.timecardId == null }.flatMap { Journal.lineFor(it, edits[it.id]) },
        )
    }

    /**
     * Timecards with lines that are not locked or paid yet. Where this viewer
     * can move the fixable ones — approved and ACCT Approved — the alert offers
     * to, and posts once they have moved (the web's "…& Post").
     */
    private fun lockGateAlert(ui: PayrollUiState, blockers: List<Pair<String, TimecardStatus>>): JournalAlert {
        val approved = blockers.filter { it.second == TimecardStatus.Approved }.map { it.first }
        val finalApproved = blockers.filter { it.second == TimecardStatus.FinalApproved }.map { it.first }
        val pending = blockers.size - approved.size - finalApproved.size
        val selection = RunSelection(blockers.size, approved, finalApproved, emptyList(), emptyList())
        val mode = selection.approvalFor(ui.viewer)?.first
        val names = blockers.take(MAX_LISTED).joinToString("\n") { (id, status) ->
            val userId = ui.run.timecards.firstOrNull { it.id == id }?.userId.orEmpty()
            "• ${ui.nameOf(userId)} (${status.label})"
        } + if (blockers.size > MAX_LISTED) "\n" + str(S.desktop_payroll_and_more, blockers.size - MAX_LISTED) else ""
        val message = buildString {
            append(str(S.desktop_payroll_not_locked_yet, blockers.size))
            append("\n\n").append(names)
            if (pending > 0) append("\n\n").append(str(S.desktop_payroll_pending_cannot_lock, pending))
        }
        return JournalAlert(
            title = str(S.desktop_payroll_cannot_post_yet),
            message = message,
            fix = mode
                ?.let { run.confirmFor(it, selection, runScope = true, thenPost = true) },
        )
    }

    private fun describe(incomplete: List<com.zillit.desktop.feature.payroll.domain.IncompleteLine>): String {
        val lines = incomplete.take(MAX_LISTED).joinToString("\n") { line ->
            val missing = listOfNotNull(
                str(S.desktop_payroll_nominal_code_lower).takeIf { line.missingCode },
                str(S.desktop_payroll_effective_date_lower).takeIf { line.missingDate },
            ).joinToString(", ")
            "• ${line.description} ($missing)"
        }
        val more = if (incomplete.size > MAX_LISTED) "\n" + str(
            S.desktop_payroll_and_more,
            incomplete.size - MAX_LISTED,
        ) else ""
        return str(S.desktop_payroll_lines_cannot_post, incomplete.size) + "\n" + lines + more
    }

    private fun editJournal(reducer: JournalState.() -> JournalState) =
        vm.update { copy(run = run.copy(journal = run.journal.reducer())) }

    private companion object {
        const val MAX_LISTED = 5

        /** The server message keys the web falls back to (`showApiSuccess(res, t, key)`). */
        const val JOURNAL_SAVED_KEY = "journal_saved"
        const val TIMECARDS_POSTED_KEY = "timecards_posted"

        /** The highest rate the percentage field will hold — the web's `TAX_RATE_MAX`. */
        const val TAX_RATE_MAX = 100.0
    }
}

/** The ledger's rows for the open week — derived, never stored, so they always match the grid. */
internal fun PayrollUiState.journalRows(): List<JournalRow> {
    val week = run.weekStarting ?: return emptyList()
    return JournalBuilder(
        metadata = metadata,
        nameOf = ::nameOf,
        categoryLabel = { it.label() },
        taxLabel = str(S.ah_lbl_vat),
        weekWord = str(S.week_label),
    ).rows(run.timecards, run.journal.coding, week).filter { showsTaxLine(it) }
}

/**
 * Whether a tax line is on screen. The builder makes one for every timecard
 * because only it knows the base; a line the server does not hold is shown
 * once the accountant adds it, and a removal hides it until the save settles.
 */
private fun PayrollUiState.showsTaxLine(row: JournalRow): Boolean {
    if (!row.isTax) return true
    val timecardId = row.timecardId ?: return false
    if (timecardId in run.journal.taxRemoved) return false
    return row.taxSaved || timecardId in run.journal.taxAdded
}

/** The timecards on screen that have no tax line yet — each is offered one. */
internal fun PayrollUiState.timecardsWithoutTax(rows: List<JournalRow>): Set<String> {
    val withTax = rows.filter { it.isTax }.mapNotNull { it.timecardId }.toSet()
    return rows.mapNotNull { it.timecardId }.toSet() - withTax
}

/** The grouped setting's line names — the web's `JOURNAL_CATEGORY_LABELS`. */
internal fun JournalCategory.label(): String = str(
    when (this) {
        JournalCategory.Basic -> S.desktop_payroll_basic
        JournalCategory.Overtime -> S.overtime
        JournalCategory.Premium -> S.dm_rates_premiums
        JournalCategory.Penalty -> S.dm_rates_penalties
        JournalCategory.Turnaround -> S.dm_rates_turnarounds
        JournalCategory.Allowance -> S.allowances_label
        JournalCategory.Rental -> S.dm_allow_card_rentals
        JournalCategory.Extra -> S.desktop_payroll_upgrades_extras
        JournalCategory.Claim -> S.desktop_ce_claims
        JournalCategory.Deduction -> S.desktop_deductions
        JournalCategory.Other -> S.other
        JournalCategory.Tax -> S.ah_lbl_vat
        JournalCategory.Account -> S.desktop_payroll_accounts
    },
)
