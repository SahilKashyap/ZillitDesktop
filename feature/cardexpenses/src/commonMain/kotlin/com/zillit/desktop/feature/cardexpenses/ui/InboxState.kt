package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.feature.cardexpenses.domain.CardCurrencies
import com.zillit.desktop.feature.cardexpenses.domain.CardDepartment
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CrewSubmission
import com.zillit.desktop.feature.cardexpenses.domain.InboxSection
import com.zillit.desktop.feature.cardexpenses.domain.MatchCandidate
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetail
import com.zillit.desktop.feature.cardexpenses.domain.StatementCurrencyOptions
import com.zillit.desktop.feature.cardexpenses.domain.StatementFile
import com.zillit.desktop.feature.cardexpenses.domain.StatementImportResult

/**
 * The reconciliation surfaces' state: Import Statement, Receipt Inbox, All
 * Transactions, and the receipt detail and manual match they open.
 *
 * One field on [CardUiState] rather than a dozen, so the pages other people
 * are building never see these and a merge never has to reconcile them.
 */
data class InboxState(
    /** The receipt detail open over the inbox; null when closed. */
    val detail: ReceiptDetailState? = null,
    /** The Manual Match dialog; null when closed. */
    val manualMatch: ManualMatchState? = null,
    /** The receipt whose Attach is in flight — its menu row reads "Attaching…". */
    val attachingId: String? = null,
    /** Re-run Match is in flight. */
    val rerunning: Boolean = false,
    /** The inbox sections folded shut. */
    val collapsed: Set<InboxSection> = emptySet(),
    val import: ImportState = ImportState(),
    val ledger: LedgerState = LedgerState(),
    /** Whether this build can pick and store a statement at all; see `CardInboxHost`. */
    val canPickStatements: Boolean = false,
)

/**
 * The receipt detail over the inbox (`ReceiptInboxPage.jsx:324-336, 733-746`).
 *
 * Opened on the slim row with [loading] while `/receipts/:id/detail` is in
 * flight; the answer replaces it only if this receipt is still the one open,
 * so a quick second click cannot be overwritten by the first one's reply.
 */
data class ReceiptDetailState(
    val receiptId: String,
    val detail: ReceiptDetail,
    val loading: Boolean = true,
    /** The document's bytes for the inline preview; null until fetched. */
    val media: MediaState = MediaState(),
    /** The trail, read only when History is pressed. */
    val history: HistoryState? = null,
)

/** A document being fetched for the inline preview. */
data class MediaState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    val bytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is MediaState && loading == other.loading && failed == other.failed && bytes === other.bytes

    override fun hashCode(): Int = (loading.hashCode() * HASH + failed.hashCode()) * HASH + (bytes?.size ?: 0)

    private companion object {
        const val HASH = 31
    }
}

/** A receipt's audit trail, open in the detail view. */
data class HistoryState(val loading: Boolean = true, val entries: List<CardHistoryEntry> = emptyList())

/** The Manual Match dialog (`ManualMatchModal.jsx`). */
data class ManualMatchState(
    val receipt: CardReceipt,
    val loading: Boolean = true,
    val candidates: List<MatchCandidate> = emptyList(),
    val selectedId: String? = null,
    val confirming: Boolean = false,
)

/**
 * Import Statement (`ImportStatementPage.jsx`).
 *
 * [pendingFile] is the picked-but-not-stored statement: nothing leaves the
 * machine until Import is pressed in the dialog, so the currency is stated
 * against a file already in hand.
 */
data class ImportState(
    val pendingFile: StatementFile? = null,
    val currency: String = "",
    val currencies: StatementCurrencyOptions = StatementCurrencyOptions(emptyList(), complete = true),
    /** The providers' banks and the project's currencies are still being read. */
    val currenciesLoading: Boolean = true,
    val importing: Boolean = false,
    val result: StatementImportResult? = null,
    val selected: Set<String> = emptySet(),
    val submitting: Boolean = false,
    val submission: CrewSubmission? = null,
    /** The file currency the statement was imported in — what totals render in. */
    val importedCurrency: String = "",
    val projectCurrencies: CardCurrencies = CardCurrencies(),
) {
    /** Hidden when there is nothing to choose — the server's project default is right then. */
    val showCurrency: Boolean get() = currenciesLoading || currencies.codes.isNotEmpty()

    /** Blocked while resolving, and while a choice is still owed. */
    val importBlocked: Boolean
        get() = currenciesLoading || (currencies.codes.isNotEmpty() && currency.isBlank())
}

/** All Transactions' own state (`AllTransactionsPage.jsx`). */
data class LedgerState(
    /** The departments catalogue, for the filter — never derived from the rows. */
    val departments: List<CardDepartment> = emptyList(),
    /** The production's default currency, which the Value tile is in. */
    val defaultCurrency: String? = null,
    /** The single delete's confirmation. */
    val deleteTarget: CardTransaction? = null,
    val deleting: Boolean = false,
    /** The bulk delete's confirmation is open. */
    val bulkConfirm: Boolean = false,
    val bulkDeleting: Boolean = false,
)

/** What the reconciliation surfaces can be asked to do. */
sealed interface InboxEvent : CardEvent {
    // -- inbox ---------------------------------------------------------------

    data class ToggleSection(val section: InboxSection) : InboxEvent

    /** Opens the detail over a row and reads its full detail. */
    data class OpenDetail(val receiptId: String) : InboxEvent
    data object CloseDetail : InboxEvent
    data object ShowHistory : InboxEvent
    data object HideHistory : InboxEvent

    data class Attach(val receiptId: String) : InboxEvent
    data class FlagPersonal(val receipt: CardReceipt) : InboxEvent
    data class DismissDuplicate(val receiptId: String) : InboxEvent
    data class DismissPersonal(val receiptId: String) : InboxEvent
    data object RerunMatch : InboxEvent

    data class OpenManualMatch(val receipt: CardReceipt) : InboxEvent
    data class SelectCandidate(val transactionId: String) : InboxEvent
    data object ConfirmManualMatch : InboxEvent
    data object CloseManualMatch : InboxEvent

    // -- import --------------------------------------------------------------

    data object PickStatement : InboxEvent
    data class DropStatement(val file: StatementFile) : InboxEvent
    data class ChooseCurrency(val code: String) : InboxEvent
    data object CancelImport : InboxEvent
    data object StartImport : InboxEvent
    data class ToggleImportRow(val rowId: String) : InboxEvent
    data object ToggleAllImportRows : InboxEvent
    data object ClearImportSelection : InboxEvent
    data object SubmitToCrew : InboxEvent
    data object NewImport : InboxEvent

    // -- all transactions ----------------------------------------------------

    data class ToggleTransaction(val transactionId: String) : InboxEvent

    /** The header box, over the ids on screen. */
    data class ToggleAllTransactions(val visibleIds: List<String>) : InboxEvent
    data class AskDelete(val transaction: CardTransaction?) : InboxEvent
    data object ConfirmDelete : InboxEvent

    /** Opens (or closes) the bulk confirmation. */
    data class AskBulkDelete(val open: Boolean) : InboxEvent

    /** Deletes these — the visible, deletable selection, in table order. */
    data class ConfirmBulkDelete(val transactionIds: List<String>) : InboxEvent
}
