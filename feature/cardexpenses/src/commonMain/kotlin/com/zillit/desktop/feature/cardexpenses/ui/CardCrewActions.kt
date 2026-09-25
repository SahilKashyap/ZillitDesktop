package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cardexpenses.domain.AttachmentChange
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachmentUploader
import com.zillit.desktop.feature.cardexpenses.domain.CardCompanies
import com.zillit.desktop.feature.cardexpenses.domain.CardCrewHost
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.CrewRules
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.PickKind
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCoding
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptEdit
import com.zillit.desktop.feature.cardexpenses.domain.TierVisibility
import com.zillit.desktop.feature.cardexpenses.domain.UploadHeadroom

/**
 * The cardholder pages' handlers: My Transactions, Card Extension, the crew
 * Approval Queue and the Coding Queue.
 *
 * Every write checks, here and not only on screen, that the viewer is on the
 * page that offers it and that the row allows it — the screen hides a button,
 * and this is what refuses the event if it arrives anyway.
 */
@Suppress("TooManyFunctions") // One handler per crew action.
internal class CardCrewActions(
    private val vm: CardExpensesViewModel,
    private val uploader: CardAttachmentUploader?,
    private val host: CardCrewHost,
) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per event.
    fun handle(event: CrewEvent) {
        when (event) {
            CrewEvent.Prime -> prime()
            is CrewEvent.Filter -> crew { copy(filter = event.status) }

            is CrewEvent.OpenReceipt -> openReceipt(event.receiptId)
            CrewEvent.CloseDetail -> crew { copy(detail = null) }
            CrewEvent.ToggleDetailHistory -> toggleHistory()

            is CrewEvent.OpenEdit -> openEdit(event.receiptId)
            is CrewEvent.EditReceipt -> crew { copy(edit = event.draft) }
            CrewEvent.PickEditFile -> pickEditFile()
            CrewEvent.RemoveEditFile -> crew {
                val draft = edit ?: return@crew this
                // Removing a newly picked file leaves nothing: picking it had
                // already let go of the old one, as the web's Replace does.
                copy(edit = if (draft.newFile != null) draft.copy(newFile = null) else draft.copy(keepExisting = false))
            }
            CrewEvent.CloseEdit -> crew { copy(edit = edit?.takeIf { it.saving }) }
            CrewEvent.SaveEdit -> saveEdit()

            is CrewEvent.AskDelete -> crew { copy(deleteId = event.receiptId) }
            CrewEvent.ConfirmDelete -> confirmDelete()

            CrewEvent.OpenUpload -> openUpload()
            CrewEvent.CloseUpload -> {
                crew { copy(uploadOpen = false) }
                vm.update { copy(draft = listOf(DraftCardReceipt())) }
            }
            CrewEvent.SubmitUpload -> submitUpload()

            is CrewEvent.SelectTopUpCard -> {
                crew { copy(topUpCardId = event.cardId, topUpTrailId = null) }
                vm.reload()
            }
            is CrewEvent.OpenTopUpRequest -> openTopUpRequest(event.open)
            is CrewEvent.EditTopUpRequest -> crew { copy(topUpRequest = event.draft) }
            CrewEvent.SendTopUpRequest -> sendTopUpRequest()
            is CrewEvent.OpenTopUpTrail -> crew { copy(topUpTrailId = event.topUpId) }

            is CrewEvent.ShowApprovalTab -> crew { copy(approvalTab = event.tab) }
            is CrewEvent.OpenApprovalCard -> crew { copy(approvalCardId = event.cardId) }
            is CrewEvent.ApproveCard -> approveCard(event.cardId)
            is CrewEvent.OpenApprovalReceipt -> openApprovalReceipt(event.receiptId)
            is CrewEvent.ApproveReceipt -> approveReceipt(event.receiptId)
            is CrewEvent.AskReject -> askReject(event.targetId, event.receipt)
            is CrewEvent.EditReject -> crew { copy(reject = reject?.copy(reason = event.reason)) }
            CrewEvent.CloseReject -> crew { copy(reject = null) }
            CrewEvent.ConfirmReject -> confirmReject()

            is CrewEvent.OpenCode -> openCode(event.receiptId)
            is CrewEvent.EditCode -> crew { copy(code = event.draft) }
            CrewEvent.SaveCodeDraft -> commitCode(CodeCommit.Draft)
            CrewEvent.SubmitCode -> commitCode(CodeCommit.Submit)
            CrewEvent.ApproveAndSubmitCode -> commitCode(CodeCommit.ApproveAndSubmit)
        }
    }

    // -- host data -------------------------------------------------------------

    /**
     * Reads the companies and the chart once a crew page is up.
     *
     * Each page asks again when it mounts: both lists belong to the open
     * production, and a production switch would otherwise leave the last
     * one's codes in the pickers.
     */
    private fun prime() {
        crew { copy(isTelevision = host.isTelevision()) }
        vm.run {
            val companies = host.companies()
            val nominals = host.nominals()
            crew { copy(companies = companies, nominals = nominals) }
        }
    }

    // -- My Transactions ---------------------------------------------------------

    private fun openReceipt(receiptId: String) {
        val receipt = vm.current.receipts.firstOrNull { it.id == receiptId } ?: return
        // A rejected card opens straight into Edit & Resubmit (`UserReceiptsPage.jsx:926`).
        if (receipt.status == CardWorkflowStatus.Rejected) return openEdit(receiptId)
        showDetail(receipt, CrewOrigin.MyTransactions)
        vm.readRow(CardRowReads.detail(vm.current.destination), receiptId)
    }

    /**
     * Opens the read-only view on the slim row, then swaps in the detail read.
     *
     * The response is kept only if the same receipt is still open — a late
     * answer for a receipt somebody has since closed must not reopen it.
     */
    private fun showDetail(receipt: CardReceipt, origin: CrewOrigin) {
        crew { copy(detail = CrewReceiptView(receipt = receipt, loading = true, origin = origin)) }
        vm.run {
            val full = vm.repo.receiptDetail(receipt.id).getOrNull()
            crew {
                val open = detail?.takeIf { it.receipt.id == receipt.id } ?: return@crew this
                copy(detail = open.copy(receipt = full ?: open.receipt, loading = false))
            }
        }
    }

    private fun toggleHistory() {
        val view = vm.current.crew.detail ?: return
        crew { copy(detail = view.copy(historyOpen = !view.historyOpen)) }
        if (view.historyOpen || view.history != null) return
        vm.run {
            val trail = vm.repo.receiptHistory(view.receipt.id).getOrNull().orEmpty()
            crew {
                val open = detail?.takeIf { it.receipt.id == view.receipt.id } ?: return@crew this
                copy(detail = open.copy(history = trail))
            }
        }
    }

    /**
     * Edit Receipt, from a rejected card, a card's Upload Receipt, or the
     * detail view — the only three doors the web has.
     */
    private fun openEdit(receiptId: String) {
        val state = vm.current
        val opened = state.crew.detail?.receipt?.takeIf { it.id == receiptId }
        val receipt = opened ?: state.receipts.firstOrNull { it.id == receiptId } ?: return
        val allowed = state.destination == CardDestination.MyTransactions &&
            (
                receipt.status == CardWorkflowStatus.Rejected ||
                    CrewRules.canEdit(receipt) || CrewRules.offersUpload(receipt)
                )
        if (!allowed) return vm.fail(str(S.desktop_po_no_rights_on_project))
        crew { copy(detail = null, edit = ReceiptEditDraft.of(receipt)) }
    }

    private fun pickEditFile() {
        val pick = uploader ?: return
        vm.run {
            vm.update { copy(uploading = true) }
            val stored = pick.pick(PickKind.Receipt)
            vm.update { copy(uploading = false) }
            when (stored) {
                is ZillitResult.Failure -> vm.fail(stored.error.localised())
                is ZillitResult.Success -> stored.data?.let { file ->
                    crew { copy(edit = edit?.copy(newFile = file, keepExisting = false)) }
                }
            }
        }
    }

    /**
     * The Edit Receipt save (`UserReceiptsPage.jsx:1413-1489`): the PATCH, and
     * for a rejected receipt the confirm that resubmits it.
     */
    private fun saveEdit() {
        val state = vm.current
        val draft = state.crew.edit ?: return
        if (draft.saving || state.destination != CardDestination.MyTransactions) return
        val receipt = draft.receipt
        val active = CrewRules.activeCard(state.cards)
        val headroom = UploadHeadroom.of(active)
        val date = CardDates.toMillis(draft.date)
        val amount = draft.amount.trim().toDoubleOrNull()
        val error = CrewRules.editError(
            accountantSent = CrewRules.accountantSent(receipt),
            hasAttachment = draft.hasAttachment,
            description = draft.merchant,
            date = date,
            amount = draft.amount,
            overLimit = if (headroom.editExceeds(receipt.amount, amount ?: 0.0)) {
                str(S.desktop_ce_crew_edit_over, money(headroom.available, active?.currency))
            } else {
                null
            },
            now = host.now(),
        )
        if (error != null) return vm.fail(error)

        val patch = draft.patch(date, amount, active)
        crew { copy(edit = draft.copy(saving = true)) }
        commit(
            notice = str(if (draft.rejected) S.ah_receipt_resubmitted_toast else S.desktop_card_receipt_saved),
            onDone = { copy(edit = null) },
            onFail = { copy(edit = edit?.copy(saving = false)) },
        ) {
            when (val saved = vm.repo.updateReceipt(receipt.id, patch)) {
                is ZillitResult.Failure -> saved
                is ZillitResult.Success -> if (draft.rejected) vm.repo.confirmReceipt(receipt.id) else saved
            }
        }
    }

    /** The dialog as the PATCH wants it; card and currency are the receipt's own, else the active card's. */
    private fun ReceiptEditDraft.patch(date: Long?, amount: Double?, active: ExpenseCard?) = ReceiptEdit(
        description = merchant,
        amount = amount,
        date = date,
        cardId = receipt.cardId ?: active?.id,
        currency = receipt.currency ?: active?.currency,
        nominalCode = costCode.trim(),
        episode = episode.trim(),
        codeDescription = codeDescription.trim(),
        category = category,
        urgent = urgent,
        requestTopUp = topUp,
        attachment = when {
            newFile != null -> AttachmentChange.Replace(newFile)
            !keepExisting && !receipt.attachmentKey.isNullOrBlank() -> AttachmentChange.Remove
            else -> AttachmentChange.Keep
        },
    )

    private fun confirmDelete() {
        val state = vm.current
        val id = state.crew.deleteId ?: return
        val receipt = state.receipts.firstOrNull { it.id == id }
        crew { copy(deleteId = null) }
        if (state.destination != CardDestination.MyTransactions || receipt == null || !CrewRules.canDelete(receipt)) {
            return vm.fail(str(S.desktop_po_no_rights_on_project))
        }
        commit(str(S.ah_receipt_deleted_toast)) { vm.repo.deleteReceipt(id) }
    }

    /** Only with an active card and headroom left (`UserReceiptsPage.jsx:872-893`). */
    private fun openUpload() {
        val state = vm.current
        val active = CrewRules.activeCard(state.cards)
        if (state.destination != CardDestination.MyTransactions || active == null) return
        if (UploadHeadroom.of(active).exhausted) return vm.fail(str(S.desktop_ce_crew_upload_unavailable))
        crew { copy(uploadOpen = true) }
        vm.update { copy(draft = listOf(DraftCardReceipt())) }
    }

    /**
     * Submits the batch against the active card.
     *
     * The company rides on the card: its pin, or the company that owns its
     * bank (`resolveCardCompany`) — worked out once, since every receipt in
     * the batch spends against the same card.
     */
    private fun submitUpload() {
        val state = vm.current
        if (state.busy || !state.crew.uploadOpen) return
        val active = CrewRules.activeCard(state.cards) ?: return vm.fail(str(S.desktop_ce_crew_no_active_card))
        CrewRules.batchError(state.draft, host.now())?.let { return vm.fail(it) }
        val headroom = UploadHeadroom.of(active)
        if (headroom.batchExceeds(state.draftTotal)) {
            return vm.fail(str(S.desktop_ce_crew_batch_over, money(headroom.available, active.currency)))
        }
        val card = active.copy(companyId = CardCompanies.resolve(active, state.crew.companies))
        vm.update { copy(busy = true) }
        vm.run {
            when (val sent = vm.repo.submitReceipts(card, vm.current.draft)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            busy = false,
                            notice = str(S.desktop_card_receipts_uploaded),
                            draft = listOf(DraftCardReceipt()),
                            crew = crew.copy(uploadOpen = false),
                        )
                    }
                    vm.reload()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false) }
                    vm.fail(sent.error.localised())
                }
            }
        }
    }

    // -- Card Extension --------------------------------------------------------

    private fun selectedTopUpCard(): ExpenseCard? {
        val state = vm.current
        val toppable = CrewRules.toppableCards(state.cards)
        return toppable.firstOrNull { it.id == state.crew.topUpCardId } ?: toppable.firstOrNull()
    }

    private fun openTopUpRequest(open: Boolean) {
        if (!open) return crew { copy(topUpRequest = topUpRequest?.takeIf { it.sending }) }
        if (vm.current.destination != CardDestination.CardExtension) return
        val card = selectedTopUpCard() ?: return
        crew { copy(topUpRequest = TopUpRequestDraft(cardId = card.id)) }
    }

    /** Amount and reason, both required; currency is the card's and never sent (ZL-20808). */
    private fun sendTopUpRequest() {
        val state = vm.current
        val draft = state.crew.topUpRequest ?: return
        val amount = draft.amountValue
        if (!draft.canSend || amount == null) return
        if (CrewRules.toppableCards(state.cards).none { it.id == draft.cardId }) {
            return vm.fail(str(S.desktop_po_no_rights_on_project))
        }
        crew { copy(topUpRequest = draft.copy(sending = true)) }
        commit(
            notice = str(S.desktop_card_topup_requested),
            onDone = { copy(topUpRequest = null) },
            onFail = { copy(topUpRequest = topUpRequest?.copy(sending = false)) },
        ) { vm.repo.requestTopUp(draft.cardId, amount, draft.reason.trim()) }
    }

    // -- Approval Queue --------------------------------------------------------
    //
    // Opening a card or receipt here reads nothing (ZL-20775,
    // `CardsForApprovalPage.jsx:103-108`): the chip stays until the approver
    // acts, and approve/reject read that row (`markCardRead` / `markReceiptRead`,
    // `:45-50`, `:150-194`).

    /** Where a queued card request stands for this viewer — the web's `getApprovalVisibility`. */
    private fun visibility(card: ExpenseCard): TierVisibility {
        val viewer = vm.current.viewer
        val chain = ApprovalTiers.resolve(viewer.metadata.tierConfigs, card.departmentId, card.monthlyLimit)
        return ApprovalTiers.visibility(chain, card.approvals, viewer.userId)
    }

    private fun onApprovalQueue(): Boolean {
        val state = vm.current
        return state.destination == CardDestination.CardsForApproval && state.viewer.isApprover
    }

    private fun approveCard(cardId: String) {
        val state = vm.current
        val card = state.crew.approvalCards.firstOrNull { it.id == cardId } ?: return
        val step = visibility(card)
        if (!onApprovalQueue() || !step.canApprove || step.nextTier == null) {
            return vm.fail(str(S.desktop_po_no_rights_on_project))
        }
        if (state.crew.actionId != null) return
        crew { copy(actionId = cardId) }
        commit(
            notice = str(S.ah_card_approved_toast),
            onDone = { copy(actionId = null) },
            onFail = { copy(actionId = null) },
            onSuccess = { vm.readRow(CARD_APPROVAL_QUEUE, cardId) },
        ) { vm.repo.approveCard(cardId, step, state.viewer.userId) }
    }

    private fun openApprovalReceipt(receiptId: String) {
        val receipt = vm.current.crew.approvalReceipts.firstOrNull { it.id == receiptId } ?: return
        showDetail(receipt, CrewOrigin.ApprovalQueue)
    }

    private fun approveReceipt(receiptId: String) {
        val state = vm.current
        if (!onApprovalQueue() || state.crew.approvalReceipts.none { it.id == receiptId }) {
            return vm.fail(str(S.desktop_po_no_rights_on_project))
        }
        if (state.crew.actionId != null) return
        crew { copy(actionId = receiptId, detail = null) }
        commit(
            notice = str(S.desktop_card_receipt_approved),
            onDone = { copy(actionId = null) },
            onFail = { copy(actionId = null) },
            onSuccess = { vm.readRow(RECEIPT_APPROVAL_QUEUE, receiptId) },
        ) {
            // The web's `{ tier_number: approvals.length + 1, user_id }` (`CardsForApprovalPage.jsx:171`).
            val approvals = state.crew.approvalReceipts.first { it.id == receiptId }.approvals.size
            vm.repo.approveReceipt(receiptId, tierNumber = approvals + 1, userId = state.viewer.userId)
        }
    }

    private fun askReject(targetId: String, receipt: Boolean) {
        val state = vm.current
        val subject = if (receipt) {
            state.crew.approvalReceipts.firstOrNull { it.id == targetId }?.description?.takeIf { it.isNotBlank() }
                ?: str(S.ah_this_transaction)
        } else {
            val card = state.crew.approvalCards.firstOrNull { it.id == targetId } ?: return
            state.personName(card.holderId).takeIf { it != "—" } ?: str(S.desktop_card_card_holder)
        }
        crew { copy(detail = null, reject = CrewReject(targetId = targetId, receipt = receipt, subject = subject)) }
    }

    private fun confirmReject() {
        val state = vm.current
        val reject = state.crew.reject ?: return
        val reason = reject.reason.trim()
        if (reason.isEmpty() || state.crew.actionId != null) return
        val allowed = onApprovalQueue() && if (reject.receipt) {
            state.crew.approvalReceipts.any { it.id == reject.targetId }
        } else {
            state.crew.approvalCards.firstOrNull { it.id == reject.targetId }?.let { visibility(it).canApprove } == true
        }
        if (!allowed) return vm.fail(str(S.desktop_po_no_rights_on_project))
        crew { copy(actionId = reject.targetId) }
        commit(
            notice = str(if (reject.receipt) S.desktop_card_receipt_rejected else S.ah_card_rejected_toast),
            onDone = { copy(actionId = null, reject = null) },
            onFail = { copy(actionId = null) },
            onSuccess = {
                vm.readRow(if (reject.receipt) RECEIPT_APPROVAL_QUEUE else CARD_APPROVAL_QUEUE, reject.targetId)
            },
        ) {
            if (reject.receipt) {
                // The web's `{ reason, user_id }` (`CardsForApprovalPage.jsx:189`).
                vm.repo.rejectReceipt(reject.targetId, reason, state.viewer.userId)
            } else {
                vm.repo.rejectCard(reject.targetId, reason, state.viewer.userId)
            }
        }
    }

    // -- Coding Queue ------------------------------------------------------------

    private fun openCode(receiptId: String?) {
        if (receiptId == null) return crew { copy(code = code?.takeIf { it.saving }) }
        val receipt = vm.current.receipts.firstOrNull { it.id == receiptId } ?: return
        crew { copy(code = CodeReceiptDraft.of(receipt)) }
        // Both level_1s, as the tab sums both (`CodingQueuePage.jsx:66-81`, ZL-20779).
        vm.readRow("coding_queue", receiptId)
        vm.readRow("pending_coding", receiptId)
    }

    /**
     * The Code Receipt dialog's three buttons (`CodingQueuePage.jsx:83-138`).
     *
     * Save Draft may go with no code; both submits need one, and Approve &
     * Submit is the approver's alone.
     */
    private fun commitCode(kind: CodeCommit) {
        val state = vm.current
        val draft = state.crew.code ?: return
        if (draft.saving) return
        codeRefusal(state, kind, draft)?.let { return vm.fail(it) }
        val coding = ReceiptCoding(
            nominalCode = draft.costCode.trim(),
            episode = draft.episode.trim().takeIf { it.isNotEmpty() },
            codeDescription = draft.description.trim().takeIf { it.isNotEmpty() },
            cardId = draft.receipt.cardId,
            currency = draft.receipt.currency,
        )
        val id = draft.receipt.id
        crew { copy(code = draft.copy(saving = true)) }
        commit(
            notice = str(
                when (kind) {
                    CodeCommit.Draft -> S.desktop_card_coding_saved
                    CodeCommit.Submit -> S.ah_sent_for_approval_toast
                    CodeCommit.ApproveAndSubmit -> S.desktop_card_coded_and_approved
                },
            ),
            onDone = { copy(code = null) },
            onFail = { copy(code = code?.copy(saving = false)) },
        ) {
            when (kind) {
                CodeCommit.Draft -> vm.repo.updateReceiptCoding(id, coding)
                CodeCommit.Submit -> vm.repo.submitReceiptForApproval(id, coding)
                CodeCommit.ApproveAndSubmit -> vm.repo.approveAndSubmitReceipt(id, coding)
            }
        }
    }

    /** Why this commit may not go: the coordinator's page only, a code to move on, an approver to approve. */
    private fun codeRefusal(state: CardUiState, kind: CodeCommit, draft: CodeReceiptDraft): String? {
        val viewer = state.viewer
        val allowed = state.destination == CardDestination.CodingQueue && viewer.isCoordinator && !viewer.isAccountant
        return when {
            !allowed -> str(S.desktop_po_no_rights_on_project)
            kind != CodeCommit.Draft && draft.costCode.isBlank() -> str(S.desktop_card_nominal_code_needed)
            kind == CodeCommit.ApproveAndSubmit && !viewer.isApprover -> str(S.desktop_card_not_an_approver)
            else -> null
        }
    }

    // -- plumbing ----------------------------------------------------------------

    private fun crew(transform: CrewState.() -> CrewState) = vm.update { copy(crew = crew.transform()) }

    /**
     * Runs a write; on success shows [notice], applies [onDone] and re-reads
     * the page, on failure applies [onFail] and shows the server's refusal.
     */
    private fun commit(
        notice: String,
        onDone: CrewState.() -> CrewState = { this },
        onFail: CrewState.() -> CrewState = { this },
        onSuccess: () -> Unit = {},
        block: suspend () -> ZillitResult<Unit>,
    ) = vm.run {
        when (val result = block()) {
            is ZillitResult.Success -> {
                onSuccess()
                vm.update { copy(notice = notice, crew = crew.onDone()) }
                vm.reload()
            }

            is ZillitResult.Failure -> {
                crew(onFail)
                vm.fail(result.error.localised())
            }
        }
    }

    private enum class CodeCommit { Draft, Submit, ApproveAndSubmit }

    private companion object {
        /** The crew Approval Queue's two `level_1`s (`CardsForApprovalPage.jsx:36-43`). */
        const val CARD_APPROVAL_QUEUE = "card_approval_queue"
        const val RECEIPT_APPROVAL_QUEUE = "receipt_approval_queue"
    }
}
