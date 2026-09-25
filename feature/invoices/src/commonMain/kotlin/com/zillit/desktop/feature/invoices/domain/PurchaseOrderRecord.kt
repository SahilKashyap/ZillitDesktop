package com.zillit.desktop.feature.invoices.domain

/**
 * One purchase order as `GET /api/v2/purchase-orders/:id` answers it — what
 * the web's `LinkedPoViewer` maps (`mapApiPO`) and shows read-only in
 * `PODetailModal`, and what the linked-PO cards are enriched from
 * (`useLinkedPoSummaries`).
 *
 * Only the fields the read-only view prints; the purchase-order tool owns the
 * rest of the record.
 */
data class PurchaseOrderRecord(
    val id: String,
    val poNumber: String = "",
    val description: String = "",
    val vendorId: String = "",
    val vendorName: String = "",
    val vendorAddress: String = "",
    val status: String = "",
    val currency: String = "",
    /** `gross_total`, then `gross_amount` — the card face's figure. */
    val grossTotal: Double? = null,
    val departmentId: String = "",
    val effectiveDateMs: Long? = null,
    val deliveryDateMs: Long? = null,
    val createdBy: String = "",
    val episode: String = "",
    val notes: String = "",
    val lines: List<PoLine> = emptyList(),
) {
    /** The header's number; the web falls back to the id. */
    val label: String get() = poNumber.ifBlank { id }

    /**
     * Gross of the priced lines — the web's `computeLineTotals(lineItems).gross`:
     * split children and the reclaimable-tax line are left out, and each line's
     * own tax is added on.
     */
    val linesGross: Double
        get() = lines.filter { it.splitParentId == null }.sumOf { line ->
            val net = line.total ?: 0.0
            net + (line.taxRate?.let { net * it / PERCENT } ?: 0.0)
        }

    private companion object {
        const val PERCENT = 100.0
    }
}
