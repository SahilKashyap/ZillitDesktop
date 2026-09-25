package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardAction
import com.zillit.desktop.feature.cardexpenses.domain.CardDetailsEdit
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.NewCardRequest

/**
 * The card register's own actions: raising a card, editing one, opening one.
 *
 * Its own collaborator for the reason the account hub's `VendorActions` is —
 * the view model had grown past what one class should hold, and the register
 * is the most self-contained slice of it: a form, a drilldown that fetches
 * three lists, and a narrow correction that must not be confused with the
 * wide one.
 */
internal class CardRegisterActions(private val vm: CardExpensesViewModel) {

    /**
     * Opening a card loads what has been spent on it and how it was funded.
     *
     * Clicking a row used only to highlight it, so the register's three
     * drilldown endpoints — history, receipts and top-ups — were reachable
     * from nowhere in this application.
     */
    fun select(cardId: String?) {
        if (cardId == null) {
            vm.update { copy(selectedCardId = null, cardDetail = null) }
            return
        }
        val card = vm.current.cards.firstOrNull { it.id == cardId }
        vm.update {
            copy(
                selectedCardId = cardId,
                cardDetail = CardDetail(
                    cardId = cardId,
                    loading = true,
                    bsControlCode = card?.bsControlCode.orEmpty(),
                ),
            )
        }
        vm.run {
            val read = vm.repo.cardReceipts(cardId).getOrNull()
            val receipts = read.orEmpty()
            val topUps = vm.repo.cardTopUps(cardId).getOrNull().orEmpty()
            val history = vm.repo.cardHistory(cardId).getOrNull().orEmpty()
            // Guarded: the reader may have moved to another card while these
            // were in flight, and three lists belonging to a card nobody is
            // looking at would replace the ones they are.
            if (vm.current.selectedCardId != cardId) return@run
            vm.update {
                copy(
                    cardDetail = cardDetail?.copy(
                        loading = false,
                        receipts = receipts,
                        receiptsRead = read != null,
                        topUps = topUps,
                        history = history,
                    ),
                )
            }
        }
    }

    /**
     * Opens the new-card form (`CardRegisterPage.jsx:261-267`).
     *
     * A provider is chosen for the accountant only when exactly one exists —
     * with several, picking the first would quietly issue cards on the wrong
     * account. The currency follows the provider's bank, else the project
     * default. A cardholder asking for their own gets the crew form instead.
     */
    fun compose(holderId: String?) {
        val state = vm.current
        if (!state.viewer.isAccountant) return vm.onEvent(CardsEvent.OpenCrewRequest)
        val provider = state.providers.singleOrNull()
        vm.update {
            copy(
                newCard = NewCardDraft(
                    holderId = holderId.orEmpty(),
                    currency = bankCurrency(provider?.bankId) ?: defaultCurrency,
                    providerId = provider?.id.orEmpty(),
                    // The bank the provider binds, not its name: `card_issuer`
                    // is read as a bank id everywhere downstream.
                    issuer = provider?.bankId.orEmpty(),
                    companyId = provider?.companyId.orEmpty(),
                ),
            )
        }
    }

    /** The currency a provider's bank holds, if it names one. */
    private fun bankCurrency(bankId: String?): String? =
        vm.current.cardsArea.reference.banks.firstOrNull { it.id == bankId }?.currency?.takeIf { it.isNotBlank() }

    fun submit() {
        val state = vm.current
        val draft = state.newCard ?: return
        if (!state.viewer.isAccountant) return vm.fail(str(S.desktop_po_no_rights_on_project))
        val invalid = draft.validationError(
            providersConfigured = state.providers.isNotEmpty(),
            holderRequired = true,
            defaultCurrency = state.defaultCurrency,
        )
        if (invalid != null) {
            vm.fail(invalid)
            return
        }

        val holderId = draft.holderId
        blockingCard(holderId)?.let { blocking ->
            // The one-card rule, enforced here as well as on the server and in
            // the picker: a holder who gained a card since the form opened is
            // refused before the request is sent.
            vm.fail(str(S.desktop_card_person_already_holds_blocking, blocking.status.label.lowercase()))
            return
        }

        val person = state.people.firstOrNull { it.id == holderId }
        vm.cardWrite(
            CardAction.Request(
                NewCardRequest(
                    holderId = holderId,
                    proposedLimit = draft.limitValue,
                    currency = draft.currency.trim().ifBlank { state.defaultCurrency },
                    departmentId = person?.departmentId?.ifBlank { null },
                    companyId = draft.companyId.ifBlank { null },
                    providerId = draft.providerId.ifBlank { null },
                    issuer = draft.issuer.ifBlank { null },
                    bsControlCode = draft.bsControlCode.trim().ifBlank { null },
                    justification = draft.justification.trim().ifBlank { null },
                ),
            ),
            str(S.ah_card_requested_toast),
        ) { copy(newCard = null) }
    }

