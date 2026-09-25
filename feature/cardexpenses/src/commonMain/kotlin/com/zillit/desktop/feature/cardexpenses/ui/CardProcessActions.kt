package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLines
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRefs
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptAssignment
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptScope
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineWire
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod

/**
 * The accountant's process pages and the process editor — the web's
 * `ApprovalQueuePage`, `ProcessPage`, `BulkProcessPage`, `HistoryPage` and
 * `ProcessReceiptModal`.
 *
 * Every commit is gated here as well as on its button: who may open a row (a
 * senior, or the accountant it is assigned to), who may post (not a non-senior
 * on a flagged receipt, not above a posting limit), who hands a receipt up (a
 * non-senior), who signs an approval step (the person the next tier names),
 * and nothing at all on a receipt dated in the closed period.
 */
@Suppress("TooManyFunctions") // One handler per user action.
internal class CardProcessActions(
    private val vm: CardExpensesViewModel,
    private val today: () -> Long,
) {

    /** Routes this collaborator's events; false for any it does not own. */
    @Suppress("CyclomaticComplexMethod") // A dispatch table.
    fun handle(event: CardEvent): Boolean {
        when (event) {
            is ProcessPageEvent -> page(event)
            is CardEvent.OpenProcess -> open(event.receiptId, event.mode)
            CardEvent.CloseProcess -> vm.update { copy(process = null) }
            is CardEvent.EditProcess -> edit { if (it.receipt.id == event.draft.receipt.id) event.draft else it }
            is CardEvent.EditProcessLine -> edit { draft ->
                val id = draft.lines.getOrNull(event.index)?.id ?: return@edit draft
                draft.copy(lines = ProcessLines.update(draft.lines, id) { event.line.copy(id = id) })
            }

            CardEvent.AddProcessLine -> addLine()
            is CardEvent.RemoveProcessLine -> edit { draft ->
                val id = draft.lines.getOrNull(event.index)?.id ?: return@edit draft
                draft.copy(lines = ProcessLines.remove(draft.lines, id))
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

    @Suppress("CyclomaticComplexMethod") // A dispatch table.
    private fun page(event: ProcessPageEvent) {
        when (event) {
            is ProcessPageEvent.ApproveRow -> approve(event.receiptId)
            is ProcessPageEvent.OverrideRow -> override(event.receiptId)
            is ProcessPageEvent.OpenDetail -> openDetail(event.receiptId)
            ProcessPageEvent.CloseDetail -> pages { copy(detail = null, detailLoading = false) }
            is ProcessPageEvent.AskReject -> askReject(event.receiptId)
            is ProcessPageEvent.EditRejectReason -> pages { copy(reject = reject?.copy(reason = event.reason)) }
            ProcessPageEvent.CancelReject -> pages { copy(reject = null) }
            ProcessPageEvent.ConfirmReject -> reject()
            is ProcessPageEvent.BulkApproval -> bulkApproval(event.action)
            is ProcessPageEvent.ToggleRow -> toggle(event.receiptId)
            is ProcessPageEvent.ToggleAllRows -> toggleAll(event.visibleIds)
            ProcessPageEvent.BatchPost -> batchPost()
            is ProcessPageEvent.SelectLine -> vm.update {
                copy(
                    process = process?.let { draft ->
                        draft.copy(selectedLineId = event.lineId.takeIf { it != draft.selectedLineId })
                    },
                )
            }

            is ProcessPageEvent.EditLine -> edit { draft ->
                val id = event.line.id ?: return@edit draft
                draft.copy(lines = ProcessLines.update(draft.lines, id) { event.line })
            }

            is ProcessPageEvent.EditSplitAmount -> edit { draft ->
                draft.copy(lines = ProcessLines.redistribute(draft.lines, event.lineId, event.amount))
            }

            ProcessPageEvent.SplitLine -> edit { draft ->
                val (lines, selected) = ProcessLines.split(draft.lines, draft.selectedLineId, ::newLineId)
                draft.copy(lines = lines, selectedLineId = selected)
            }

            ProcessPageEvent.AddLine -> addLine()
            is ProcessPageEvent.RemoveLine -> edit { draft ->
                draft.copy(
                    lines = ProcessLines.remove(draft.lines, event.lineId),
                    selectedLineId = draft.selectedLineId.takeIf { it != event.lineId },
                )
            }

            is ProcessPageEvent.EditTaxLine -> edit { it.copy(taxLine = event.taxLine) }
            is ProcessPageEvent.ShowHistory -> showHistory(event.open)
        }
    }

    // -- the editor ------------------------------------------------------------

    private val refs: ProcessRefs get() = vm.current.processPages.refs

    /**
     * Applies an edit to the open editor — unless the receipt sits in the
     * closed period, where the web disables every field (`ProcessReceiptModal.jsx:610`).
     */
    private fun edit(change: (ProcessDraft) -> ProcessDraft) = vm.update {
        copy(
            process = process?.let { draft ->
                if (draft.periodLocked(processPages.refs.lock)) draft else change(draft)
            },
        )
    }

    /** A new line starts empty (`createEmptyLine`) and is selected, ready to split. */
    private fun addLine() = edit { draft ->
        val line = ProcessLine(id = newLineId())
        draft.copy(lines = draft.lines + line, selectedLineId = line.id)
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
        // Bulk Process opens any row it lists (`BulkProcessPage.jsx:319-326`);
        // the Processing Queue only the rows this accountant may work.
        val gated = mode == ProcessMode.Process && state.destination != CardDestination.BulkProcess
        val allowed = state.viewer.isAccountant && (!gated || ProcessRules.canOpen(state.viewer, receipt))
        if (!allowed) return refuse()
        val lock = state.processPages.refs.lock
        vm.update { copy(process = ProcessDraft.of(receipt, mode, loading = true, today(), lock, gated)) }
        // Mark-read on open (`ProcessReceiptModal.jsx:94-102`); History passes no readScope.
        if (mode == ProcessMode.Process) vm.readRow(CardRowReads.detail(state.destination), receiptId)
        vm.run {
            val detail = vm.repo.receiptDetail(receiptId)
            if (vm.current.process?.receipt?.id != receiptId) return@run
            when (detail) {
                is ZillitResult.Success -> vm.update {
                    copy(process = ProcessDraft.of(detail.data, mode, loading = false, today(), lock, gated))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(process = null) }
                    vm.fail(detail.error.localised())
                }
            }
        }
    }

    /** The History panel: the receipt's trail, read when it opens. */
    private fun showHistory(open: Boolean) {
        val draft = vm.current.process ?: return
        vm.update { copy(process = process?.copy(history = if (open) emptyList() else null)) }
        if (!open) return
        vm.run {
            val trail = vm.repo.receiptHistory(draft.receipt.id).getOrNull().orEmpty()
            vm.update {
                copy(process = process?.takeIf { it.receipt.id == draft.receipt.id && it.history != null }
                    ?.copy(history = trail) ?: process)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod") // The web's post guard, one refusal per line.
    private fun commit(kind: Commit) {
        val state = vm.current
        val draft = state.process ?: return
        if (draft.loading) return
        // A receipt in the closed period takes no update at all (`saveAndAction`).
        if (draft.periodLocked(refs.lock)) return
        val viewer = state.viewer
        val receipt = draft.receipt
        val figures = draft.figures(refs)
        val permitted = when {
            !viewer.isAccountant -> false
            draft.mode == ProcessMode.History -> kind == Commit.Save
            draft.gated && !ProcessRules.canOpen(viewer, receipt) -> false
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
            if (missing.isNotEmpty()) {
                // The offending rows go red, then the post is blocked; Save is
                // deliberately not gated, so partial coding still persists.
                vm.update { copy(process = process?.copy(lineErrors = figures.idsMissingNominal)) }
                return vm.fail(missingNominalMessage(missing))
            }
            vm.update { copy(process = process?.copy(lineErrors = emptySet())) }
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
            taxLine = draft.taxLine.takeIf { figures.sendsTaxLine }?.let {
                TaxLineWire(figures.effectiveTax, it.account, it.trackingCodes, it.tags)
            },
        )
        // The escalate dialog closes either way; the editor only on success
        // (`ProcessReceiptModal.jsx:446-460, 993-996`).
        send(kind.notice(), onFailure = { it.copy(escalation = null) }) {
            vm.repo.saveProcessReceipt(receipt.id, submission)
        }
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
            draft.periodLocked(refs.lock) -> str(S.desktop_po_no_rights_on_project)
            !choice.complete -> str(S.desktop_card_assign_needs_person_and_reason)
            choice.userId == draft.receipt.assignedTo -> str(S.desktop_card_already_assigned_to_them)
            else -> null
        }
        if (refusal != null) return vm.fail(refusal)
        val reassign = !draft.receipt.assignedTo.isNullOrBlank()
        val assignment = ReceiptAssignment(choice.userId, state.viewer.userId, choice.finalReason)
        send(if (reassign) str(S.desktop_card_receipt_reassigned) else str(S.desktop_card_receipt_assigned)) {
            vm.repo.assignReceipt(draft.receipt.id, assignment, reassign)
        }
    }

    /**
     * One editor write: the editor closes and the queue re-reads only when it
     * lands. A refusal keeps the editor open on what was typed — closing it
     * threw the coding away with the error.
     */
    private fun send(
        notice: String,
        onFailure: (ProcessDraft) -> ProcessDraft = { it },
        block: suspend () -> ZillitResult<Unit>,
    ) {
        vm.update { copy(busy = true) }
        vm.run {
            when (val result = block()) {
                is ZillitResult.Success -> {
                    vm.update { copy(busy = false, notice = notice, process = null) }
                    vm.reload()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false, process = process?.let(onFailure)) }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    // -- the approval queue ------------------------------------------------------

    private fun pages(change: ProcessPagesState.() -> ProcessPagesState) =
        vm.update { copy(processPages = processPages.change()) }

    /** The receipt as freshest known: the open detail when it is this one, else the row. */
    private fun receipt(id: String): CardReceipt? {
        val state = vm.current
        return state.processPages.detail?.takeIf { it.id == id && !state.processPages.detailLoading }
            ?: state.receipts.firstOrNull { it.id == id }
    }

    private fun isNextApprover(viewer: CardViewer, receipt: CardReceipt): Boolean =
        viewer.isAccountant && ApprovalTiers.isNextApprover(viewer.metadata.tierConfigs, receipt, viewer.userId)

    /** Approve signs the next step — `tier_number` one past what the receipt already has. */
    private fun approve(id: String) {
        val viewer = vm.current.viewer
        val receipt = receipt(id) ?: return
        if (!isNextApprover(viewer, receipt)) return refuse()
        row(id, str(S.desktop_card_receipt_approved)) {
            vm.repo.approveReceipt(id, receipt.approvals.size + 1, viewer.userId)
        }
    }

    /** Override approves over the chain in one click, with the web's own reason. */
    private fun override(id: String) {
        val viewer = vm.current.viewer
        if (receipt(id) == null) return
        if (!viewer.canOverrideReceipt) return refuse()
        row(id, str(S.desktop_card_receipt_overridden)) {
            vm.repo.overrideReceipt(id, viewer.userId, OVERRIDE_REASON)
        }
    }

    private fun row(id: String, notice: String, block: suspend () -> ZillitResult<Unit>) {
        pages { copy(acting = id, detail = null, detailLoading = false) }
        vm.run {
            val result = block()
            pages { copy(acting = null) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.update { copy(notice = notice) }
                    vm.reload()
                }

                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    /**
     * A row opened for a closer look: at once with the row, then the full
     * detail (`ApprovalQueuePage.jsx:283-296`). A failed read keeps the row.
     */
    private fun openDetail(id: String) {
        val row = vm.current.receipts.firstOrNull { it.id == id } ?: return
        pages { copy(detail = row, detailLoading = true) }
        vm.readRow(CardRowReads.detail(vm.current.destination), id)
        vm.run {
            val read = vm.repo.receiptDetail(id)
            pages {
                if (detail?.id != id) {
                    this
                } else {
                    copy(detail = (read as? ZillitResult.Success)?.data ?: detail, detailLoading = false)
                }
            }
        }
    }

    private fun askReject(id: String) {
        val viewer = vm.current.viewer
        val receipt = receipt(id) ?: return
        if (!isNextApprover(viewer, receipt)) return refuse()
        pages { copy(reject = RejectDraft(receipt), detail = null, detailLoading = false) }
    }

    private fun reject() {
        val state = vm.current
        val draft = state.processPages.reject ?: return
        val reason = draft.reason.trim()
        if (reason.isEmpty()) return vm.fail(str(S.desktop_a_reason_is_required))
        if (!isNextApprover(state.viewer, draft.receipt)) return refuse()
        pages { copy(acting = draft.receipt.id) }
        vm.run {
            val result = vm.repo.rejectReceipt(draft.receipt.id, draft.reason, state.viewer.userId)
            pages { copy(acting = null) }
            when (result) {
                is ZillitResult.Success -> {
                    pages { copy(reject = null) }
                    vm.update { copy(notice = str(S.desktop_card_receipt_rejected)) }
                    vm.reload()
                }

                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    /**
     * The floating bar's Approve N / Override N. Approve is an approver's,
     * Override an override-holder's; rows in the closed period never go.
     */
    private fun bulkApproval(action: BulkAction) {
        val state = vm.current
        val viewer = state.viewer
        val allowed = when (action) {
            BulkAction.Approve -> viewer.isAccountant && viewer.isApprover
            BulkAction.Override -> viewer.canOverrideReceipt
            BulkAction.Reject -> false
        }
        if (!allowed) return refuse()
        val lock = state.processPages.refs.lock
        val ids = state.receipts
            .filter { it.id in state.selection && ProcessRules.approvalSelectable(it, lock) }
            .map { it.id }
        if (ids.isEmpty()) return
        val notice = when (action) {
            BulkAction.Override -> str(S.desktop_card_receipts_overridden_count, ids.size)
            else -> str(S.desktop_card_receipts_approved_count, ids.size)
        }
        pages { copy(bulkBusy = true) }
        vm.run {
            val result = vm.repo.bulkApproval(action, ids)
            pages { copy(bulkBusy = false) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.update { copy(selection = emptySet(), notice = notice) }
                    vm.reload()
                }

                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
    }

    // -- ticking rows --------------------------------------------------------------

    private fun selectable(state: CardUiState, receipt: CardReceipt): Boolean {
        val lock = state.processPages.refs.lock
        return when (state.destination) {
            CardDestination.ApprovalQueue -> ProcessRules.approvalSelectable(receipt, lock)
            CardDestination.BulkProcess -> ProcessRules.bulkSelectable(state.viewer, receipt, lock)
            else -> false
        }
    }

    /** The one guard for the checkbox and the row click alike: a row nobody may tick never enters the set. */
    private fun toggle(id: String) {
        val state = vm.current
        val receipt = state.receipts.firstOrNull { it.id == id } ?: return
        if (!selectable(state, receipt)) return
        vm.update { copy(selection = if (id in selection) selection - id else selection + id) }
    }

    private fun toggleAll(visibleIds: List<String>) {
        val state = vm.current
        val ticks = state.receipts.filter { it.id in visibleIds && selectable(state, it) }.map { it.id }.toSet()
        vm.update {
            copy(selection = if (ticks.isNotEmpty() && selection.containsAll(ticks)) emptySet() else ticks)
        }
    }

    // -- bulk process ----------------------------------------------------------------

    /**
     * Batch Post: the ticked rows this accountant may post, with the override
     * fields applied — blank keeps each row's own (`BulkProcessPage.jsx:156-185`).
     * The answer counts what went and what did not.
     */
    private fun batchPost() {
        val state = vm.current
        if (!state.viewer.isAccountant) return refuse()
        val lock = state.processPages.refs.lock
        val ids = state.receipts
            .filter { it.id in state.selection && ProcessRules.bulkSelectable(state.viewer, it, lock) }
            .map { it.id }
        if (ids.isEmpty()) return
        val coding = state.bulkCoding
        pages { copy(bulkBusy = true) }
        vm.run {
            val result = vm.repo.bulkProcess(ids, coding)
            pages { copy(bulkBusy = false) }
            when (result) {
                is ZillitResult.Success -> {
                    val outcome = result.data
                    vm.update { copy(selection = emptySet(), bulkCoding = BulkCoding()) }
                    val posted = str(S.desktop_card_posted_count, outcome.succeeded)
                    if (outcome.failed > 0) {
                        vm.fail(str(S.desktop_ce_process_posted_failed, posted, outcome.failed))
                    } else {
                        vm.update { copy(notice = posted) }
                    }
                    vm.reload()
                }

                is ZillitResult.Failure -> vm.fail(result.error.localised())
            }
        }
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

    private companion object {
        /** The reason the web files every one-click override under (`ApprovalQueuePage.jsx:166`). */
        const val OVERRIDE_REASON = "Accountant override"
    }
}

/** "Line 3 need…" / "Lines 1, 4 need…", the web's `describeMissingNominals` alert. */
internal fun missingNominalMessage(rows: List<Int>): String {
    val named = if (rows.size == 1) {
        str(S.ah_line_num, rows.single())
    } else {
        str(S.desktop_ce_process_lines_many, rows.joinToString(", "))
    }
    return str(S.desktop_ce_process_need_nominal, named)
}

/**
 * What the four process pages read (`CardPageLoader` hands them here): their
 * rows, and — once per production — the references they work against.
 *
 * Bulk Process lists the process queue's approved rows, as the web's does
 * (`BulkProcessPage.jsx:79-81`); the `/bulk` read listed a different set.
 */
internal suspend fun CardExpensesViewModel.readProcessPage(destination: CardDestination): ZillitResult<Reducer> {
    val rows = when (destination) {
        CardDestination.ApprovalQueue -> repo.approvalQueue()
        CardDestination.ProcessQueue -> repo.receipts(ReceiptScope.ProcessQueue)
        CardDestination.BulkProcess -> repo.receipts(ReceiptScope.ProcessQueue)
            .map { list -> list.filter { it.status == CardWorkflowStatus.Approved } }

        else -> repo.receipts(ReceiptScope.Posted)
    }
    if (rows is ZillitResult.Failure) return rows
    val list = (rows as ZillitResult.Success).data
    val known = current.processPages.refs.takeIf { it.loaded }
    val refs = known ?: repo.processReferences().getOrNull() ?: ProcessRefs()
    return ZillitResult.Success {
        copy(
            receipts = list,
            processPages = processPages.copy(
                refs = refs,
                detail = processPages.detail.takeIf { destination == CardDestination.ApprovalQueue },
                reject = processPages.reject.takeIf { destination == CardDestination.ApprovalQueue },
            ),
        )
    }
}
