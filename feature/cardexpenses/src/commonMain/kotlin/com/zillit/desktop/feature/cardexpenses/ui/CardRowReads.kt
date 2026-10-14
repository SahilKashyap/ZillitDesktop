package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp

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

/** Top-Up To Do's `level_1` — the accountant's funding queue. */
internal const val TOPUP_TODO = "topup_todo"

/** Card Extension's `level_1` — the holder's own top-ups. */
internal const val CARD_EXTENSION = "card_extension"

/**
 * The ids a top-up's unread can be filed under on [key]'s page.
 *
 * iOS keys both pages by the top-up itself: Top-Up To Do reads `level_3 =
 * <top-up>` as its detail opens (`TopUpDetailPage.swift:141-146`), Card
 * Extension counts and reads the same (`CardExtensionTabView.swift:277, 311`).
 * The web's Top-Up To Do chips the request's entity instead — its card, else
 * its holder (`TopUpToDoPage.jsx:42-44, 354`) — so on that page both are
 * counted and both are read: whichever the service stamped, the chip shows it
 * and the read clears it.
 */
internal fun CardTopUp.badgeKeys(key: String): List<String> {
    val entity = listOf(entityId, cardId, holderId).firstOrNull { !it.isNullOrBlank() }
    return listOfNotNull(id, entity.takeIf { key == TOPUP_TODO }).filter { it.isNotBlank() }.distinct()
}

/** A top-up's unread on [key]'s page — the row's chip. */
internal fun CardUiState.topUpUnread(key: String, topUp: CardTopUp): Int =
    topUp.badgeKeys(key).sumOf { unreadRow(key, it) }

/**
 * One top-up read — iOS `readBadge(level1: .topupTodo, …)` /
 * `readTopUpBadge(topUpId:)`, scoped to [key] and the top-up, in every bucket.
 * iOS pins the bucket (`card_topup` on the queue, `card` on the extension,
 * where its skip event files), and notes a wrong one reads nothing and leaves
 * the dot; the chip here sums every bucket, so the read takes every bucket.
 */
internal fun CardExpensesViewModel.readTopUp(key: String, topUp: CardTopUp) {
    topUp.badgeKeys(key).forEach { readRow(key, it) }
}
