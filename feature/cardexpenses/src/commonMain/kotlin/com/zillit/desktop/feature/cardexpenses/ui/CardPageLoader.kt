package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptScope

/**
 * What each page reads, as a change to the state.
 *
 * Its own collaborator so the view model keeps only the driving — cancel the
 * last read, mark loading, apply what came back — and this keeps the mapping
 * from a page to its routes, which is the part that changes as pages grow.
 */
internal class CardPageLoader(
    private val vm: CardExpensesViewModel,
    /** The statement page's own read, which also opens the latest import. */
    private val statements: suspend () -> ZillitResult<Reducer>,
) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table; splitting it hides the mapping.
    suspend fun fetch(destination: CardDestination): ZillitResult<Reducer> {
        val repository = vm.repo
        return when (destination) {
            CardDestination.Overview -> overview()
            CardDestination.CardRegister ->
                repository.cards(mineOnly = false).mapState { copy(cards = it) }

            CardDestination.CardsForApproval -> CrewLoads.approvals(vm)
            CardDestination.MyCards -> myCard()
            CardDestination.CardExtension -> CrewLoads.extension(vm)
            CardDestination.ImportStatement -> statements()
            CardDestination.ReceiptInbox -> inbox()
            CardDestination.MyTransactions -> myTransactions()
            CardDestination.AllTransactions -> transactions()
            CardDestination.PendingCoding, CardDestination.CodingQueue ->
                repository.receipts(ReceiptScope.PendingCoding).mapState { copy(receipts = it) }

            // The accountant's process pages read their references with
            // their rows; see readProcessPage.
            CardDestination.ApprovalQueue, CardDestination.ProcessQueue, CardDestination.BulkProcess,
            CardDestination.History,
            -> vm.readProcessPage(destination)

            CardDestination.TopUpQueue -> repository.topUps().mapState { copy(topUps = it) }
            CardDestination.Analytics -> vm.insights.analyticsPage()

            CardDestination.Alerts -> repository.alerts().mapState { copy(alerts = it) }
            CardDestination.Settings -> vm.insights.settingsPage()
        }
    }

    /**
     * The dashboard's figures, with the register behind its card rows.
     *
     * `/overview` projects each card to a thinner field set: no status, no
     * currency, no holder name, and `last4` where the register says
     * `last_four`. Rendered as-is that gave a dashboard of raw ObjectIds,
     * every status reading "Unknown", and a limits total that added yen to
     * pounds. The web hit this as ZL-20582 and reads the amounts and the code
     * from `/cards` keyed by id, which is what this does — the overview row
     * supplies *which* cards, and the register supplies what they are.
     *
     * A failed register read is not fatal: the counts and totals are the point
     * of the page, and thin card rows are better than no page.
     */
    private suspend fun overview(): ZillitResult<Reducer> {
        val repository = vm.repo
        val overview = repository.overview()
        if (overview is ZillitResult.Failure) return overview
        val dashboard = (overview as ZillitResult.Success).data
        val register = repository.cards(mineOnly = false).getOrNull().orEmpty().associateBy { it.id }
        // Limit, balance and currency from the register; `spent` only exists on
        // the overview row, and it is the one figure that counts top-ups
        // (`OverviewPage.jsx:165-171`), so it is carried across.
        val merged = dashboard.cards.map { row ->
            register[row.id]?.let { card -> card.copy(serverSpent = row.serverSpent ?: card.serverSpent) } ?: row
        }
        return ZillitResult.Success { copy(overview = dashboard.copy(cards = merged), cards = merged) }
    }

    /**
     * The cardholder's own card, plus the top-ups raised against it.
     *
     * Both, because the Card Extension screen shows the balance and the
     * request history side by side, and fetching them on separate visits made
     * a just-raised request appear to have vanished.
     */
    private suspend fun myCard(): ZillitResult<Reducer> {
        val repository = vm.repo
        val cards = repository.cards(mineOnly = true)
        if (cards is ZillitResult.Failure) return cards
        val mine = (cards as ZillitResult.Success).data
        val card = mine.firstOrNull { it.holderId == vm.current.viewer.userId } ?: mine.firstOrNull()
        val topUps = card?.let { repository.cardTopUps(it.id).getOrNull() }.orEmpty()
        return ZillitResult.Success { copy(cards = mine, topUps = topUps) }
    }

    /**
     * My Transactions needs the card as well as the receipts.
     *
     * The card carries the limit and the committed total, and without those
     * the upload gate cannot be evaluated — so the screen would offer an
     * upload it is about to refuse.
     */
    private suspend fun myTransactions(): ZillitResult<Reducer> {
        val repository = vm.repo
        val receipts = repository.receipts(ReceiptScope.Mine)
        if (receipts is ZillitResult.Failure) return receipts
        val mine = (receipts as ZillitResult.Success).data
        val cards = repository.cards(mineOnly = true).getOrNull().orEmpty()
        return ZillitResult.Success { copy(receipts = mine, cards = cards) }
    }

    /**
     * The inbox reads `/receipts` and nothing else (`ReceiptInboxPage.jsx:136-145`):
     * each row carries its linked transaction's merchant, amount, card and
     * date, so the statement side is not a second and third read.
     */
    private suspend fun inbox(): ZillitResult<Reducer> =
        vm.repo.receipts(ReceiptScope.All).mapState { copy(receipts = it) }

    /**
     * All Transactions, narrowed by the server, with the statements and cards
     * its filters pick from. Those two are optional: a filter with nothing to
     * choose is better than no ledger.
     */
    private suspend fun transactions(): ZillitResult<Reducer> {
        val repository = vm.repo
        val read = repository.transactions(vm.current.transactionFilters)
        if (read is ZillitResult.Failure) return read
        val rows = (read as ZillitResult.Success).data
        val imports = repository.imports().getOrNull()
        val cards = repository.cards(mineOnly = false).getOrNull()
        val catalogues = vm.inboxActions.ledgerCatalogues()
        return ZillitResult.Success {
            copy(transactions = rows, imports = imports ?: this.imports, cards = cards ?: this.cards).catalogues()
        }
    }

    private fun <T> ZillitResult<T>.mapState(transform: CardUiState.(T) -> CardUiState): ZillitResult<Reducer> =
        when (this) {
            is ZillitResult.Success -> ZillitResult.Success({ transform(data) })
            is ZillitResult.Failure -> this
        }
}

/** A change to the card tool's state, as a page's read produces it. */
internal typealias Reducer = CardUiState.() -> CardUiState
