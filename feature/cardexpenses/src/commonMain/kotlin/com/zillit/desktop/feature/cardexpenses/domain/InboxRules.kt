package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The badge a receipt wears where reconciliation is the more meaningful fact
 * than the workflow (`receiptReconciliation.js`).
 *
 * "Match Suggested" and "Unreconciled" share the amber on purpose: they are
 * the two states the inbox's "Action needed" banner counts, so every amber
 * chip on the page means "this one is waiting on you".
 */
enum class ReconciliationBadge(private val labelKey: String) {
    Unreconciled(S.ah_unreconciled),
    MatchSuggested(S.desktop_ce_inbox_match_suggested),
    Reconciled(S.desktop_reconciled),
    ;

    val label: String get() = str(labelKey)

    /** Waiting on the accountant: the two states the banner counts. */
    val needsAction: Boolean get() = this != Reconciled

    companion object {
        /**
         * The Receipt Inbox rule, `receiptReconciliationBadge` exactly:
         *
         *  1. unmatched (or no status) → Unreconciled, whatever the workflow says;
         *  2. a suggested match → Match Suggested, attachment or not — the
         *     machine's guess is unconfirmed, and an uploaded document does not
         *     make it true;
         *  3. matched with no document → Reconciled;
         *  4. matched with a document → null: the workflow badge is what matters.
         */
        fun inbox(matchStatus: MatchStatus, hasAttachment: Boolean): ReconciliationBadge? = when {
            matchStatus == MatchStatus.Unmatched -> Unreconciled
            matchStatus == MatchStatus.Suggested -> MatchSuggested
            !hasAttachment -> Reconciled
            else -> null
        }

        /**
         * The crew rule (`unreconciledBadge`): Unreconciled or nothing. A
         * cardholder cannot confirm a suggestion and still owes the receipt, so
         * neither "Match Suggested" nor "Reconciled" is theirs to see.
         */
        fun crew(matchStatus: MatchStatus): ReconciliationBadge? =
            if (matchStatus == MatchStatus.Unmatched) Unreconciled else null
    }
}

/** The inbox rule applied to a receipt row or detail. */
val CardReceipt.inboxBadge: ReconciliationBadge?
    get() = ReconciliationBadge.inbox(matchStatus, !attachmentKey.isNullOrBlank())

/**
 * The Receipt Inbox's status chips (`ReceiptInboxPage.jsx:96-107`) — a fixed
 * list, not the statuses that happen to be loaded, so a chip does not vanish
 * the moment its last row moves on.
 */
val INBOX_STATUS_FILTERS: List<String> = listOf(
    "all",
    "pending_receipt",
    "pending_code",
    "awaiting_approval",
    "approved",
    "queried",
    "under_review",
    "escalated",
    "posted",
    "personal",
)

/** All Transactions' status chips (`AllTransactionsPage.jsx:228-240`). */
val TRANSACTION_STATUS_FILTERS: List<String> = listOf(
    "all",
    "new",
    "pending_receipt",
    "pending_code",
    "awaiting_approval",
    "approved",
    "rejected",
    "queried",
    "under_review",
    "escalated",
    "posted",
)

/**
 * The inbox search (`ReceiptInboxPage.jsx:153-162`): the description, the
 * amount as written, and the holder's name from the directory.
 */
fun CardReceipt.matchesInboxSearch(query: String, holderName: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return description.lowercase().contains(needle) ||
        amount.plainAmount().contains(needle) ||
        holderName.lowercase().contains(needle)
}

/**
 * All Transactions' search (`AllTransactionsPage.jsx:245-252`) — merchant and
 * card last four, the two the web matches — plus the holder, code and
 * description, which cost nothing and are what the placeholder promises.
 */
fun CardTransaction.matchesLedgerSearch(query: String, holderName: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return merchant.lowercase().contains(needle) ||
        cardLastFour?.contains(needle) == true ||
        holderName.lowercase().contains(needle) ||
        nominalCode?.lowercase()?.contains(needle) == true ||
        description?.lowercase()?.contains(needle) == true ||
        amount.plainAmount().contains(needle)
}

/** `12.5` rather than `12.50` or `1.25E1` — the way the web's `String(amount)` reads. */
private fun Double.plainAmount(): String =
    if (this == kotlin.math.floor(this)) toLong().toString() else toString()

/**
 * The bulk delete's selection rules (`transactionQuery.js:55-104`).
 *
 * The stored set outlives the filters on purpose — narrowing a search and
 * clearing it gives the selection back — so what Delete *sends* is derived
 * here, from the rows on screen, never read off the set.
 */
object TransactionSelection {
    /** Deletable and carrying an id: the rows that get a checkbox. */
    fun selectableIds(rows: List<CardTransaction>): List<String> =
        rows.filter { it.id.isNotBlank() && it.status.canDelete }.map { it.id }

    /** Header box: everything on screen already ticked means clear, anything less means tick the rest. */
    fun toggleAll(selected: Set<String>, visibleIds: List<String>): Set<String> {
        val allOn = visibleIds.isNotEmpty() && visibleIds.all { it in selected }
        return if (allOn) selected - visibleIds.toSet() else selected + visibleIds
    }

    /** What Delete sends: the selection intersected with the screen, in table order. */
    fun visibleSelection(selected: Set<String>, visibleIds: List<String>): List<String> =
        visibleIds.filter { it in selected }
}

/**
 * The statement import's format check (`statementFile.js`): by extension, not
 * type — `.ofx` and `.qif` report as anything from plain text to an opaque
 * stream. Null when the file is acceptable.
 */
fun statementFileError(fileName: String): String? {
    val parts = fileName.split('.')
    val extension = if (parts.size > 1) parts.last().lowercase() else ""
    return if (extension in STATEMENT_EXTENSIONS) null else str(S.desktop_ce_inbox_only_statement_types)
}

private val STATEMENT_EXTENSIONS = setOf("csv", "ofx", "qif")

/**
 * The currencies a statement may be in, and whether that list is the whole
 * answer (`statementCurrency.js`).
 *
 * The card providers' banks first — a provider carries no currency of its
 * own, its bank does — then the project's configured currencies, then nothing
 * (the server applies the default). [complete] is false when a provider's bank
 * did not resolve, because the missing one is exactly the currency nobody
 * would think to question; only a complete list is ever auto-selected.
 */
data class StatementCurrencyOptions(val codes: List<String>, val complete: Boolean) {
    /**
     * What the picker should hold (`resolveCurrencySelection`): one known-
     * complete option is not a choice and is taken; otherwise a previous pick
     * the list still offers is kept and anything else is dropped.
     */
    fun resolve(previous: String): String = when {
        complete && codes.size == 1 -> codes.single()
        previous in codes -> previous
        else -> ""
    }

    companion object {
        fun of(
            providers: List<CardProvider>,
            banks: List<CardBank>,
            projectCodes: List<String>,
            banksFailed: Boolean = false,
        ): StatementCurrencyOptions {
            val bankIds = providers.map { it.bankId }.filter { it.isNotBlank() }
            val byId = banks.associateBy { it.id }
            val codes = bankIds.mapNotNull { byId[it]?.currency?.takeIf(String::isNotBlank) }.distinct()
            if (codes.isNotEmpty()) {
                val complete = !banksFailed && bankIds.all { !byId[it]?.currency.isNullOrBlank() }
                return StatementCurrencyOptions(codes, complete)
            }
            return StatementCurrencyOptions(projectCodes.filter { it.isNotBlank() }.distinct(), !banksFailed)
        }
    }
}
