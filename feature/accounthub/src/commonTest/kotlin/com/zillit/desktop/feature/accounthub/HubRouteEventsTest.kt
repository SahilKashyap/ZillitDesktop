package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.VendorFilter
import com.zillit.desktop.feature.accounthub.ui.hubRouteEvents
import kotlin.test.Test
import kotlin.test.assertEquals

/** A route below the console's path names a screen — how another tool's Form Configuration card lands here. */
class HubRouteEventsTest {

    @Test
    fun `the bare path and an unknown slug ask for nothing`() {
        assertEquals(emptyList(), hubRouteEvents("/film-tools/account-hub"))
        assertEquals(emptyList(), hubRouteEvents("/film-tools/account-hub/"))
        assertEquals(emptyList(), hubRouteEvents("/film-tools/account-hub/nowhere"))
    }

    @Test
    fun `an area's slug opens it`() {
        assertEquals(listOf(AccountHubEvent.Open(HubArea.Vendors)), hubRouteEvents("/film-tools/account-hub/vendors"))
    }

    @Test
    fun `the forms editor opens on the module the route names`() {
        assertEquals(
            listOf(AccountHubEvent.Open(HubArea.FormConfig), AccountHubEvent.OpenFormConfig(FormModule.PurchaseOrders)),
            hubRouteEvents("/film-tools/account-hub/form-config/purchase_orders"),
        )
        assertEquals(
            listOf(AccountHubEvent.Open(HubArea.FormConfig)),
            hubRouteEvents("/film-tools/account-hub/form-config/not_a_module"),
        )
    }

    /**
     * The web's own vendor addresses land in the same place.
     *
     * Its Invoices suppliers page sends someone to `vendors/all?action=add` to
     * add a supplier and `?action=edit&id=` to correct one, and the tab rides in
     * the path. All three are read here.
     */
    @Test
    fun `the web's vendor addresses open the tab and the form`() {
        assertEquals(
            listOf(
                AccountHubEvent.Open(HubArea.Vendors),
                AccountHubEvent.FilterVendors(VendorFilter.All),
                AccountHubEvent.OpenVendorForm(),
            ),
            hubRouteEvents("/film-tools/account-hub/vendors/all?action=add"),
        )
        assertEquals(
            listOf(
                AccountHubEvent.Open(HubArea.Vendors),
                AccountHubEvent.FilterVendors(VendorFilter.Unverified),
                AccountHubEvent.OpenVendorForm("v-42"),
            ),
            hubRouteEvents("/film-tools/account-hub/vendors/unverified?action=edit&id=v-42"),
        )
    }

    /** An edit with no id, or an action nobody knows, opens the tab and nothing more. */
    @Test
    fun `a malformed vendor action is ignored`() {
        assertEquals(
            listOf(AccountHubEvent.Open(HubArea.Vendors)),
            hubRouteEvents("/film-tools/account-hub/vendors?action=edit"),
        )
        assertEquals(
            listOf(AccountHubEvent.Open(HubArea.Vendors), AccountHubEvent.FilterVendors(VendorFilter.Verified)),
            hubRouteEvents("/film-tools/account-hub/vendors/verified?action=teleport"),
        )
    }

    /** The web's "Set Approval Level" link from the card and petty-cash modules. */
    @Test
    fun `approvers lands on the module the link names`() {
        assertEquals(
            listOf(
                AccountHubEvent.Open(HubArea.Approvers),
                AccountHubEvent.SwitchApprovalModule(ApprovalModule.CardExpenses),
            ),
            hubRouteEvents("/film-tools/account-hub/approvers?module=card_expenses"),
        )
        // An unknown module still opens the page, on whatever it last showed.
        assertEquals(
            listOf(AccountHubEvent.Open(HubArea.Approvers)),
            hubRouteEvents("/film-tools/account-hub/approvers?module=x"),
        )
    }

    /** Payroll's Entry Setup tile opens the settings it edits, in Production Setup. */
    @Test
    fun `production setup opens the named settings modal`() {
        assertEquals(
            listOf(
                AccountHubEvent.Open(HubArea.ProductionSetup),
                AccountHubEvent.OpenSetupModal(SetupModal.Payroll),
            ),
            hubRouteEvents("/film-tools/account-hub/production-setup?setup=payroll"),
        )
        assertEquals(
            listOf(
                AccountHubEvent.Open(HubArea.ProductionSetup),
                AccountHubEvent.OpenSetupModal(SetupModal.PurchaseOrders),
            ),
            hubRouteEvents("/film-tools/account-hub/production-setup?setup=po_setup"),
        )
    }
}
