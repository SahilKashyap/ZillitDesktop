package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cardexpenses.domain.CrewRules

/**
 * What the crew Approval Queue and Card Extension read. Called from
 * [CardPageLoader]'s dispatch.
 */
internal object CrewLoads {

    /**
     * The crew Approval Queue: card requests this viewer signs next, and the
     * receipts in their approval queue (`CardsForApprovalPage.jsx:85-97`).
     *
     * Neither read is fatal, as on the web — a failed one keeps what was
     * there. An open card request that has left the queue (approved, or acted
     * on by somebody else) closes back to the list: "close when gone".
     */
    suspend fun approvals(vm: CardExpensesViewModel): ZillitResult<Reducer> {
        val cards = vm.repo.cardsForApproval().getOrNull()
        val receipts = vm.repo.approvalQueue().getOrNull()
        return ZillitResult.Success {
            val queued = cards ?: crew.approvalCards
            copy(
                crew = crew.copy(
                    approvalCards = queued,
                    approvalReceipts = receipts ?: crew.approvalReceipts,
                    approvalCardId = crew.approvalCardId?.takeIf { id -> queued.any { it.id == id } },
                ),
            )
        }
    }

    /**
     * Card Extension: this viewer's cards, and every top-up raised against the
     * chosen active one, newest first (`CardExtensionPage.jsx`,
     * `TopUpExtensionPanel.jsx:84-104`).
     *
     * A failed top-up read is the panel's own state — "Couldn't load top-ups.
     * Retry" — not the page's.
     */
    suspend fun extension(vm: CardExpensesViewModel): ZillitResult<Reducer> {
        val read = vm.repo.cards(mineOnly = true)
        if (read is ZillitResult.Failure) return read
        val mine = (read as ZillitResult.Success).data
        val toppable = CrewRules.toppableCards(mine)
        val chosen = toppable.firstOrNull { it.id == vm.current.crew.topUpCardId } ?: toppable.firstOrNull()
        val topUps = chosen?.let { vm.repo.cardTopUps(it.id) }
        return ZillitResult.Success {
            copy(
                cards = mine,
                topUps = topUps?.getOrNull().orEmpty(),
                crew = crew.copy(topUpCardId = chosen?.id, topUpsFailed = topUps is ZillitResult.Failure),
            )
        }
    }
}
