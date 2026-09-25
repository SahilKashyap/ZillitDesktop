package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The pre-approval queue's overlays — the web's `POMatchingOverlay`, its
 * `POSuggestionDropdown`, and the `LinkedPoViewer` both the overlay and the
 * detail dialog open.
 *
 * The review is where a matched invoice is judged: the document beside the
 * order's own PDF, and one decision at the foot of it. The picker is the step
 * before, on a row with no order yet: the server's suggestions, and the link
 * when one is chosen.
 */
internal class InvoiceReviewActions(private val vm: InvoicesViewModel) {

    /** Orders being read for their card faces, so two opens do not read one twice (`inflight`). */
    private val summarising = mutableSetOf<String>()

    /** True when [event] was one of these overlays'. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is InvoicesEvent.OpenReview -> open(event.invoice)
            InvoicesEvent.CloseReview -> vm.update { copy(review = null) }
            is InvoicesEvent.SelectReviewPo -> selectPo(event.index)
            InvoicesEvent.ReviewSendToApproval -> sendToApproval()
            InvoicesEvent.ReviewOverride ->
                decide(str(S.ah_override_approved_toast)) { vm.repo.overrideWithMessage(it) }
            InvoicesEvent.ReviewShowHistory -> showHistory()
            InvoicesEvent.ReviewHideHistory -> vm.update { copy(review = review?.copy(historyOpen = false)) }

            is InvoicesEvent.OpenLinkedPo -> openLinkedPo(event.poId, event.poNumber, event.index)
            InvoicesEvent.CloseLinkedPo -> vm.update { copy(linkedPo = null) }
            InvoicesEvent.OpenLinkedPoPdf -> openLinkedPoPdf()

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
     * linked orders, its hold note, who raised it — so the overlay covers
     * itself with "Loading invoice details..." until the read lands. A failed
     * read falls back to the row, silently, as the web's `.catch` does
     * (`POMatchingOverlay.jsx:116-127`).
     *
     * Opened from Pre-approval — a row, a held row or Review — it reads the
     * invoice under `invoice_matching` first (`markMatchingRead`,
     * `MatchingPage.jsx:371-377, 682, 709, 724`); the Register opens the same
     * overlay for its matching rows and reads nothing (`RegisterPage.jsx:597`).
     */
    private fun open(invoice: Invoice) {
        vm.readPageRow(AccountantPage.Matching, invoice.id)
        vm.update {
            copy(
                review = ReviewOverlay(
                    invoice = invoice,
                    names = vm.namesFor(invoice),
                    designations = vm.designationsFor(invoice),
                ),
            )
        }
        vm.run {
            val record = (vm.repo.invoice(invoice.id) as? ZillitResult.Success)?.data ?: invoice
            vm.update {
                val open = review?.takeIf { it.invoice.id == invoice.id } ?: return@update this
                copy(
                    review = open.copy(
                        invoice = record,
                        loading = false,
                        names = open.names + vm.namesFor(record),
                        designations = open.designations + vm.designationsFor(record),
                    ),
                )
            }
            loadPreview(record)
            loadPoPdf(record.id)
            summarise(record.linkedPos.map { it.poId })
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

    private fun selectPo(index: Int) {
        val open = vm.currentState.review ?: return
        if (index == open.selectedPo) return
        vm.update { copy(review = review?.copy(selectedPo = index)) }
        loadPoPdf(open.invoice.id)
    }

    /**
     * The middle pane's PDF for the order on show — rendered by the
     * purchase-order service, then fetched like any stored file (the web's
     * `usePoPdfPreview`). A late answer for an order no longer on show is
     * dropped.
     */
    private fun loadPoPdf(invoiceId: String) {
        val po: LinkedPo? = vm.currentState.review?.takeIf { it.invoice.id == invoiceId }?.activePo
        vm.update { copy(review = review?.copy(poPdf = null, poPdfLoading = po != null, poPdfFor = po?.poId)) }
        if (po == null) return
        vm.run {
            val bytes = vm.repo.purchaseOrderPdf(po.poId).flatMap { vm.fetchAttachment(it) }
            vm.update {
                val open = review?.takeIf { it.invoice.id == invoiceId && it.poPdfFor == po.poId }
                    ?: return@update this
                copy(
                    review = open.copy(
                        poPdf = (bytes as? ZillitResult.Success)?.data?.let(::AttachmentBytes),
                        poPdfLoading = false,
                    ),
                )
            }
        }
    }

    /**
     * Reads each linked order once for its card face — number, vendor,
     * description and gross in its own currency (`useLinkedPoSummaries`).
     * A failed read leaves the card on what the link row carries.
     */
    fun summarise(poIds: List<String>) {
        val wanted = poIds.filter { it.isNotBlank() && it !in vm.currentState.poSummaries && it !in summarising }
            .distinct()
        wanted.forEach { id ->
            summarising += id
            vm.run {
                val result = vm.repo.purchaseOrder(id)
                summarising -= id
                if (result is ZillitResult.Success) {
                    vm.update { copy(poSummaries = poSummaries + (id to result.data)) }
                }
            }
        }
    }

    /**
     * The currency check comes first, as the web's `handleConfirmApproval`
     * makes it: no currency, no call (`POMatchingOverlay.jsx:253-258`). The
     * project's currency does not stand in.
     */
    private fun sendToApproval() {
        val open = vm.currentState.review ?: return
        if (open.invoice.currency.isBlank()) {
            vm.notice(str(S.ah_err_currency_required))
            return
        }
        decide(str(S.ah_sent_for_approval_toast)) { vm.repo.sendToApprovalWithMessage(it) }
    }

    /**
     * Sends the invoice on, or past, the approval chain; the overlay closes
     * when the server takes it, and the toast is the server's own word when
     * it sent one (`showApiSuccess`).
     */
    private fun decide(fallback: String, action: suspend (String) -> ZillitResult<String?>) {
        val open = vm.currentState.review ?: return
        if (open.acting) return
        vm.update { copy(review = review?.copy(acting = true)) }
        vm.run {
            when (val result = action(open.invoice.id)) {
                is ZillitResult.Success -> {
                    vm.update { copy(review = null) }
                    vm.notice(result.data?.takeIf { it.isNotBlank() }?.localisedMessage() ?: fallback)
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.update {
                    copy(review = review?.copy(acting = false), error = result.error.localised())
                }
            }
        }
    }

    private fun showHistory() {
        val open = vm.currentState.review ?: return
        vm.update { copy(review = review?.copy(historyOpen = true, historyLoading = open.history == null)) }
        if (open.history != null) return
        vm.run {
            val result = vm.repo.history(open.invoice.id)
            if (result is ZillitResult.Success) vm.rememberNames(result.data.map { it.actionBy })
            vm.update {
                val current = review?.takeIf { it.invoice.id == open.invoice.id } ?: return@update this
                when (result) {
                    is ZillitResult.Success ->
                        copy(review = current.copy(history = result.data, historyLoading = false))
                    is ZillitResult.Failure ->
                        copy(review = current.copy(historyLoading = false), error = result.error.localised())
                }
            }
        }
    }

    // -- the linked-PO viewer ------------------------------------------------

    /**
     * A linked-PO card: in the review it also switches the PDF pane to that
     * order; either way the order opens read-only over everything. The read
     * is shared with the card faces, so an order already read opens at once.
     */
    private fun openLinkedPo(poId: String, poNumber: String, index: Int?) {
        if (index != null) selectPo(index)
        val cached = vm.currentState.poSummaries[poId]
        vm.update {
            copy(linkedPo = LinkedPoView(poId = poId, poNumber = poNumber, record = cached, loading = cached == null))
        }
        if (cached != null) {
            val (name, designation) = vm.personOf(cached.createdBy)
            vm.update { copy(linkedPo = linkedPo?.copy(creatorName = name, creatorDesignation = designation)) }
            return
        }
        vm.run {
            val result = vm.repo.purchaseOrder(poId)
            val (name, designation) = (result as? ZillitResult.Success)?.data?.let { vm.personOf(it.createdBy) }
                ?: (null to null)
            vm.update {
                val view = linkedPo?.takeIf { it.poId == poId }
                when (result) {
                    is ZillitResult.Success -> copy(
                        poSummaries = poSummaries + (poId to result.data),
                        linkedPo = view?.copy(
                            record = result.data,
                            loading = false,
                            creatorName = name,
                            creatorDesignation = designation,
                        ),
                    )
                    is ZillitResult.Failure -> copy(linkedPo = view?.copy(loading = false, failed = true))
                }
            }
        }
    }

    /** "View PDF" — the order's rendered PDF, saved and opened as the detail's attachment is. */
    private fun openLinkedPoPdf() {
        val view = vm.currentState.linkedPo ?: return
        if (view.openingPdf) return
        val label = view.record?.label ?: view.poNumber.ifBlank { view.poId }
        vm.update { copy(linkedPo = linkedPo?.copy(openingPdf = true)) }
        vm.run {
            val outcome = vm.repo.purchaseOrderPdf(view.poId)
                .flatMap { attachment ->
                    vm.fetchAttachment(attachment).flatMap { bytes ->
                        vm.saveAndOpen(attachment.name.ifBlank { "$label.pdf" }, bytes)
                    }
                }
            vm.update {
                copy(
                    linkedPo = linkedPo?.copy(openingPdf = false),
                    error = (outcome as? ZillitResult.Failure)?.error?.localised() ?: error,
                )
            }
        }
    }

    // -- the picker ----------------------------------------------------------

    private fun openPicker(invoice: Invoice) {
        vm.update { copy(poPicker = PoPicker(invoice = invoice)) }
        vm.run {
            // No vendor filter: the web's `poSuggestions(id)` sends none.
            val result = vm.repo.poSuggestions(invoice.id, null)
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
        if (picker.invoice.currency.isBlank()) {
            vm.fail(str(S.ah_err_currency_required))
            return
        }
        vm.update { copy(poPicker = poPicker?.copy(matching = suggestion.poId)) }
        vm.run {
            when (val result = vm.repo.match(picker.invoice.id, suggestion)) {
                is ZillitResult.Success -> {
                    vm.update { copy(poPicker = null) }
                    vm.notice(str(S.desktop_inv_matched_to, suggestion.label))
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.update {
                    copy(poPicker = poPicker?.copy(matching = null), error = result.error.localised())
                }
            }
        }
    }
}
