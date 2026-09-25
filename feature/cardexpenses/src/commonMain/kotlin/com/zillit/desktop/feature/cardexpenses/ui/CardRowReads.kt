package com.zillit.desktop.feature.cardexpenses.ui

/**
 * Which badge a receipt read lands under, per page — the `readScope` each web
 * page hands the modal it opens. A page with none (History, All Transactions,
 * a card's own receipts) reads nothing, as on the web.
 */
internal object CardRowReads {

    /** A row's `level_1` and the action label narrowing it, or none. */
    data class Scope(val key: String, val kind: String? = null)

    /**
     * The receipt detail's read on open: Receipt Inbox and My Transactions narrow
     * to `card_receipt` (`ReceiptInboxPage.jsx:745`, `UserReceiptsPage.jsx:1059`);
     * the Approval Queue and the process editors read the whole row
     * (`ApprovalQueuePage.jsx:399`, `ProcessPage.jsx:491,997`, `BulkProcessPage.jsx:224`).
     */
    fun detail(destination: CardDestination): Scope? = when (destination) {
        CardDestination.ReceiptInbox -> Scope("receipt_inbox", CARD_RECEIPT)
        CardDestination.MyTransactions -> Scope("my_transactions", CARD_RECEIPT)
        CardDestination.ApprovalQueue -> Scope("approval_queue")
        CardDestination.ProcessQueue, CardDestination.BulkProcess -> Scope("process_queue")
        else -> null
    }

    /**
     * A query thread's read on open — `query_chat` under the same `level_1` as
     * the detail it was opened from (`ReceiptDetailModal.jsx:190-198`,
     * `ProcessReceiptModal.jsx:122-130`).
     */
    fun query(destination: CardDestination): Scope? = detail(destination)?.let { Scope(it.key, QUERY_CHAT) }

    private const val CARD_RECEIPT = "card_receipt"
    private const val QUERY_CHAT = "query_chat"
}

/** Reads [id]'s row under [scope], when the page has one. */
internal fun CardExpensesViewModel.readRow(scope: CardRowReads.Scope?, id: String) {
    scope?.let { readRow(it.key, id, it.kind) }
}
