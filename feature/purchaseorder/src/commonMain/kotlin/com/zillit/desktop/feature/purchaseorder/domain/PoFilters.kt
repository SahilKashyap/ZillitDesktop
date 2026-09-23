package com.zillit.desktop.feature.purchaseorder.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The chips above every order list — the web's `FILTERS_BY_TAB`.
 *
 * Which chips a page offers is per-page and deliberately so: the approval
 * queue's endpoint never returns a posted or closed order, so offering those
 * two chips there would render controls that can never match anything. The
 * Posted tab's chips are not statuses at all but relief labels.
 */
enum class PoQuickFilter(private val labelKey: String) {
    All(S.all),
    Pending(S.pending),
    Approved(S.approved),
    Rejected(S.rejected),
    Posted(S.ah_posted_label),
    Closed(S.ah_status_closed),

    // -- the Posted tab's own chips: relief, not status --------------------
    Open(S.dd_action_open),
    FullyRelieved(S.desktop_fully_relieved),
    PartiallyRelieved(S.desktop_partially_relieved),
    Invoice(S.ah_run_detail_col_invoice),
    ;

    /** What the chip shows. */
    val label: String get() = str(labelKey)

    /**
     * Whether [order] belongs under this chip.
     *
     * `Approved` gathers the three post-approval, pre-posting statuses the
     * server spells differently (`APPROVED`, `ACCT_ENTERED`, `QUEUED`): they
     * are one situation to a reader, and the web pairs them at every site that
     * checks one.
     */
    fun matches(order: PurchaseOrder): Boolean = when (this) {
        All -> true
        Pending -> order.status == PoStatus.AwaitingApproval || order.status == PoStatus.Draft
        Approved -> order.status == PoStatus.Approved ||
            order.status == PoStatus.AccountsEntered ||
            order.status == PoStatus.Queued

        Rejected -> order.status == PoStatus.Rejected
        Posted -> order.status == PoStatus.Posted
        Closed -> order.status == PoStatus.Closed
        Open -> order.relief == PoRelief.Open
        FullyRelieved -> order.relief == PoRelief.FullyRelieved
        PartiallyRelieved -> order.relief == PoRelief.PartiallyRelieved
        // Anything an invoice has been matched against, whole or in part.
        Invoice -> order.paidAmount > 0.0
    }

    companion object {
        /** Every list's default four — the web's `QUICK_FILTERS`. */
        val DEFAULT = listOf(All, Pending, Approved, Rejected)

        /** The two tabs whose endpoint also returns posted and closed rows. */
        val WITH_HISTORY = listOf(All, Pending, Approved, Rejected, Posted, Closed)

        /** The Posted tab's relief chips. */
        val RELIEF = listOf(All, Open, FullyRelieved, PartiallyRelieved, Closed, Invoice)
    }
}

/**
 * How a list is ordered — the web's three `sortKey` options, in its order.
 *
 * Newest first by default, because a purchase-order list is read to find what
 * just came in.
 */
enum class PoSortKey(private val labelKey: String) {
    DateDescending(S.desktop_dm_sort_date_desc),
    AmountDescending(S.desktop_po_sort_amount_desc),
    VendorAscending(S.ah_sort_vendor_asc),
    ;

    /** What the dropdown shows. */
    val label: String get() = str(labelKey)

    fun sort(orders: List<PurchaseOrder>): List<PurchaseOrder> = when (this) {
        // An order with no effective date sorts last rather than interleaving:
        // a blank is not "the epoch", it is "not decided yet".
        DateDescending -> orders.sortedWith(
            compareByDescending<PurchaseOrder> { it.effectiveDate ?: it.createdAt ?: 0L }
                .thenByDescending { it.number },
        )

        AmountDescending -> orders.sortedByDescending { it.gross }
        VendorAscending -> orders.sortedBy { it.vendorName.lowercase() }
    }
}

/**
 * A column an order table can be sorted by, when the header is clicked.
 *
 * Separate from [PoSortKey], which is the dropdown above the table: the web has
 * both, and the header click overrides the dropdown while it is set.
 */
enum class PoSortColumn { Number, Vendor, Department, Amount, EffectiveDate, Status, Assigned }

/** Ascending or descending, for a clicked column. */
enum class PoSortDirection { Ascending, Descending }
