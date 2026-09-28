package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AccrualDetail
import com.zillit.desktop.feature.invoices.domain.AccrualSort
import com.zillit.desktop.feature.invoices.domain.VendorFilter
import com.zillit.desktop.feature.invoices.domain.VendorPo
import com.zillit.desktop.feature.invoices.domain.VendorRow

// -- Vendors (the web's SuppliersPage) ------------------------------------------

/** The Vendors page's chips and the detail open over it. */
data class VendorsUi(
    val filter: VendorFilter = VendorFilter.All,
    /** The row open in "Vendor Detail — {name}". */
    val detail: VendorRow? = null,
    val historyTab: VendorHistoryTab = VendorHistoryTab.PurchaseOrders,
    /** Every purchase order, read once the detail opens; the history filters it to the vendor. */
    val orders: List<VendorPo> = emptyList(),
    val ordersLoading: Boolean = false,
)

/** The four tabs of "Vendor History" (`SuppliersPage.jsx:299-304`). */
enum class VendorHistoryTab(private val labelKey: String) {
    PurchaseOrders(S.desktop_inv_vendor_tab_pos),
    Invoices(S.desktop_inv_vendor_tab_invoices),
    Payments(S.desktop_inv_vendor_tab_payments),
    CreditNotes(S.desktop_inv_vendor_tab_credits),
    ;

    /** "Purchase Orders (3)". */
    fun label(count: Int): String = str(labelKey, count)
}

/** Vendors' own events, routed to [InvoiceVendorActions]. */
sealed interface VendorEvent : InvoicesEvent {
    data class SelectFilter(val filter: VendorFilter) : VendorEvent
    data class Open(val row: VendorRow) : VendorEvent
    data object Close : VendorEvent
    data class SelectTab(val tab: VendorHistoryTab) : VendorEvent

    /** Account Hub → Vendors, the new-vendor form (`?action=add`). */
    data object AddVendor : VendorEvent

    /** Account Hub → Vendors, this vendor's form (`?action=edit&id=`). */
    data class EditVendor(val vendorId: String) : VendorEvent
}

// -- Accruals ---------------------------------------------------------------------

/** The Accruals page's own controls — its Sort and Dept selects — and the detail over it. */
data class AccrualsUi(
    val sort: AccrualSort = AccrualSort.AccrualDesc,
    /** The page's own department filter; the Register's is separate. Null is every department. */
    val departmentId: String? = null,
    /** The row open in the detail; its record arrives in [detail]. */
    val detailId: String? = null,
    val detail: AccrualDetail? = null,
    val detailLoading: Boolean = false,
)

/** Accruals' own events, routed to [InvoiceAccrualActions]. */
sealed interface AccrualEvent : InvoicesEvent {
    data class SelectSort(val sort: AccrualSort) : AccrualEvent
    data class SelectDepartment(val departmentId: String?) : AccrualEvent
    data class Open(val accrualId: String) : AccrualEvent
    data object Close : AccrualEvent
}
