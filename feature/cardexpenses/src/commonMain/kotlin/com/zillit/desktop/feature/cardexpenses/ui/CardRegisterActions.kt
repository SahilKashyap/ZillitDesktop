package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
            val receipts = vm.repo.cardReceipts(cardId).getOrNull().orEmpty()
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
                        topUps = topUps,
                        history = history,
                    ),
                )
            }
        }
    }

    /**
     * Opens the new-card form, seeded for whoever it is being raised for.
     *
     * The currency and the provider come from the production rather than being
     * left blank: every card on a production is normally held with the same
     * issuer in the same currency, and pre-filling the common case is the
     * difference between a form and an interrogation.
     */
    fun compose(holderId: String?) {
        val state = vm.current
        val provider = state.providers.firstOrNull()
        // An accountant opening this from the register is issuing a card to
        // somebody else, so it starts with nobody chosen; a cardholder can
        // only ever be asking for their own, so it starts with them.
        val seed = holderId ?: state.viewer.userId.takeUnless { state.viewer.isAccountant }
        vm.update {
            copy(
                newCard = NewCardDraft(
                    holderId = seed.orEmpty(),
                    currency = cards.firstOrNull { it.currency != null }?.currency.orEmpty(),
                    providerId = provider?.id.orEmpty(),
                    issuer = provider?.name.orEmpty(),
                ),
            )
        }
    }

    fun submit() {
        val state = vm.current
        val draft = state.newCard ?: return
        val accountant = state.viewer.isAccountant
        val invalid = draft.validationError(
            providersConfigured = state.providers.isNotEmpty(),
            holderRequired = accountant,
        )
        if (invalid != null) {
            vm.fail(invalid)
            return
        }

        val holderId = draft.holderId.ifBlank { state.viewer.userId }
        blockingCard(holderId)?.let { blocking ->
            // The one-card rule, enforced here as well as on the server and in
            // the picker: a refusal after the form is filled in teaches people
            // to ignore the rule rather than ask for the existing card to be
            // closed.
            val status = blocking.status.label.lowercase()
            vm.fail(
                if (holderId == state.viewer.userId) {
                    str(S.desktop_card_you_already_hold_blocking, status)
                } else {
                    str(S.desktop_card_person_already_holds_blocking, status)
                },
            )
            return
        }

        val person = state.people.firstOrNull { it.id == holderId }
        vm.act(str(S.ah_card_requested_toast)) {
            vm.repo.requestCard(
                NewCardRequest(
                    holderId = holderId,
                    proposedLimit = draft.limitValue,
                    currency = draft.currency.trim().ifBlank { null },
                    departmentId = person?.departmentId?.ifBlank { null },
                    companyId = draft.companyId.ifBlank { null },
                    providerId = draft.providerId.ifBlank { null },
                    issuer = draft.issuer.ifBlank { null },
                    bsControlCode = draft.bsControlCode.trim().ifBlank { null },
                    justification = draft.justification.trim().ifBlank { null },
                ),
            )
        }
        vm.update { copy(newCard = null) }
    }

    /**
     * Opens the full details edit, refusing on a card past approval.
     *
     * The save resubmits the card and wipes its collected approvals — right on
     * a request under review, destructive on a live card — so the gate is here
     * and not only on the button that opens it.
     */
    fun openEdit(cardId: String) {
        val state = vm.current
        val card = state.cards.firstOrNull { it.id == cardId } ?: return
        if (!CardRules.canEditRequest(card, state.viewer.userId, state.viewer.isAccountant)) {
            vm.fail(
                str(S.desktop_card_past_request_stage),
            )
            return
        }
        vm.update { copy(cardEdit = CardEditDraft.of(card)) }
    }

    fun saveEdit() {
        val state = vm.current
        val draft = state.cardEdit ?: return
        val invalid = draft.validationError(providersConfigured = state.providers.isNotEmpty())
        if (invalid != null) {
            vm.fail(invalid)
            return
        }
        vm.act(str(S.desktop_card_details_saved)) {
            vm.repo.updateCardDetails(
                cardId = draft.cardId,
                edit = CardDetailsEdit(
                    limit = draft.limitValue,
                    balance = draft.newBalance,
                    currency = draft.currency.trim().ifBlank { null },
                    providerId = draft.providerId,
                    issuer = draft.issuer,
                    companyId = draft.companyId,
                    bsControlCode = draft.bsControlCode,
                    justification = draft.justification,
                ),
            )
        }
        vm.update { copy(cardEdit = null) }
    }

    /** The narrow correction. See the repository for why it is not the wide one. */
    fun saveBsCode(cardId: String) {
        val code = vm.current.cardDetail?.bsControlCode?.trim().orEmpty()
        if (code.isEmpty()) {
            vm.fail(str(S.desktop_card_control_code_required))
            return
        }
        vm.act(str(S.desktop_card_control_code_updated)) { vm.repo.updateBsControlCode(cardId, code) }
    }

    /** Deleting a card closes the drilldown, which is about to point at nothing. */
    fun delete(cardId: String) {
        vm.act(str(S.desktop_card_request_deleted)) { vm.repo.deleteCard(cardId) }
        vm.update { copy(selectedCardId = null, cardDetail = null, cardEdit = null) }
    }

    private fun blockingCard(holderId: String) =
        vm.current.cards.firstOrNull { it.holderId == holderId && CardRules.blocksNewRequest(it) }
}
