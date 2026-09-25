package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.invoices.domain.InvoiceLabels
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter

/**
 * An invoice's query thread — the hub's `QueryPanel` over
 * `/account-hub/queries`: read by entity, opened by the first message,
 * added to after that, and re-read in the background while it is open
 * whenever someone else opens or answers it (`ah:query:entity:invoice:<id>`).
 */
internal class InvoiceQueryActions(private val vm: InvoicesViewModel) {

    /** The open thread's live re-read; one at a time, stopped when the panel closes. */
    private var live: Job? = null

    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is QueryEvent.Open -> open(event.invoice.id, event.invoice.displayNumber)
            is QueryEvent.Draft -> vm.update { copy(query = query?.copy(draft = event.text)) }
            QueryEvent.Send -> send()
            QueryEvent.Close -> {
                vm.update { copy(query = query?.takeIf { it.sending }) }
                if (vm.state.value.query == null) stopListening()
            }
            else -> return false
        }
        return true
    }

    private fun open(invoiceId: String, subtitle: String) {
        vm.update { copy(query = QueryView(invoiceId = invoiceId, subtitle = subtitle, roles = roles())) }
        load(invoiceId, silent = false)
        listen(invoiceId)
    }

    /** Reads the thread; a [silent] re-read keeps what is on screen until the answer lands. */
    private fun load(invoiceId: String, silent: Boolean) {
        vm.run {
            val result = vm.repo.queryThread(invoiceId)
            (result as? ZillitResult.Success)?.data?.let { thread -> vm.rememberNames(thread.messages.map { it.by }) }
            vm.update {
                val open = query?.takeIf { it.invoiceId == invoiceId } ?: return@update this
                when (result) {
                    is ZillitResult.Success -> copy(query = open.copy(thread = result.data, loading = false))
                    // No thread yet reads as an empty one; the first message opens it.
                    is ZillitResult.Failure -> if (silent) this else copy(query = open.copy(loading = false))
                }
            }
        }
    }

    /** Another participant's message on this invoice re-reads the thread without a spinner. */
    private fun listen(invoiceId: String) {
        stopListening()
        live = vm.run {
            vm.repo.queryUpdates.filter { it == invoiceId }.collect {
                if (vm.state.value.query?.invoiceId == invoiceId) load(invoiceId, silent = true)
            }
        }
    }

    private fun stopListening() {
        live?.cancel()
        live = null
    }

    /** Who's who — the designation the web prints beside an author's name. */
    private fun roles(): Map<String, String> =
        vm.people().filter { it.role.isNotBlank() }.associate { it.id to InvoiceLabels.format(it.role) }

    /** The first message opens the thread; later ones are added to it. What was typed survives a refusal. */
    private fun send() {
        val view = vm.state.value.query ?: return
        val text = view.draft.trim()
        if (text.isEmpty() || view.sending) return
        vm.update { copy(query = query?.copy(sending = true)) }
        vm.run {
            val result = if (view.thread.id.isBlank()) {
                vm.repo.openQuery(view.invoiceId, text)
            } else {
                vm.repo.addQuery(view.thread.id, text)
            }
            // The web shows the record the write answers with; an answer
            // without one is read back rather than shown as an empty thread.
            val thread = (result as? ZillitResult.Success)?.let { landed ->
                landed.data.takeIf { it.id.isNotBlank() }
                    ?: (vm.repo.queryThread(view.invoiceId) as? ZillitResult.Success)?.data
            }
            vm.update {
                val open = query?.takeIf { it.invoiceId == view.invoiceId } ?: return@update this
                when (result) {
                    is ZillitResult.Success -> copy(
                        query = open.copy(thread = thread ?: open.thread, draft = "", sending = false),
                    )
                    is ZillitResult.Failure -> copy(
                        query = open.copy(sending = false),
                        error = result.error.localised(),
                    )
                }
            }
        }
    }
}
