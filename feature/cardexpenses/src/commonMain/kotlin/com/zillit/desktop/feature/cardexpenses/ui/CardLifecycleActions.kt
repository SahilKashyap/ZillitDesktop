package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardAction
import com.zillit.desktop.feature.cardexpenses.domain.CardActivation
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardExportRow
import com.zillit.desktop.feature.cardexpenses.domain.CardFiles
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat

/**
 * Activating a card and exporting the register and the ledger.
 *
 * Activation was a yes/no with an empty body; the web asks what kind of card
 * it is, its sixteen digits and — for a request that came without one — the
 * provider it is held with (`CardRegisterPage.jsx:460-506`). The exports did
 * not exist.
 */
internal class CardLifecycleActions(
    private val vm: CardExpensesViewModel,
    private val files: CardFiles?,
    private val today: () -> Long,
) {

    fun handle(event: CardEvent): Boolean {
        when (event) {
            is CardEvent.OpenActivation -> openActivation(event.cardId)
            is CardEvent.EditActivation -> vm.update {
                copy(activation = activation?.takeIf { it.cardId == event.draft.cardId }?.let { event.draft })
            }

            CardEvent.CloseActivation -> vm.update { copy(activation = null) }
            CardEvent.SubmitActivation -> submitActivation()
            is CardEvent.ExportCards -> exportCards(event.format)
            is CardEvent.ExportTransactions -> exportTransactions(event.format)
            else -> return false
        }
        return true
    }

    private fun activatable(cardId: String) = vm.current.cards.firstOrNull {
        it.id == cardId && (it.status == CardStatus.Approved || it.status == CardStatus.Override)
    }

    private fun openActivation(cardId: String) {
        val state = vm.current
        val card = activatable(cardId)
        if (!state.viewer.isAccountant || card == null) return refuse()
        val needsProvider = card.providerId.isNullOrBlank()
        vm.update {
            copy(
                activation = ActivationDraft(
                    cardId = cardId,
                    needsProvider = needsProvider,
                    // Empty, as the web opens it (`CardRegisterPage.jsx:755`):
                    // the provider a request is activated on is a choice.
                ),
            )
        }
    }

    private fun submitActivation() {
        val state = vm.current
        val draft = state.activation ?: return
        val card = activatable(draft.cardId)
        if (!state.viewer.isAccountant || card == null) return refuse()
        draft.validationError(state.providers.isNotEmpty())?.let { return vm.fail(it) }
        val provider = state.providers.firstOrNull { it.id == draft.providerId }.takeIf { draft.needsProvider }
        val activation = CardActivation(
            cardType = draft.type ?: return,
            cardNumber = draft.digits,
            providerId = provider?.id,
            bankId = provider?.bankId?.ifBlank { null },
            // The pin, in the web's order: the provider's company, then the
            // card's own, then nothing — the server derives one from the bank.
            companyId = provider?.companyId?.ifBlank { null } ?: card.companyId?.ifBlank { null },
        )
        vm.cardWrite(CardAction.Activate(card.id, activation), str(S.ah_card_activated_toast)) {
            copy(activation = null)
        }
    }

    /**
     * The register as the server prints it, names resolved here: the rows are
     * the cards on screen, and the server knows only ids.
     */
    private fun exportCards(format: ExportFormat) {
        val state = vm.current
        if (!state.viewer.isAccountant) return refuse()
        val rows = state.cards.map { card ->
            val holder = state.people.firstOrNull { it.id == card.holderId }
            CardExportRow(
                id = card.id,
                last4 = card.lastFour.orEmpty(),
                holder = holder?.name ?: card.holderName,
                department = holder?.department.orEmpty(),
                // The issuing bank's name, as the web prints it (`CardRegisterPage.jsx:376`).
                issuer = card.bankName.orEmpty(),
                status = card.status.wire,
                currency = card.currency.orEmpty(),
                limit = card.limit.takeIf { it > 0 } ?: card.monthlyLimit ?: 0.0,
                balance = card.balance,
            )
        }
        deliver("card-register_${stamp()}.${format.extension}") { vm.repo.exportCards(format, rows) }
    }

    private fun exportTransactions(format: ExportFormat) {
        if (!vm.current.viewer.isAccountant) return refuse()
        deliver("card-transactions_${stamp()}.${format.extension}") { vm.repo.exportTransactions(format) }
    }

    private fun stamp(): String = CardDates.toIso(today())

    /** Fetches the file, saves it and opens it, reporting either failure. */
    private fun deliver(fileName: String, fetch: suspend () -> ZillitResult<ByteArray>) {
        val target = files ?: return vm.fail(str(S.desktop_cannot_save_exports))
        vm.run {
            vm.update { copy(exporting = true) }
            val bytes = fetch()
            val saved = when (bytes) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> target.saveAndOpen(fileName, bytes.data)
            }
            vm.update { copy(exporting = false) }
            when (saved) {
                is ZillitResult.Success -> vm.update { copy(notice = str(S.desktop_exported_file, fileName)) }
                // The host's byte POST words a refusal from the server's own
                // envelope; an Unknown error would otherwise read as a shrug.
                is ZillitResult.Failure -> vm.fail(
                    (saved.error as? ZillitError.Unknown)?.technical?.takeIf { it.isNotBlank() }
                        ?: saved.error.localised(),
                )
            }
        }
    }

    private fun refuse() = vm.fail(str(S.desktop_po_no_rights_on_project))
}
