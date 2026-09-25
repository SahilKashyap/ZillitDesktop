package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardAction
import com.zillit.desktop.feature.cardexpenses.domain.CardNumbers
import com.zillit.desktop.feature.cardexpenses.domain.CardReference
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardServerNote
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapGuard

/**
 * The register's, the card detail's and the Card tab's actions.
 *
 * Every handler re-checks the right it needs against the viewer — the screen
 * hiding a button is not the gate. Approve, Suspend and Reactivate act at once,
 * as on the web; reject, override, assign and delete go through their dialogs.
 */
@Suppress("TooManyFunctions") // One handler per action on the card lifecycle.
internal class CardsActions(
    private val vm: CardExpensesViewModel,
    /** Opens a card's drilldown reads; the register owns them. */
    private val select: (String?) -> Unit,
    /** The production's currencies, rates, companies and banks; a host seam. */
    private val reference: suspend () -> CardReference,
) {

    /** Reads the reference once a production is open; a failure leaves the defaults. */
    suspend fun start() {
        val loaded = reference()
        vm.update { copy(cardsArea = cardsArea.copy(reference = loaded)) }
    }

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per event.
    fun handle(event: CardsEvent) {
        when (event) {
            is CardsEvent.OpenCard -> open(event.cardId)
            is CardsEvent.ShowHistory -> area { copy(historyOpen = event.open) }
            is CardsEvent.SetFullScreen -> vm.update { copy(fullScreen = event.on) }
            CardsEvent.StartBsEdit -> vm.current.openCard?.let { card ->
                if (vm.current.canCorrectBsCode(card)) area { copy(bsDraft = card.bsControlCode.orEmpty()) }
            }

            is CardsEvent.EditBsDraft -> area { copy(bsDraft = bsDraft?.let { event.code }) }
            CardsEvent.CancelBsEdit -> area { copy(bsDraft = null) }
            CardsEvent.SaveBsEdit -> saveBsCode()
            is CardsEvent.Approve -> approve(event.cardId)
            is CardsEvent.Suspend -> lifecycle(event.cardId, CardStatus.Active) { CardAction.Suspend(it) }
            is CardsEvent.Reactivate -> lifecycle(event.cardId, CardStatus.Suspended) { CardAction.Reactivate(it) }
            is CardsEvent.AskReject -> if (canDecide(event.cardId)) area { copy(reject = ReasonDraft(event.cardId)) }
            is CardsEvent.EditRejectReason -> area { copy(reject = reject?.copy(reason = event.reason)) }
            CardsEvent.ConfirmReject -> reject()
            is CardsEvent.AskOverride -> if (canOverride(event.cardId)) {
                area { copy(override = ReasonDraft(event.cardId)) }
            }

            is CardsEvent.EditOverrideReason -> area { copy(override = override?.copy(reason = event.reason)) }
            CardsEvent.ConfirmOverride -> override()
            is CardsEvent.AskAssignPhysical -> if (canAssignPhysical(event.cardId)) {
                area { copy(assignPhysical = NumberDraft(event.cardId)) }
            }

            is CardsEvent.EditPhysicalNumber ->
                area { copy(assignPhysical = assignPhysical?.copy(digits = CardNumbers.digits(event.typed))) }

            CardsEvent.ConfirmAssignPhysical -> assignPhysical()
            is CardsEvent.AskDelete -> if (canDelete(event.cardId)) area { copy(deleteCardId = event.cardId) }
            CardsEvent.ConfirmDelete -> delete()
            CardsEvent.CloseDialog -> area {
                copy(
                    reject = null,
                    override = null,
                    assignPhysical = null,
                    deleteCardId = null,
                    crewRequest = null,
                    crewEdit = null,
                )
            }

            CardsEvent.OpenCrewRequest -> openCrewRequest()
            is CardsEvent.EditCrewRequest -> area { copy(crewRequest = crewRequest?.let { event.draft }) }
            CardsEvent.SubmitCrewRequest -> submitCrewRequest()
            is CardsEvent.OpenCrewEdit -> openCrewEdit(event.cardId)
            is CardsEvent.EditCrewEdit -> area { copy(crewEdit = crewEdit?.let { event.draft }) }
            CardsEvent.SubmitCrewEdit -> submitCrewEdit()
            CardsEvent.OpenApprovers -> vm.emit(CardEffect.Navigate(APPROVERS_PATH))
            is CardsEvent.OpenReceipt -> openReceipt(event.receiptId)
        }
    }

    private fun area(reducer: CardsArea.() -> CardsArea) = vm.update { copy(cardsArea = cardsArea.reducer()) }

    private fun card(cardId: String): ExpenseCard? = vm.current.cards.firstOrNull { it.id == cardId }

    /**
     * Opens a card full-page and reads what it has been spent on; closing
     * drops the drilldown with it.
     */
    private fun open(cardId: String?) {
        area { copy(openCardId = cardId, historyOpen = false, bsDraft = null) }
        select(cardId)
        if (cardId != null) readScope(vm.current.destination)?.let { vm.readRow(it, cardId) }
    }

    /**
     * The badge a card detail reads as it opens — the `readScope` each page
     * hands `CardDetailModal` (`CardDetailModal.jsx:157-164`): the register
     * `card_register` (`CardRegisterPage.jsx:823`), the Card tab `my_cards`
     * (`UserCardsPage.jsx:321`). None elsewhere.
     */
    private fun readScope(destination: CardDestination): String? = when (destination) {
        CardDestination.CardRegister -> "card_register"
        CardDestination.MyCards -> "my_cards"
        else -> null
    }

    // -- rights ------------------------------------------------------------------

    private fun canDecide(cardId: String): Boolean =
        card(cardId)?.let { vm.current.cardApproval(it).canApprove } == true

    private fun canOverride(cardId: String): Boolean =
        vm.current.viewer.canOverrideCard && card(cardId)?.status == CardStatus.Pending

    private fun canAssignPhysical(cardId: String): Boolean =
        vm.current.viewer.isAccountant && card(cardId)?.isDigitalActive == true

    private fun canDelete(cardId: String): Boolean =
        card(cardId)?.let { CardRules.canDeleteRequest(it, vm.current.viewer.userId) } == true

    // -- the lifecycle ----------------------------------------------------------

    private fun approve(cardId: String) {
        val card = card(cardId) ?: return refuse()
        val step = vm.current.cardApproval(card)
        if (!step.canApprove || step.nextTier == null) return refuse()
        vm.cardWrite(CardAction.Approve(cardId, step, vm.current.viewer.userId), str(S.ah_card_approved_toast))
    }

    /** Suspend and Reactivate: an accountant's, on a card in the one status each applies to. */
    private fun lifecycle(cardId: String, from: CardStatus, action: (String) -> CardAction) {
        val state = vm.current
        if (!state.viewer.isAccountant || card(cardId)?.status != from) return refuse()
        val fallback = if (from == CardStatus.Active) S.ah_card_suspended_toast else S.ah_card_reactivated_toast
        vm.cardWrite(action(cardId), str(fallback))
    }

    private fun reject() {
        val draft = vm.current.cardsArea.reject ?: return
        if (!canDecide(draft.cardId)) return refuse()
        val reason = draft.reason.trim()
        if (reason.isEmpty()) return
        vm.cardWrite(
            CardAction.Reject(draft.cardId, reason, vm.current.viewer.userId),
            str(S.ah_card_rejected_toast),
        ) { copy(cardsArea = cardsArea.copy(reject = null)) }
    }

    /** The reason is optional; the web records its own when none is given (`:447`). */
    private fun override() {
        val draft = vm.current.cardsArea.override ?: return
        if (!canOverride(draft.cardId)) return refuse()
        val reason = draft.reason.trim().ifEmpty { OVERRIDE_REASON }
        vm.cardWrite(
            CardAction.Override(draft.cardId, reason, vm.current.viewer.userId),
            str(S.desktop_card_overridden_toast),
        ) { copy(cardsArea = cardsArea.copy(override = null)) }
    }

    /** Exactly sixteen digits, onto a live digital card (`CardRegisterPage.jsx:511-531`). */
    private fun assignPhysical() {
        val draft = vm.current.cardsArea.assignPhysical ?: return
        if (!canAssignPhysical(draft.cardId)) return refuse()
        if (draft.digits.length != CardNumbers.LENGTH) return
        vm.cardWrite(
            CardAction.AssignPhysical(draft.cardId, draft.digits),
            str(S.ah_physical_card_assigned_toast),
        ) { copy(cardsArea = cardsArea.copy(assignPhysical = null)) }
    }

    /** Deleting a request closes its detail, which is about to point at nothing. */
    private fun delete() {
        val cardId = vm.current.cardsArea.deleteCardId ?: return
        if (!canDelete(cardId)) return refuse()
        area { copy(deleteCardId = null) }
        vm.cardWrite(CardAction.Delete(cardId), str(S.desktop_card_request_deleted)) {
            copy(
                cardsArea = cardsArea.copy(openCardId = cardsArea.openCardId.takeUnless { it == cardId }),
                selectedCardId = selectedCardId.takeUnless { it == cardId },
            )
        }
    }

    /**
     * The inline control-code correction: an accountant's, until the first
     * receipt exists against the card. The draft stays open on a failure so
     * the edit is not lost (`CardDetailModal.jsx:242-253`).
     */
    private fun saveBsCode() {
        val state = vm.current
        val card = state.openCard ?: return
        val code = state.cardsArea.bsDraft?.trim().orEmpty()
        if (!state.canCorrectBsCode(card)) return refuse()
        if (code.isEmpty()) return
        vm.cardWrite(
            CardAction.BsCode(card.id, code, state.viewer.userId),
            str(S.desktop_card_control_code_updated),
        ) { copy(cardsArea = cardsArea.copy(bsDraft = null)) }
    }

    // -- the cardholder's own request -------------------------------------------

    /** Offered only while no card of theirs blocks a new one (`UserCardsPage.jsx:353`). */
    private fun openCrewRequest() {
        val state = vm.current
        if (state.viewer.isAccountant) return
        if (state.cards.any { it.holderId == state.viewer.userId && CardRules.blocksNewRequest(it) }) return
        area { copy(crewRequest = CrewCardDraft(currency = state.defaultCurrency)) }
    }

    /**
     * The crew's request, in the web's order: a limit, a currency, the cap,
     * then a justification — each refused with a toast (`:175-199`).
     */
    private fun submitCrewRequest() {
        val state = vm.current
        val draft = state.cardsArea.crewRequest ?: return
        if (state.viewer.isAccountant) return refuse()
        val currency = draft.currency.ifBlank { state.defaultCurrency }
        val problem = crewProblem(state, draft, currency, currencyMessage = S.desktop_ce_cards_err_currency)
        if (problem != null) return vm.fail(problem)
        val me = state.viewer.userId
        vm.cardWrite(
            CardAction.CrewRequest(
                userId = me,
                departmentId = state.people.firstOrNull { it.id == me }?.departmentId?.ifBlank { null },
                proposedLimit = draft.limitValue,
                justification = draft.justification.trim(),
                currency = currency,
            ),
            str(S.ah_card_requested_toast),
        ) { copy(cardsArea = cardsArea.copy(crewRequest = null)) }
    }

    private fun openCrewEdit(cardId: String) {
        val state = vm.current
        val card = card(cardId) ?: return
        if (!CardRules.canEditRequest(card, state.viewer.userId, isAccountant = false)) return refuse()
        area {
            copy(
                crewEdit = CrewCardDraft(
                    cardId = card.id,
                    // Seeded from `monthly_limit`, as the web's is (`:314, 388`).
                    limit = card.monthlyLimit?.takeIf { it > 0 }?.let(::plainAmount).orEmpty(),
                    currency = card.currency.orEmpty(),
                    justification = card.justification.orEmpty(),
                ),
            )
        }
    }

    /**
     * The crew's re-submit. Refusals stay inside the dialog; on success the
     * detail stays open on the updated card (`UserCardsPage.jsx:255-261`).
     */
    private fun submitCrewEdit() {
        val state = vm.current
        val draft = state.cardsArea.crewEdit ?: return
        val card = state.cards.firstOrNull { it.id == draft.cardId } ?: return
        if (!CardRules.canEditRequest(card, state.viewer.userId, isAccountant = false)) return refuse()
        val currency = draft.currency.ifBlank { state.defaultCurrency }
        val problem = crewProblem(state, draft, currency, currencyMessage = S.ah_err_currency_required)
        area { copy(crewEdit = crewEdit?.copy(error = problem)) }
        if (problem == null) sendCrewEdit(card.id, draft, currency)
    }

    private fun sendCrewEdit(cardId: String, draft: CrewCardDraft, currency: String) = vm.run {
        vm.update { copy(busy = true, cardsArea = cardsArea.copy(actionCardId = cardId)) }
        val result = vm.repo.cardAction(
            CardAction.CrewEdit(cardId, draft.limitValue, draft.justification.trim(), currency),
        )
        vm.update { copy(busy = false, cardsArea = cardsArea.copy(actionCardId = null)) }
        when (result) {
            is ZillitResult.Success -> {
                val wasOpen = vm.current.cardsArea.openCardId == cardId
                area { copy(crewEdit = null, openCardId = cardId) }
                if (!wasOpen) select(cardId)
                vm.reload()
            }

            is ZillitResult.Failure -> area {
                copy(
                    crewEdit = crewEdit?.copy(
                        error = result.error.localised().ifBlank { str(S.desktop_ce_cards_err_update) },
                    ),
                )
            }
        }
    }

    private fun crewProblem(state: CardUiState, draft: CrewCardDraft, currency: String, currencyMessage: String) =
        when {
            draft.limitValue <= 0 -> str(S.desktop_ce_cards_err_limit)
            currency.isBlank() -> str(currencyMessage)
            RequestCapGuard.exceeds(
                state.capVerdict(currency),
                draft.limitValue,
                currency,
                state.cardsArea.reference,
            ) -> capError(state.capVerdict(currency), state.cardsArea.reference)

            draft.justification.isBlank() -> str(S.desktop_ce_cards_err_justification)
            else -> null
        }

    /**
     * A receipt row opens the receipt. Until the receipt detail is rebuilt,
     * an accountant gets the read-only process view and a cardholder the
     * stored document.
     */
    private fun openReceipt(receiptId: String) {
        val state = vm.current
        val receipt = state.cardDetail?.receipts?.firstOrNull { it.id == receiptId } ?: return
        if (state.viewer.isAccountant) {
            vm.update { copy(receipts = receipts.filterNot { it.id == receiptId } + receipt) }
            vm.onEvent(CardEvent.OpenProcess(receiptId, ProcessMode.History))
        } else {
            receipt.attachmentKey?.takeIf { it.isNotBlank() }?.let { vm.onEvent(CardEvent.ViewReceipt(it)) }
        }
    }

    private fun refuse() = vm.fail(str(S.desktop_po_no_rights_on_project))

    private companion object {
        /** What the web records when an accountant overrides without saying why. */
        const val OVERRIDE_REASON = "Overridden by accountant"
        const val APPROVERS_PATH = "/film-tools/account-hub/approvers?module=card_expenses"
    }
}

/**
 * Sends one card write, toasting the server's own message — or [fallback]
 * where it sent none — and re-reading the page. [settle] runs on success,
 * before the re-read, to close whichever dialog raised it.
 */
internal fun CardExpensesViewModel.cardWrite(
    action: CardAction,
    fallback: String,
    settle: CardUiState.() -> CardUiState = { this },
) = run {
    update { copy(busy = true, cardsArea = cardsArea.copy(actionCardId = action.cardId)) }
    val result = repo.cardAction(action)
    update { copy(busy = false, cardsArea = cardsArea.copy(actionCardId = null)) }
    when (result) {
        is ZillitResult.Success -> {
            update { settle().copy(notice = result.data.text(fallback)) }
            reload()
        }

        is ZillitResult.Failure -> fail(result.error.localised())
    }
}

/** The server's message, translated and filled in; [fallback] where it sent none. */
internal fun CardServerNote.text(fallback: String): String =
    key?.let { ZillitError.Http(status = HTTP_OK, serverMessage = it, messageElements = elements).localised() }
        ?.takeIf { it.isNotBlank() }
        ?: fallback

private const val HTTP_OK = 200
