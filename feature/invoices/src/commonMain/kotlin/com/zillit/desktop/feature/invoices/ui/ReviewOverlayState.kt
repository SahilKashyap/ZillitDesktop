package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PoSuggestions
import com.zillit.desktop.feature.invoices.domain.PurchaseOrderRecord
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
    /** User ids → designations, the second line under each name. */
    val designations: Map<String, String> = emptyMap(),
    val acting: Boolean = false,
    /** The middle pane's PO PDF — `usePoPdfPreview` for [activePo]. */
    val poPdf: AttachmentBytes? = null,
    val poPdfLoading: Boolean = false,
    /** Which order [poPdf] is, so a late answer for another one is dropped. */
    val poPdfFor: String? = null,
    /** The History panel, as the detail dialog has it. */
    val history: List<HistoryEntry>? = null,
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
) {
    /** `linked_pos` as the server sent them — never a synthetic row for a bare typed number. */
    val linkedPos: List<LinkedPo> get() = invoice.linkedPos

    /**
     * The web's `hasPO`: `linked_pos` or `po_id` (`POMatchingOverlay.jsx:201`).
     * A typed `po_number` alone is not a PO — the invoice is still sent for
     * approval (or overridden) as one without.
     */
    val hasPo: Boolean get() = invoice.hasMatchedPo

    /**
     * The order the PDF pane shows: the chosen linked one, else the first,
     * else a stub for a bare `po_id` (`POMatchingOverlay.jsx:203`).
     */
    val activePo: LinkedPo?
        get() = linkedPos.getOrNull(selectedPo) ?: linkedPos.firstOrNull()
            ?: invoice.poId.takeIf { it.isNotBlank() }?.let { LinkedPo(poId = it, poNumber = invoice.poNumber) }

    /** A held invoice is released from the queue, not overridden from here — the web's `isOnHold`. */
    val isOnHold: Boolean get() = invoice.status == com.zillit.desktop.feature.invoices.domain.InvoiceStatus.Held

    fun nameOf(userId: String): String = names[userId] ?: userId.ifBlank { str(S.desktop_unknown) }
}

/**
 * A linked purchase order open read-only — the web's `LinkedPoViewer`:
 * "Loading PO …" while `GET /purchase-orders/:id` runs, the order itself once
 * it lands, or "Couldn't load this PO's details." when it does not.
 */
data class LinkedPoView(
    val poId: String,
    val poNumber: String = "",
    val record: PurchaseOrderRecord? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** "View PDF" is rendering and fetching the order's PDF. */
    val openingPdf: Boolean = false,
    /** Who raised the order, and their designation — the "Created By" tile. */
    val creatorName: String? = null,
    val creatorDesignation: String? = null,
)

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
