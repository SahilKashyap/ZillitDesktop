package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.PoSuggestion

/**
 * The pre-approval queue's two overlays — the web's `POMatchingOverlay` and
 * its `POSuggestionDropdown`.
 *
 * The review is where a matched invoice is judged: the document beside the
 * order it is being checked against, and one decision at the foot of it. The
 * picker is the step before, on a row with no order yet: the server's
 * suggestions, and the link when one is chosen.
 */
internal class InvoiceReviewActions(private val vm: InvoicesViewModel) {

    /** True when [event] was one of these two overlays'. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is InvoicesEvent.OpenReview -> open(event.invoice)
            InvoicesEvent.CloseReview -> vm.update { copy(review = null) }
            is InvoicesEvent.SelectReviewPo -> vm.update { copy(review = review?.copy(selectedPo = event.index)) }
            InvoicesEvent.ReviewSendToApproval -> decide("Sent for approval") { vm.repo.sendToApproval(it) }
            InvoicesEvent.ReviewOverride -> decide("Override approved") { vm.repo.override(it) }

            is InvoicesEvent.OpenPoSuggestions -> openPicker(event.invoice)
            is InvoicesEvent.MatchToPo -> match(event.suggestion)
            InvoicesEvent.ClosePoSuggestions -> vm.update { copy(poPicker = null) }
            else -> return false
        }
        return true
    }

    /**
     * Opens the review over the row, then re-reads the invoice.
     *
     * The list carries a summary; the decision needs the whole record — its
     * linked orders, its hold note, who raised it — so the overlay opens on
     * what is known and fills in, as the web's does.
     */
    private fun open(invoice: Invoice) {
        vm.update { copy(review = ReviewOverlay(invoice = invoice, names = vm.namesFor(invoice))) }
        vm.run {
            when (val result = vm.repo.invoice(invoice.id)) {
                is ZillitResult.Failure -> vm.update {
                    copy(review = review?.copy(loading = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    vm.update {
                        val open = review?.takeIf { it.invoice.id == invoice.id } ?: return@update this
                        copy(
                            review = open.copy(
                                invoice = result.data,
                                loading = false,
                                names = open.names + vm.namesFor(result.data),
                            ),
                        )
                    }
                    loadPreview(result.data)
                }
            }
        }
    }

    private fun loadPreview(invoice: Invoice) {
        val attachment = invoice.firstAttachment?.takeIf { it.isImage || it.isPdf } ?: return
        vm.update { copy(review = review?.copy(previewLoading = true)) }
        vm.run {
            val bytes = vm.fetchAttachment(attachment)
            vm.update {
                val open = review?.takeIf { it.invoice.id == invoice.id } ?: return@update this
                copy(
                    review = when (bytes) {
                        is ZillitResult.Success ->
                            open.copy(preview = AttachmentBytes(bytes.data), previewLoading = false)
                        is ZillitResult.Failure -> open.copy(previewLoading = false, previewFailed = true)
                    },
                )
            }
        }
    }

    /** Sends the invoice on, or past, the approval chain; the overlay closes when the server takes it. */
    private fun decide(done: String, action: suspend (String) -> ZillitResult<Unit>) {
        val open = vm.currentState.review ?: return
        if (open.acting) return
        vm.update { copy(review = review?.copy(acting = true)) }
        vm.run {
            when (val result = action(open.invoice.id)) {
                is ZillitResult.Success -> {
                    vm.update { copy(review = null) }
                    vm.notice(done)
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.update {
                    copy(review = review?.copy(acting = false), error = result.error.localised())
                }
            }
        }
    }

    // -- the picker ----------------------------------------------------------

    private fun openPicker(invoice: Invoice) {
        vm.update { copy(poPicker = PoPicker(invoice = invoice)) }
        vm.run {
            val result = vm.repo.poSuggestions(invoice.id, invoice.vendorId)
            vm.update {
                val picker = poPicker?.takeIf { it.invoice.id == invoice.id } ?: return@update this
                copy(
                    poPicker = picker.copy(
                        loading = false,
                        suggestions = (result as? ZillitResult.Success)?.data ?: picker.suggestions,
                    ),
                )
            }
        }
    }

    /**
     * Links the order and reloads the queue.
     *
     * The currency check is the web's: an invoice with no currency cannot be
     * matched, because the figures on either side would not be comparable.
     */
    private fun match(suggestion: PoSuggestion) {
        val picker = vm.currentState.poPicker ?: return
        if (picker.matching != null) return
        if (picker.invoice.currency.isBlank() && vm.currentState.projectCurrency.isBlank()) {
            vm.fail("Currency is required")
            return
        }
        vm.update { copy(poPicker = poPicker?.copy(matching = suggestion.poId)) }
        vm.run {
            when (val result = vm.repo.match(picker.invoice.id, suggestion)) {
                is ZillitResult.Success -> {
                    vm.update { copy(poPicker = null) }
                    vm.notice("Matched to ${suggestion.label}")
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.update {
                    copy(poPicker = poPicker?.copy(matching = null), error = result.error.localised())
                }
            }
        }
    }
}
