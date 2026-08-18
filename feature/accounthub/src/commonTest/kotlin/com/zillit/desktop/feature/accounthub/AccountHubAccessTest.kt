package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.HubTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who gets into the console, and what they see once inside.
 *
 * Entry is decided twice — by tool access and by department — and the web's own
 * shell got this wrong twice by conflating them: every hub route was gated on
 * department alone, so revoking tool access mid-session left somebody sitting
 * inside the finance module.
 */
class AccountHubAccessTest {

    private fun permissions(
        canView: Boolean = true,
        canPost: Boolean = true,
        others: List<ToolAccess> = emptyList(),
        isAdmin: Boolean = false,
    ) = ProjectPermissions(
        listOf(
            ToolAccess(
                identifier = AccountHubViewer.TOOL_IDENTIFIER,
                enabled = true,
                canView = canView,
                canPost = canPost,
                canDownload = true,
            ),
        ) + others,
        isAdmin = isAdmin,
    )

    /**
     * Before rights arrive, nothing is denied.
     *
     * A hard default flashes "no access" at every user on every open while the
     * tools call is in flight. The server enforces the real gate on every read
     * and write regardless, so the only cost of being permissive here is a
     * screen that briefly offers something the next frame withdraws.
     */
    @Test
    fun `an unresolved viewer is not blocked`() {
        val viewer = AccountHubViewer.from(ProjectPermissions.Empty, userId = "u1", isAccountant = true)

        assertFalse(viewer.ready)
        assertFalse(viewer.isBlocked)
        assertTrue(viewer.canView)
    }

    @Test
    fun `a viewer without view rights is blocked once rights are known`() {
        val viewer = AccountHubViewer.from(permissions(canView = false), "u1", isAccountant = true)

        assertTrue(viewer.ready)
        assertTrue(viewer.isBlocked)
    }

    /**
     * Reads are open, writes are the accounts department's.
     *
     * Every line-item picker in the platform needs the chart of accounts, so a
     * non-accountant must be able to look. The server 403s their writes, and
     * offering a save that will be refused is worse than not offering it.
     */
    @Test
    fun `a non-accountant with posting rights still cannot edit`() {
        val viewer = AccountHubViewer.from(permissions(), "u1", isAccountant = false)

        assertTrue(viewer.canView)
        assertFalse(viewer.canEdit)
    }

    @Test
    fun `an accountant with posting rights can edit`() {
        val viewer = AccountHubViewer.from(permissions(), "u1", isAccountant = true)

        assertTrue(viewer.canEdit)
    }

    /** An admin passes the department check as well as the access one. */
    @Test
    fun `an admin can edit without being in accounts`() {
        val viewer = AccountHubViewer.from(permissions(isAdmin = true), "u1", isAccountant = false)

        assertTrue(viewer.canEdit)
    }

    @Test
    fun `accounts membership is matched as a substring, as the web does`() {
        assertTrue(AccountHubViewer.isAccountsDepartment(listOf("production_accounts")))
        assertTrue(AccountHubViewer.isAccountsDepartment(listOf("camera", "accounts")))
        assertFalse(AccountHubViewer.isAccountsDepartment(listOf("camera", "grip")))
    }

    /**
     * A department user gets the three areas they raise spend in — and nothing
     * the hub itself renders.
     */
    @Test
    fun `a department user sees only the spend tools`() {
        val viewer = AccountHubViewer.from(
            permissions(
                others = listOf(
                    ToolAccess("purchase_order_tool", enabled = true, canView = true),
                    ToolAccess("card_expenses_tool", enabled = true, canView = true),
                    ToolAccess("cash_expenses_tool", enabled = true, canView = true),
                ),
            ),
            userId = "u1",
            isAccountant = false,
        )

        val items = HubNavigation.visibleTo(viewer).flatMap { it.items }

        assertEquals(
            listOf("purchase-orders", "card-expenses", "cash-expenses"),
            items.map { it.id },
        )
        assertTrue(items.all { it.target is HubTarget.Tool })
        // Null, not a loading state: there is genuinely nothing here for them
        // to open, and the console says so rather than rendering blank.
        assertNull(HubNavigation.landing(viewer))
    }

    @Test
    fun `an accountant lands on Production Setup`() {
        val viewer = AccountHubViewer.from(permissions(), "u1", isAccountant = true)

        assertEquals(HubArea.ProductionSetup, HubNavigation.landing(viewer))
    }

    /**
     * A hosted tool the user has no rights to is dropped from the sidebar.
     *
     * Not shown disabled: a row that cannot be opened reads as a permission to
     * ask for, and the tools this person genuinely has are what the list is
     * for.
     */
    @Test
    fun `a hosted tool with no rights is not listed`() {
        val viewer = AccountHubViewer.from(
            permissions(others = listOf(ToolAccess("payroll_tool", enabled = true, canView = true))),
            userId = "u1",
            isAccountant = true,
        )

        val payrollSection = HubNavigation.visibleTo(viewer)
            .first { it.title == "Payroll Management" }

        assertEquals(listOf("payroll"), payrollSection.items.map { it.id })
    }

    /**
     * The hub's own screens are gated by the hub, not by a per-screen tool.
     *
     * They have no identifier of their own — Production Setup is not a film
     * tool — so they resolve to `account_hub_tool` and travel with it.
     */
    @Test
    fun `the hub's own areas ride on the hub's own right`() {
        val viewer = AccountHubViewer.from(permissions(), "u1", isAccountant = true)

        assertEquals(
            listOf(
                HubArea.ProductionSetup,
                HubArea.Vendors,
                HubArea.Approvers,
                HubArea.ChartOfAccounts,
            ),
            HubNavigation.areasFor(viewer),
        )
    }

    /** An empty section is dropped rather than rendered as a heading with nothing under it. */
    @Test
    fun `a section whose every row is hidden does not render`() {
        val viewer = AccountHubViewer.from(permissions(), "u1", isAccountant = true)

        val titles = HubNavigation.visibleTo(viewer).map { it.title }

        // Every payroll row is gated on a tool this viewer lacks; Transactions
        // keeps its ungated Invoices row (as on the web) and stays.
        assertFalse(titles.contains("Payroll Management"))
        assertTrue(titles.contains("Transactions"))
        assertTrue(titles.contains("Setup"))
    }

    /**
     * The chart is accountant-only, and an admin is not exempt.
     *
     * Verified on dev 2026-08-12: a production admin outside accounts got
     * `403 {"message":"accountant_access_only"}` creating a code, while the same
     * user's project-settings writes succeeded. One flag cannot serve both, so
     * `canEdit` keeps the admin bypass and `canActAsAccountant` does not.
     */
    @Test
    fun `an admin outside accounts may edit setup but not the chart`() {
        val viewer = AccountHubViewer.from(permissions(isAdmin = true), "u1", isAccountant = false)

        assertTrue(viewer.canEdit)
        assertFalse(viewer.canActAsAccountant)
    }

    @Test
    fun `an accountant may edit the chart`() {
        val viewer = AccountHubViewer.from(permissions(), "u1", isAccountant = true)

        assertTrue(viewer.canActAsAccountant)
    }

    /** Posting rights still gate it — accounts membership alone is not enough. */
    @Test
    fun `an accountant without posting rights may not edit the chart`() {
        val viewer = AccountHubViewer.from(permissions(canPost = false), "u1", isAccountant = true)

        assertFalse(viewer.canActAsAccountant)
    }
}
