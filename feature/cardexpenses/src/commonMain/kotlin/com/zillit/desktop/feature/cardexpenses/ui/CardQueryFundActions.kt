package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.FundRequest
import com.zillit.desktop.feature.cardexpenses.domain.FundRequestDraft
import com.zillit.desktop.feature.cardexpenses.domain.QueryThread

/** The query thread open over one receipt (`QueryPanel.jsx`). */
data class QueryDraft(
    val receiptId: String,
    val title: String,
    val thread: QueryThread = QueryThread(),
    val loading: Boolean = true,
    val text: String = "",
    val sending: Boolean = false,
)

/** The register's Fund Requests surface (`RequestFundsModal.jsx`). */
data class FundsState(
    val requests: List<FundRequest> = emptyList(),
    val loading: Boolean = true,
    val draft: FundRequestDraft = FundRequestDraft(),
    /** The request a Mark received or Cancel is in flight for ("Working…"). */
    val acting: String? = null,
)

/**
 * Query threads on receipts, and the fund requests behind the register.
 *
 * Both are accountant surfaces on the web; a receipt's own holder may also
 * answer a query on it, which is the point of asking one.
 */
internal class CardQueryFundActions(
    private val vm: CardExpensesViewModel,
    private val banks: suspend () -> List<CardBank>,
) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table.
    fun handle(event: CardEvent): Boolean {
        when (event) {
            is CardEvent.OpenQuery -> openQuery(event.receiptId)
            is CardEvent.EditQuery -> vm.update { copy(query = query?.copy(text = event.text)) }
            CardEvent.SendQuery -> sendQuery()
            CardEvent.CloseQuery -> vm.update { copy(query = null) }
            CardEvent.OpenFunds -> openFunds()
            CardEvent.CloseFunds -> vm.update { copy(funds = null) }
            is CardEvent.EditFundDraft -> vm.update { copy(funds = funds?.copy(draft = event.draft)) }
            CardEvent.SubmitFundRequest -> submitFundRequest()
            is CardEvent.ReceiveFundRequest -> settleFund(event.requestId, receive = true)
            is CardEvent.CancelFundRequest -> settleFund(event.requestId, receive = false)
            else -> return false
        }
        return true
    }

    // -- queries -------------------------------------------------------------

    /** An accountant, or the receipt's own holder answering. */
    private fun mayQuery(receiptId: String): Boolean {
        val state = vm.current
        val receipt = state.receipts.firstOrNull { it.id == receiptId }
            ?: state.process?.receipt?.takeIf { it.id == receiptId }
            ?: return false
        return state.viewer.isAccountant || (receipt.holderId != null && receipt.holderId == state.viewer.userId)
    }

    private fun openQuery(receiptId: String) {
        if (!mayQuery(receiptId)) return refuse()
        val state = vm.current
        val receipt = state.receipts.firstOrNull { it.id == receiptId } ?: state.process?.receipt
        val title = receipt?.description?.ifBlank { null } ?: str(S.desktop_receipt)
        vm.update { copy(query = QueryDraft(receiptId = receiptId, title = title)) }
        vm.readRow(CardRowReads.query(state.destination), receiptId)
        vm.run {
            val read = vm.repo.queryThread(ENTITY, receiptId)
            if (vm.current.query?.receiptId != receiptId) return@run
            when (read) {
                is ZillitResult.Success -> vm.update { copy(query = query?.copy(thread = read.data, loading = false)) }
                is ZillitResult.Failure -> {
                    vm.update { copy(query = query?.copy(loading = false)) }
                    vm.fail(read.error.localised())
                }
            }
        }
    }

    private fun sendQuery() {
        val draft = vm.current.query ?: return
        val text = draft.text.trim()
        if (text.isEmpty() || draft.sending || draft.loading) return
        if (!mayQuery(draft.receiptId)) return refuse()
        vm.update { copy(query = query?.copy(sending = true)) }
        vm.run {
            val sent = vm.repo.sendQuery(draft.thread, ENTITY, draft.receiptId, text)
            when (sent) {
                is ZillitResult.Success -> vm.update {
                    copy(query = query?.copy(thread = sent.data, text = "", sending = false))
                }

                // The typing stays, for the retry.
                is ZillitResult.Failure -> {
                    vm.update { copy(query = query?.copy(sending = false)) }
                    vm.fail(sent.error.localised())
                }
            }
        }
    }

    // -- fund requests -------------------------------------------------------

    private fun openFunds() {
        if (!vm.current.viewer.isAccountant) return refuse()
        vm.update { copy(funds = FundsState()) }
        reloadFunds()
    }

    private fun reloadFunds() = vm.run {
        val accounts = banks()
        val read = vm.repo.fundRequests()
        vm.update {
            copy(
                banks = accounts,
                funds = funds?.copy(requests = read.getOrNull().orEmpty(), loading = false),
            )
        }
        (read as? ZillitResult.Failure)?.let { vm.fail(it.error.localised()) }
    }

    private fun submitFundRequest() {
        val funds = vm.current.funds ?: return
        if (!vm.current.viewer.isAccountant) return refuse()
        if (!funds.draft.complete) return vm.fail(str(S.desktop_card_fund_request_incomplete))
        vm.run {
            vm.update { copy(busy = true) }
            when (val made = vm.repo.createFundRequest(funds.draft)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            busy = false,
                            notice = str(S.desktop_ce_funds_requested),
                            funds = this.funds?.copy(draft = FundRequestDraft()),
                        )
                    }
                    reloadFunds()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false) }
                    vm.fail(made.error.localised())
                }
            }
        }
    }

    /** Receiving posts to the ledger; either way, only an open request moves. */
    private fun settleFund(requestId: String, receive: Boolean) {
        val state = vm.current
        val open = state.funds?.requests?.any { it.id == requestId && it.open } == true
        if (!state.viewer.isAccountant || !open) return refuse()
        if (state.funds?.acting != null) return
        vm.run {
            vm.update { copy(funds = funds?.copy(acting = requestId)) }
            val done = if (receive) vm.repo.receiveFundRequest(requestId) else vm.repo.cancelFundRequest(requestId)
            vm.update { copy(funds = funds?.copy(acting = null)) }
            when (done) {
                is ZillitResult.Success -> {
                    val notice = if (receive) S.desktop_card_fund_received else S.desktop_card_fund_cancelled
                    vm.update { copy(notice = str(notice)) }
                    reloadFunds()
                }

                is ZillitResult.Failure -> vm.fail(done.error.localised())
            }
        }
    }

    private fun refuse() = vm.fail(str(S.desktop_po_no_rights_on_project))

    private companion object {
        /** The web's entity type for a card receipt's thread. */
        const val ENTITY = "card_receipt"
    }
}
