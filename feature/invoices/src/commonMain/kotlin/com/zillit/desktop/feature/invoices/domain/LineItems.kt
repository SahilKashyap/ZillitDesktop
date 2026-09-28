package com.zillit.desktop.feature.invoices.domain

/**
 * Line items being written on a credit note or a sales invoice — the web's
 * `LineItemsEditor` state: the lines, the one picked for a split, and the
 * lines the last save flagged.
 *
 * The line model and its edits are Invoice Entry's ([EntryCoding]): one net
 * amount per line, splits that must add back up to their parent, and a tax
 * rate a child inherits.
 */
data class LineDraft(
    val lines: List<CodedLine> = emptyList(),
    val selectedId: String? = null,
    /** Line ids the last save refused; each clears as it is edited. */
    val flagged: Set<String> = emptySet(),
) {
    /**
     * Any picked line can be split — a picked child splits its parent again
     * (`LineItemsEditor.jsx:255-260`, disabled only with nothing picked).
     */
    val canSplit: Boolean get() = lines.any { it.id == selectedId }

    /** Net, tax and gross of the parents; children are a breakdown of them. */
    val totals: EntryTotals get() = EntryCoding.totals(lines)
}

/** One edit to a [LineDraft]. */
sealed interface LineEdit {
    data class Select(val id: String) : LineEdit
    data class Change(val line: CodedLine) : LineEdit

    /** A split child's own amount; its siblings share what is left. */
    data class SplitAmount(val id: String, val amount: Double) : LineEdit
    data object Add : LineEdit
    data object Split : LineEdit
    data class Remove(val id: String) : LineEdit
}

/** A described line the save refused, and why — the web's `Line N: account, amount > 0`. */
data class LineProblem(val position: Int, val lineId: String, val needsAccount: Boolean, val needsAmount: Boolean)

/** What a save found wrong with the lines; [ok] when nothing. */
data class LineCheck(val noDescription: Boolean = false, val problems: List<LineProblem> = emptyList()) {
    val ok: Boolean get() = !noDescription && problems.isEmpty()
}

object LineItems {

    fun apply(draft: LineDraft, edit: LineEdit, newId: () -> String): LineDraft = when (edit) {
        // A second click on the picked line lets it go, as the web's row toggle does.
        is LineEdit.Select -> draft.copy(selectedId = edit.id.takeUnless { it == draft.selectedId })
        is LineEdit.Change -> draft.copy(
            // Nominal, expenditure type, Layers, tags and tax reach the children (`:160-199`).
            lines = EntryCoding.update(draft.lines, edit.line.id, cascadeCoding = true) { edit.line },
            flagged = draft.flagged - edit.line.id,
        )
        is LineEdit.SplitAmount -> draft.copy(lines = EntryCoding.redistribute(draft.lines, edit.id, edit.amount))
        LineEdit.Add -> EntryCoding.add(draft.lines, newId).let { (lines, id) ->
            draft.copy(lines = lines, selectedId = id)
        }
        LineEdit.Split -> EntryCoding.split(draft.lines, draft.selectedId, newId).let { (lines, id) ->
            draft.copy(lines = lines, selectedId = id)
        }
        is LineEdit.Remove -> draft.copy(
            lines = EntryCoding.remove(draft.lines, edit.id),
            selectedId = draft.selectedId.takeUnless { it == edit.id },
        )
    }

    /** A line's tax as the web stores it on these records: its amount at its rate. */
    fun taxOf(line: CodedLine): Double = line.amount * (line.taxRate ?: 0.0) / PERCENT

    /**
     * The web's PO-style line check (`handleCreate`): blank placeholder lines
     * are skipped, at least one parent line must be described, and every
     * described parent line needs an account and an amount above zero.
     * Positions count parent lines only, from 1.
     */
    fun check(lines: List<CodedLine>): LineCheck {
        val parents = lines.filter { !it.isSplit }
        if (parents.all { it.description.isBlank() }) return LineCheck(noDescription = true)
        val problems = parents.mapIndexedNotNull { index, line ->
            if (line.description.isBlank()) return@mapIndexedNotNull null
            val account = line.account.isBlank()
            val amount = line.amount <= 0.0
            if (account || amount) LineProblem(index + 1, line.id, account, amount) else null
        }
        return LineCheck(problems = problems)
    }

    private const val PERCENT = 100.0
}
