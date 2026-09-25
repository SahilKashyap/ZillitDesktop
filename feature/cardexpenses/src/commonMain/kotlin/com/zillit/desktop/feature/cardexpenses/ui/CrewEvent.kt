package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus

/** What a cardholder can do on the crew pages; handled by `CardCrewActions`. */
sealed interface CrewEvent : CardEvent {
    /** A crew page is on screen: read the host's companies, codes and TV flag once. */
    data object Prime : CrewEvent

    // -- My Transactions -----------------------------------------------------

    data class Filter(val status: CardWorkflowStatus?) : CrewEvent

    /** A receipt card was clicked: rejected rows open the edit, the rest the detail view. */
    data class OpenReceipt(val receiptId: String) : CrewEvent
    data object CloseDetail : CrewEvent
    data object ToggleDetailHistory : CrewEvent

    /** Edit Receipt, from a card's Upload Receipt button or the detail view. */
    data class OpenEdit(val receiptId: String) : CrewEvent
    data class EditReceipt(val draft: ReceiptEditDraft) : CrewEvent
    data object PickEditFile : CrewEvent
    data object RemoveEditFile : CrewEvent
    data object CloseEdit : CrewEvent
    data object SaveEdit : CrewEvent

    data class AskDelete(val receiptId: String?) : CrewEvent
    data object ConfirmDelete : CrewEvent

    /** The full-screen Upload Receipts page. */
    data object OpenUpload : CrewEvent
    data object CloseUpload : CrewEvent
    data object SubmitUpload : CrewEvent

    // -- Card Extension --------------------------------------------------------

    data class SelectTopUpCard(val cardId: String) : CrewEvent
    data class OpenTopUpRequest(val open: Boolean) : CrewEvent
    data class EditTopUpRequest(val draft: TopUpRequestDraft) : CrewEvent
    data object SendTopUpRequest : CrewEvent
    data class OpenTopUpTrail(val topUpId: String?) : CrewEvent

    // -- Approval Queue ----------------------------------------------------------

    data class ShowApprovalTab(val tab: CrewApprovalTab) : CrewEvent
    data class OpenApprovalCard(val cardId: String?) : CrewEvent
    data class ApproveCard(val cardId: String) : CrewEvent
    data class OpenApprovalReceipt(val receiptId: String) : CrewEvent
    data class ApproveReceipt(val receiptId: String) : CrewEvent

    /** Opens the reason dialog over a card ([receipt] false) or a receipt. */
    data class AskReject(val targetId: String, val receipt: Boolean) : CrewEvent
    data class EditReject(val reason: String) : CrewEvent
    data object CloseReject : CrewEvent
    data object ConfirmReject : CrewEvent

    // -- Coding Queue --------------------------------------------------------------

    data class OpenCode(val receiptId: String?) : CrewEvent
    data class EditCode(val draft: CodeReceiptDraft) : CrewEvent
    data object SaveCodeDraft : CrewEvent
    data object SubmitCode : CrewEvent
    data object ApproveAndSubmitCode : CrewEvent
}