    /**
     * Opens the full details edit, refusing on a card past approval.
     *
     * The save resubmits the card and wipes its collected approvals — right on
     * a request under review, destructive on a live card — so the gate is here
     * and not only on the button that opens it. A cardholder correcting their
     * own request gets the crew form, which re-submits it to the accounts team.
     */
    fun openEdit(cardId: String) {
        val state = vm.current
        if (!state.viewer.isAccountant) return vm.onEvent(CardsEvent.OpenCrewEdit(cardId))
        val card = state.cards.firstOrNull { it.id == cardId } ?: return
        if (!CardRules.canEditRequest(card, state.viewer.userId, state.viewer.isAccountant)) {
            vm.fail(str(S.desktop_card_past_request_stage))
            return
        }
        vm.update { copy(cardEdit = CardEditDraft.of(card)) }
    }

    /** Saves and resubmits, then shows the updated card (`CardRegisterPage.jsx:593-599`). */
    fun saveEdit() {
        val state = vm.current
        val draft = state.cardEdit ?: return
        val card = state.cards.firstOrNull { it.id == draft.cardId } ?: return
        if (!CardRules.canEditRequest(card, state.viewer.userId, state.viewer.isAccountant)) {
            vm.fail(str(S.desktop_card_past_request_stage))
            return
        }
        val invalid = draft.validationError(state.providers.isNotEmpty(), state.defaultCurrency)
        if (invalid != null) {
            vm.fail(invalid)
            return
        }
        vm.cardWrite(
            CardAction.EditDetails(
                cardId = draft.cardId,
                edit = CardDetailsEdit(
                    limit = draft.limitValue,
                    balance = draft.newBalance,
                    currency = draft.currency.trim().ifBlank { state.defaultCurrency }.ifBlank { null },
                    providerId = draft.providerId,
                    issuer = draft.issuer,
                    companyId = draft.companyId,
                    bsControlCode = draft.bsControlCode,
                    justification = draft.justification,
                ),
                updatedBy = state.viewer.userId,
            ),
            str(S.desktop_card_details_saved),
        ) { copy(cardEdit = null, cardsArea = cardsArea.copy(openCardId = draft.cardId)) }
        if (state.cardsArea.openCardId != draft.cardId) select(draft.cardId)
    }

    /** The narrow correction. See the repository for why it is not the wide one. */
    fun saveBsCode(cardId: String) {
        val card = vm.current.cards.firstOrNull { it.id == cardId }
        if (card == null || !vm.current.canCorrectBsCode(card)) {
            vm.fail(str(S.desktop_po_no_rights_on_project))
            return
        }
        val code = vm.current.cardDetail?.bsControlCode?.trim().orEmpty()
        if (code.isEmpty()) {
            vm.fail(str(S.desktop_ce_cards_err_bs))
            return
        }
        vm.cardWrite(
            CardAction.BsCode(cardId, code, vm.current.viewer.userId),
            str(S.desktop_card_control_code_updated),
        )
    }

    /** Deleting a card closes the drilldown, which is about to point at nothing. */
    fun delete(cardId: String) {
        val card = vm.current.cards.firstOrNull { it.id == cardId }
        if (card == null || !CardRules.canDeleteRequest(card, vm.current.viewer.userId)) {
            vm.fail(str(S.desktop_po_no_rights_on_project))
            return
        }
        vm.cardWrite(CardAction.Delete(cardId), str(S.desktop_card_request_deleted)) {
            copy(
                selectedCardId = null,
                cardDetail = null,
                cardEdit = null,
                cardsArea = cardsArea.copy(openCardId = null),
            )
        }
    }

    private fun blockingCard(holderId: String) =
        vm.current.cards.firstOrNull { it.holderId == holderId && CardRules.blocksNewRequest(it) }
}
