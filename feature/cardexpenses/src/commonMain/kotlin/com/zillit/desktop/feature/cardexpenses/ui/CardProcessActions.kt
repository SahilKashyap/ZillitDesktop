package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptAssignment
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod

/**
 * The accountant's process editor — the web's `ProcessReceiptModal`.
 *
 * Replaces the split dialog, which posted through `/post` with an empty body:
 * no lines, no ledger date, no top-up decision. Every commit here goes through
 * `save-process` with the whole editor, told apart by status, and every one is
 * gated here as well as on the button: who may open the row (a senior, or the
 * accountant it is assigned to), who may post (not a non-senior on a flagged
 * receipt, not above a posting limit), and who hands a receipt up (a
 * non-senior).
 */
internal class CardProcessActions(
    private val vm: CardExpensesViewModel,
    private val today: () -> Long,
) {

    /** Routes this collaborator's events; false for any it does not own. */
    @Suppress("CyclomaticComplexMethod") // A dispatch table.
    fun handle(event: CardEvent): Boolean {
        when (event) {
            is CardEvent.OpenProcess -> open(event.receiptId, event.mode)
            CardEvent.CloseProcess -> vm.update { copy(process = null) }
            is CardEvent.EditProcess -> edit { if (it.receipt.id == event.draft.receipt.id) event.draft else it }
            is CardEvent.EditProcessLine -> edit { draft ->
                draft.copy(lines = draft.lines.mapIndexed { at, line -> if (at == event.index) event.line else line })
            }

            CardEvent.AddProcessLine -> edit { draft ->
                // Starts at what is left, which is what someone adding a line
                // almost always means.
                val left = (draft.receipt.amount - draft.figures.gross).coerceAtLeast(0.0)
                draft.copy(lines = draft.lines + ProcessLine(net = left))
            }

            is CardEvent.RemoveProcessLine -> edit { draft ->
                val kept = draft.lines.filterIndexed { index, _ -> index != event.index }
                draft.copy(lines = kept.ifEmpty { draft.lines })
            }

            CardEvent.SaveProcess -> commit(Commit.Save)
            CardEvent.PostProcess -> commit(Commit.Post)
            CardEvent.SubmitProcessForReview -> commit(Commit.Review)
            CardEvent.ConfirmEscalation -> commit(Commit.Escalate)
            CardEvent.ConfirmAssign -> assign()
            is CardEvent.ShowProcessTab -> showTab(event.tab)
            else -> return false
        }
        return true
    }

    private fun edit(change: (ProcessDraft) -> ProcessDraft) = vm.update {
        copy(process = process?.let(change))
    }

    /** The Posting Review queue is a senior's; anyone else stays on Processing. */
    private fun showTab(tab: ProcessTab) {
        val viewer = vm.current.viewer
        if (tab == ProcessTab.Review && !(viewer.isAccountant && viewer.isSenior)) return refuse()
        vm.update { copy(processTab = tab) }
    }

    /**
     * Opens the editor at once with the row's own figures, then fills it from
     * the detail read.
     *
     * A failed read closes it again rather than leaving it open on the row:
     * the row carries none of the server-owned lines, and a save from it would
     * send a line list without them — which deletes them.
     */
    private fun open(receiptId: String, mode: ProcessMode) {
        val state = vm.current
        val receipt = state.receipts.firstOrNull { it.id == receiptId } ?: return
        val allowed = when (mode) {
            ProcessMode.Process -> ProcessRules.canOpen(state.viewer, receipt)
            ProcessMode.History -> state.viewer.isAccountant
        }
        if (!allowed) return refuse()
        vm.update { copy(process = ProcessDraft.of(receipt, mode, loading = true, today = today())) }
        vm.run {
            val detail = vm.repo.receiptDetail(receiptId)
            if (vm.current.process?.receipt?.id != receiptId) return@run
            when (detail) {
                is ZillitResult.Success ->
                    vm.update { copy(process = ProcessDraft.of(detail.data, mode, loading = false, today = today())) }

                is ZillitResult.Failure -> {
                    vm.update { copy(process = null) }
                    vm.fail(detail.error.localised())
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // The web's post guard, one refusal per line.
    private fun commit(kind: Commit) {
        val state = vm.current
        val draft = state.process ?: return
        if (draft.loading) return
        val viewer = state.viewer
        val receipt = draft.receipt
        val figures = draft.figures
        val permitted = when {
            !viewer.isAccountant -> false
            draft.mode == ProcessMode.History -> kind == Commit.Save
            !ProcessRules.canOpen(viewer, receipt) -> false
            kind == Commit.Post -> ProcessRules.canPost(viewer, receipt.processing, figures.effectiveAmount)
            kind == Commit.Review || kind == Commit.Escalate -> ProcessRules.canHandUp(viewer)
            else -> true
        }
        if (!permitted) return refuse()

        if ((kind == Commit.Save || kind == Commit.Post) && figures.mismatch) {
            val message = when (kind) {
                Commit.Post -> S.desktop_card_lines_must_match_post
                else -> S.desktop_card_lines_must_match_save
            }
            return vm.fail(str(message))
        }
        var topUp: TopUpMethod? = null
        if (kind == Commit.Post) {
            val missing = figures.linesMissingNominal
            if (missing.isNotEmpty()) return vm.fail(str(S.desktop_po_lines_need_nominal, missing.joinToString(", ")))
            if (draft.effectiveDateMillis == null) return vm.fail(str(S.desktop_card_effective_date_required))
            topUp = if (receipt.processing.requestTopUp) draft.topUp else TopUpMethod.None
            if (figures.topUpOverfills(topUp)) {
                return vm.fail(
                    str(S.desktop_card_topup_exceeds_limit, Money.format(figures.topUpHeadroom, receipt.currency)),
                )
            }
        }
        val reason = draft.escalation?.trim().orEmpty()
        if (kind == Commit.Escalate && reason.isEmpty()) return vm.fail(str(S.desktop_a_reason_is_required))

        val submission = ProcessSubmission(
            lines = draft.lines,
            fixedLines = receipt.processing.fixedLines,
            net = figures.net,
            tax = figures.tax,
            gross = figures.gross,
            description = draft.description,
            nominalCode = draft.nominalCode,
            effectiveDate = draft.effectiveDateMillis,
            userId = viewer.userId,
            status = kind.status,
            topUpMethod = topUp,
            topUpAmount = topUp?.let(figures::topUpAmount),
            escalationReason = reason.takeIf { kind == Commit.Escalate },
        )
        vm.act(kind.notice()) { vm.repo.saveProcessReceipt(receipt.id, submission) }
        vm.update { copy(process = null) }
    }

    /**
     * Assigning or reassigning, from the editor's own dialog.
     *
     * An accountant's action on the web (`ProcessReceiptModal.jsx:508`); the
     * list offers the accounts team, never the current assignee.
     */
    private fun assign() {
        val state = vm.current
        val draft = state.process ?: return
        val choice = draft.assign ?: return
        val refusal = when {
            !state.viewer.isAccountant || draft.mode != ProcessMode.Process -> str(S.desktop_po_no_rights_on_project)
            !choice.complete -> str(S.desktop_card_assign_needs_person_and_reason)
            choice.userId == draft.receipt.assignedTo -> str(S.desktop_card_already_assigned_to_them)
            else -> null
        }
        if (refusal != null) return vm.fail(refusal)
        val reassign = !draft.receipt.assignedTo.isNullOrBlank()
        val assignment = ReceiptAssignment(choice.userId, state.viewer.userId, choice.finalReason)
        vm.act(if (reassign) str(S.desktop_card_receipt_reassigned) else str(S.desktop_card_receipt_assigned)) {
            vm.repo.assignReceipt(draft.receipt.id, assignment, reassign)
        }
        vm.update { copy(process = null) }
    }

    private fun refuse() = vm.fail(str(S.desktop_po_no_rights_on_project))

    private enum class Commit(val status: String?) {
        Save(null),
        Post(ProcessSubmission.POSTED),
        Review(ProcessSubmission.UNDER_REVIEW),
        Escalate(ProcessSubmission.ESCALATED),
        ;

        fun notice(): String = when (this) {
            Save -> str(S.desktop_card_receipt_saved)
            Post -> str(S.desktop_card_receipt_posted)
            Review -> str(S.desktop_card_sent_for_review)
            Escalate -> str(S.desktop_card_escalated_to_senior)
        }
    }
}
