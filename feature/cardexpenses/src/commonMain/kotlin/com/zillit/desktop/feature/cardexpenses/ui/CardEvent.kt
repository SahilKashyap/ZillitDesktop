package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.domain.FundRequestDraft
import com.zillit.desktop.feature.cardexpenses.domain.InboxSection
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters

/** Everything the user can do in the card tool. */
sealed interface CardEvent {
    /**
     * Which door the next events come through: the Film Tools tile ([asTool])
     * or the Account Hub. Sent ahead of every event by the composition that
     * raises it; a no-op when nothing changes. See `CardExpensesToolProvider`.
     */
    data class Enter(val asTool: Boolean) : CardEvent

    data object Refresh : CardEvent
    data class Open(val destination: CardDestination) : CardEvent
    data class Search(val query: String) : CardEvent
    data class FilterStatus(val status: String) : CardEvent

    /** Shows one of the inbox's four sections; null shows them all. */
    data class FilterInboxSection(val section: InboxSection?) : CardEvent

    /** Applies All Transactions' server-side filters and reads again. */
    data class SetTransactionFilters(val filters: TransactionFilters) : CardEvent
    data class SelectReceipt(val receiptId: String?) : CardEvent
    data class SelectCard(val cardId: String?) : CardEvent
    data class SelectTransaction(val transactionId: String?) : CardEvent
    data class ToggleSelection(val id: String) : CardEvent
    data object ClearSelection : CardEvent
    data object ClearNotice : CardEvent

    data class Ask(val prompt: CardPrompt) : CardEvent
    data class UpdatePrompt(val prompt: CardPrompt) : CardEvent
    data object DismissPrompt : CardEvent
    data object ConfirmPrompt : CardEvent

    // -- uploading receipts --------------------------------------------------

    data object AddDraftReceipt : CardEvent
    data class RemoveDraftReceipt(val index: Int) : CardEvent
    data class EditDraftReceipt(val index: Int, val receipt: DraftCardReceipt) : CardEvent

    /** Opens the file picker and stores what comes back against draft row [index]. */
    data class AttachDraftReceipt(val index: Int) : CardEvent
    data class ClearDraftAttachment(val index: Int) : CardEvent
    data object SubmitDraftReceipts : CardEvent

    // -- cards ---------------------------------------------------------------

    /** Opens the new-card form. Null holder means "this viewer's own". */
    data class OpenNewCard(val holderId: String?) : CardEvent
    data class EditNewCard(val draft: NewCardDraft) : CardEvent
    data object CloseNewCard : CardEvent
    data object SubmitNewCard : CardEvent

    data class OpenCardEdit(val cardId: String) : CardEvent
    data class EditCardDraft(val draft: CardEditDraft) : CardEvent
    data object CloseCardEdit : CardEvent
    data object SaveCardEdit : CardEvent

    /** Types into the card drilldown's control-code field. */
    data class EditBsCode(val code: String) : CardEvent
    data class SaveBsCode(val cardId: String) : CardEvent

    // -- receipts ------------------------------------------------------------

    data class MatchReceipt(val receiptId: String, val transactionId: String) : CardEvent

    /** Types into the coding editor on the two coding queues. */
    data class EditCoding(val draft: CodingDraft) : CardEvent
    data object SaveCodingDraft : CardEvent
    data object SubmitCoding : CardEvent
    data object ApproveAndSubmitCoding : CardEvent

    /** Opens a receipt's stored image or PDF through the host's file layer. */
    data class ViewReceipt(val attachmentKey: String) : CardEvent

    // -- statements ----------------------------------------------------------

    /** Picks a statement file, stores it, and hands the server the pointer. */
    data object ImportStatement : CardEvent
    data class EditStatementCurrency(val currency: String) : CardEvent

    /** Opens (or closes) one top-up's trail in the funding queue. */
    data class OpenTopUpHistory(val topUpId: String?) : CardEvent

    // -- settings and analytics ----------------------------------------------

    data class EditSettings(val settings: CardSettings) : CardEvent

    /** Saves one section of the settings document; see [SettingsSection]. */
    data class SaveSettings(val section: SettingsSection) : CardEvent
    data object DiscardSettings : CardEvent
    data class SetAnalyticsRange(val range: AnalyticsRange) : CardEvent

    // -- bulk processing -----------------------------------------------------

    data class EditBulkCoding(val coding: BulkCoding) : CardEvent
    data object SelectAllBulk : CardEvent
    data object BulkPost : CardEvent

    // -- statement review ----------------------------------------------------

    data class OpenImport(val importId: String?) : CardEvent
    data object ProcessImportRows : CardEvent
    data object SubmitRowsToHolders : CardEvent

    // -- processing (the accountant's editor) --------------------------------

    /** Opens the process editor over a receipt, reading its full detail. */
    data class OpenProcess(val receiptId: String, val mode: ProcessMode = ProcessMode.Process) : CardEvent
    data object CloseProcess : CardEvent
    data class EditProcess(val draft: ProcessDraft) : CardEvent
    data class EditProcessLine(val index: Int, val line: ProcessLine) : CardEvent
    data object AddProcessLine : CardEvent
    data class RemoveProcessLine(val index: Int) : CardEvent
    data object SaveProcess : CardEvent
    data object PostProcess : CardEvent
    data object SubmitProcessForReview : CardEvent

    /** Sends the escalation typed into the open editor's dialog. */
    data object ConfirmEscalation : CardEvent

    /** Sends the assignment chosen in the open editor's dialog. */
    data object ConfirmAssign : CardEvent
    data class ShowProcessTab(val tab: ProcessTab) : CardEvent

    // -- card activation -----------------------------------------------------

    data class OpenActivation(val cardId: String) : CardEvent
    data class EditActivation(val draft: ActivationDraft) : CardEvent
    data object CloseActivation : CardEvent
    data object SubmitActivation : CardEvent

    // -- query threads and fund requests ---------------------------------------

    data class OpenQuery(val receiptId: String) : CardEvent
    data class EditQuery(val text: String) : CardEvent
    data object SendQuery : CardEvent
    data object CloseQuery : CardEvent

    data object OpenFunds : CardEvent
    data object CloseFunds : CardEvent
    data class EditFundDraft(val draft: FundRequestDraft) : CardEvent
    data object SubmitFundRequest : CardEvent
    data class ReceiveFundRequest(val requestId: String) : CardEvent
    data class CancelFundRequest(val requestId: String) : CardEvent

    // -- exports -------------------------------------------------------------

    data class ExportCards(val format: ExportFormat) : CardEvent
    data class ExportTransactions(val format: ExportFormat) : CardEvent
}

sealed interface CardEffect {
    data class Failed(val message: String) : CardEffect
    data class OpenAttachment(val key: String) : CardEffect
}
