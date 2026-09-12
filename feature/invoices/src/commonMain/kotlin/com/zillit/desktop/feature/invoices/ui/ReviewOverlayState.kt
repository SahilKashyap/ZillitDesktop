package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PoSuggestions

/**
 * The side-by-side review a pre-approval row opens — the web's
 * `POMatchingOverlay`.
 *
 * The invoice document on the left, the order it is being checked against in
 * the middle, and the invoice's own figures on the right with the decision
 * underneath. The row that opened it is only the start: the overlay re-reads
 * the invoice, because the list carries a summary and the decision needs the
 * whole record.
 */
data class ReviewOverlay(
    val invoice: Invoice,
    val loading: Boolean = true,
    /** The invoice's own attachment, fetched as the detail dialog fetches it. */
    val preview: AttachmentBytes? = null,
    val previewLoading: Boolean = false,
    val previewFailed: Boolean = false,
    /** Which linked order the middle pane is showing. */
    val selectedPo: Int = 0,
    /** User ids → names, for the created-by and updated-by lines. */
    val names: Map<String, String> = emptyMap(),
    val acting: Boolean = false,
) {
    val linkedPos: List<LinkedPo>
        get() = invoice.linkedPos.ifEmpty {
            if (invoice.poId.isBlank() && invoice.poNumber.isBlank()) {
                emptyList()
            } else {
                listOf(LinkedPo(poId = invoice.poId, poNumber = invoice.poNumber))
            }
        }

    val hasPo: Boolean get() = linkedPos.isNotEmpty()

    val activePo: LinkedPo? get() = linkedPos.getOrNull(selectedPo) ?: linkedPos.firstOrNull()

    /** A held invoice is released from the queue, not overridden from here — the web's `isOnHold`. */
    val isOnHold: Boolean get() = invoice.status == com.zillit.desktop.feature.invoices.domain.InvoiceStatus.Held

    fun nameOf(userId: String): String = names[userId] ?: userId.ifBlank { "Unknown" }
}

/**
 * The PO picker on a pre-approval row — the web's `POSuggestionDropdown`.
 *
 * Two lists: orders raised against the same vendor, and orders the reader
 * raised themselves. Picking one links it and reloads the queue.
 */
data class PoPicker(
    val invoice: Invoice,
    val loading: Boolean = true,
    val suggestions: PoSuggestions = PoSuggestions(),
    val matching: String? = null,
)
