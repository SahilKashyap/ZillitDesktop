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
        // Null, not a loading state: the hub renders no screen for them.
        assertNull(HubNavigation.landing(viewer))
        // But they are not left staring at it — the web puts exactly this
        // person in Purchase Orders, and so does the console.
        assertEquals("purchase-orders", HubNavigation.landingTool(viewer)?.id)
    }

    /**
     * Without Purchase Orders, they land on whichever spend tool they hold.
     *
     * The web's rule is "the default landing is the Purchase Orders module";
     * a user who cannot open PO still has work here, and a dead end would be a
     * worse answer than their next-best tool.
     */
    @Test
    fun `a department user without POs lands on the tool they do have`() {
        val viewer = AccountHubViewer.from(
            permissions(others = listOf(ToolAccess("cash_expenses_tool", enabled = true, canView = true))),
            userId = "u1",
            isAccountant = false,
        )

        assertEquals("cash-expenses", HubNavigation.landingTool(viewer)?.id)
    }

    /** Nothing at all is the one case the empty state is honest about. */
    @Test
    fun `a viewer with no rows at all lands nowhere`() {
        val viewer = AccountHubViewer.from(permissions(), userId = "u1", isAccountant = false)

        assertNull(HubNavigation.landing(viewer))
        assertNull(HubNavigation.landingTool(viewer))
    }

    /** An accountant has console screens, so nothing is opened for them. */
    @Test
    fun `an accountant is not sent to a tool`() {
        val viewer = AccountHubViewer.from(permissions(), "u1", isAccountant = true)

        assertNull(HubNavigation.landingTool(viewer))
    }

    /**
     * Timecard and Deal Memo are not sidebar rows.
     *
     * Both were removed from the web's sidebar when they became their own
     * tools, and this file's own header said so while listing them anyway.
     * They keep their approval chains — see `ApprovalModule`.
     */
    @Test
    fun `the payroll group lists payroll alone`() {
        val viewer = AccountHubViewer.from(
            permissions(
                others = listOf(
                    ToolAccess("payroll_tool", enabled = true, canView = true),
                    ToolAccess("timecard_tool", enabled = true, canView = true),
                    ToolAccess("deal_memo_tool", enabled = true, canView = true),
                ),
            ),
            userId = "u1",
            isAccountant = true,
        )

        val payroll = HubNavigation.visibleTo(viewer).first { it.title == "Payroll Management" }

        assertEquals(listOf("payroll"), payroll.items.map { it.id })
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
                // Reports comes before Management in the sidebar.
                HubArea.PeriodClose,
                HubArea.Vendors,
                // Under Management with Vendors, as the web files it.
                HubArea.TrialBalance,
                HubArea.BibleReport,
                HubArea.Approvers,
                // Budget sits above the chart it hangs off, as on the web.
                HubArea.Budget,
                HubArea.ChartOfAccounts,
                // Last under Configuration, as on the web.
                HubArea.FormConfig,
            ),
            HubNavigation.areasFor(viewer),
        )
    }

    /**
     * Tax Filing is a hand-off, not a hub page.
     *
     * It reaches HMRC and files a legal return, so it gets a window of its own
     * here — unlike the web, where it renders inside the console's shell. The
     * row sits under Management as it does there, and it is the accountant's:
     * a department user has no business filing a company's VAT.
     */
    @Test
    fun `tax filing is offered to accountants as its own tool`() {
        val management = HubNavigation
            .visibleTo(AccountHubViewer.from(permissions(), "u1", isAccountant = true))
            .first { it.title == "Management" }

        val row = management.items.first { it.id == "tax-filing" }
        assertEquals("Tax Filing", row.label)
        assertEquals(
            "/film-tools/account-hub/tax-filing",
            (row.target as HubTarget.Tool).toolPath,
        )
        // After Bible Report and after Bank Reconciliation, as the web files it.
        assertTrue(
            management.items.indexOfFirst { it.id == "bible-report" } <
                management.items.indexOfFirst { it.id == "bank-reconciliation" },
        )
        assertTrue(
            management.items.indexOfFirst { it.id == "bank-reconciliation" } <
                management.items.indexOfFirst { it.id == "tax-filing" },
        )

        val department = HubNavigation
            .visibleTo(AccountHubViewer.from(permissions(), "u2", isAccountant = false))
            .flatMap { it.items }
        assertFalse(department.any { it.id == "tax-filing" })
    }

    /** Cost Report sits above Period Close under Reports, as on the web. */
    @Test
    fun `the reports group is in the web's order`() {
        val reports = HubNavigation
            .visibleTo(AccountHubViewer.from(permissions(), "u1", isAccountant = true))
            .first { it.title == "Reports" }

        assertEquals(listOf("cost-report", "period-close"), reports.items.map { it.id })
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
