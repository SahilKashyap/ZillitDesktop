package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult

/**
 * Vendors — the web's `SuppliersPage`: the quick filters, and the vendor
 * detail a row opens, whose history reads the production's purchase orders
 * (`GET /api/v2/purchase-orders?per_page=200`) and filters them to the
 * vendor. Adding or correcting a vendor is the Account Hub's job; the page
 * sends the reader there, as the web's `navigate` does.
 */
internal class InvoiceVendorActions(private val vm: InvoicesViewModel) {

    /** True when [event] was this page's own. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is VendorEvent.SelectFilter -> ui { copy(filter = event.filter) }
            is VendorEvent.Open -> open(event)
            VendorEvent.Close -> ui { copy(detail = null) }
            is VendorEvent.SelectTab -> ui { copy(historyTab = event.tab) }
            VendorEvent.AddVendor -> vm.navigate(ADD_VENDOR_ROUTE)
            is VendorEvent.EditVendor -> if (event.vendorId.isNotBlank()) {
                vm.navigate("$EDIT_VENDOR_ROUTE${event.vendorId}")
            }
            else -> return false
        }
        return true
    }

    /** The detail opens on Purchase Orders; a vendor on the master list has its orders read. */
    private fun open(event: VendorEvent.Open) {
        val masterId = event.row.vendor.id
        ui {
            copy(
                detail = event.row,
                historyTab = VendorHistoryTab.PurchaseOrders,
                ordersLoading = masterId.isNotBlank(),
            )
        }
        if (masterId.isBlank()) return
        vm.run {
            val orders = (vm.repo.vendorPurchaseOrders() as? ZillitResult.Success)?.data
            ui { copy(orders = orders ?: emptyList(), ordersLoading = false) }
        }
    }

    private fun ui(change: VendorsUi.() -> VendorsUi) = vm.update { copy(vendorsPage = vendorsPage.change()) }

    companion object {
        /** Account Hub → Vendors, the new-vendor form — the web's `/account-hub/vendors/all?action=add`. */
        const val ADD_VENDOR_ROUTE = "/film-tools/account-hub/vendors/all?action=add"

        /** Account Hub → Vendors, one vendor's form; the vendor's id follows. */
        const val EDIT_VENDOR_ROUTE = "/film-tools/account-hub/vendors/all?action=edit&id="
    }
}
