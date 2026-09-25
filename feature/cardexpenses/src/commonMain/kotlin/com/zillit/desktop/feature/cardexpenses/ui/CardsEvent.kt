package com.zillit.desktop.feature.cardexpenses.ui

/**
 * What the register, the full-page card detail and the cardholder's Card tab
 * can do. Handled by [CardsActions].
 */
sealed interface CardsEvent : CardEvent {

    /** Opens a card full-page in place of the grid; null goes back to the grid. */
    data class OpenCard(val cardId: String?) : CardsEvent
    data class ShowHistory(val open: Boolean) : CardsEvent

    /** Hides (or restores) the cardholder header and tabs; see `CardUiState.fullScreen`. */
    data class SetFullScreen(val on: Boolean) : CardsEvent

    // -- the inline control-code edit ------------------------------------------

    data object StartBsEdit : CardsEvent
    data class EditBsDraft(val code: String) : CardsEvent
    data object CancelBsEdit : CardsEvent
    data object SaveBsEdit : CardsEvent

    // -- the lifecycle, acted on at once (`CardRegisterPage.jsx:390, 838-866`) --

    data class Approve(val cardId: String) : CardsEvent
    data class Suspend(val cardId: String) : CardsEvent
    data class Reactivate(val cardId: String) : CardsEvent

    // -- the action dialogs ---------------------------------------------------

    data class AskReject(val cardId: String) : CardsEvent
    data class EditRejectReason(val reason: String) : CardsEvent
    data object ConfirmReject : CardsEvent

    data class AskOverride(val cardId: String) : CardsEvent
    data class EditOverrideReason(val reason: String) : CardsEvent
    data object ConfirmOverride : CardsEvent

    data class AskAssignPhysical(val cardId: String) : CardsEvent
    data class EditPhysicalNumber(val typed: String) : CardsEvent
    data object ConfirmAssignPhysical : CardsEvent

    data class AskDelete(val cardId: String) : CardsEvent
    data object ConfirmDelete : CardsEvent

    /** Closes whichever action dialog is open. */
    data object CloseDialog : CardsEvent

    // -- the cardholder's own request ------------------------------------------

    data object OpenCrewRequest : CardsEvent
    data class EditCrewRequest(val draft: CrewCardDraft) : CardsEvent
    data object SubmitCrewRequest : CardsEvent

    data class OpenCrewEdit(val cardId: String) : CardsEvent
    data class EditCrewEdit(val draft: CrewCardDraft) : CardsEvent
    data object SubmitCrewEdit : CardsEvent

    // -- leaving the register ---------------------------------------------------

    /** The detail's "Set Approval Level →": the hub's Approvers, on the card chain. */
    data object OpenApprovers : CardsEvent

    /** A receipt row on the detail page. */
    data class OpenReceipt(val receiptId: String) : CardsEvent
}
