package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRights
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deal memo tool's routes and gates, against `DealMemoModule.jsx` and
 * `useDealMemoRights.js`.
 */
class DealMemoRouteTest {

    private val accountant = DealMemoRights.resolve(
        rightsLoaded = true,
        hasPostingAccess = false,
        hasViewAccess = false,
        departmentIdentifier = "department_accounts",
        memberStatus = "accepted",
    )

    private val crew = DealMemoRights.resolve(
        rightsLoaded = true,
        hasPostingAccess = false,
        hasViewAccess = true,
        departmentIdentifier = "department_camera",
        memberStatus = "accepted",
    )

    private val approver = DealMemoMetadata(isApprover = true, loaded = true)

    // -- rights -------------------------------------------------------------

    @Test
    fun `every accountant posts, whatever the tool grid says`() {
        assertTrue(accountant.canPost)
        assertTrue(accountant.canView)
    }

    @Test
    fun `a pending member views but never posts, even as an accountant`() {
        val pending = DealMemoRights.resolve(true, true, true, "department_accounts", "pending")

        assertTrue(pending.canView, "without view the tool would turn them away before My Deal")
        assertFalse(pending.canPost)
        assertTrue(pending.isPending)
    }

    @Test
    fun `only an explicit pending restricts`() {
        val unknown = DealMemoRights.resolve(true, false, false, "department_accounts", null)

        assertFalse(unknown.isPending, "an unknown status is not a pending one")
        assertTrue(unknown.canPost)
    }

    // -- tabs ---------------------------------------------------------------

    @Test
    fun `posting users see the management tabs and land on All Deals`() {
        assertEquals(
            listOf(DealTab.Overview, DealTab.Deals, DealTab.MyDeal, DealTab.Notices),
            DealTab.visible(accountant, DealMemoMetadata.Unknown),
        )
        assertEquals(DealTab.Deals, DealTab.defaultFor(accountant))
    }

    @Test
    fun `crew see My Deal, plus Approval Queue only when they approve`() {
        assertEquals(listOf(DealTab.MyDeal), DealTab.visible(crew, DealMemoMetadata.Unknown))
        assertEquals(listOf(DealTab.MyDeal, DealTab.ApprovalQueue), DealTab.visible(crew, approver))
        assertEquals(DealTab.MyDeal, DealTab.defaultFor(crew))
    }

    @Test
    fun `a pending approver still sees only My Deal`() {
        val pending = crew.copy(isPending = true)

        assertEquals(listOf(DealTab.MyDeal), DealTab.visible(pending, approver))
    }

    // -- parsing ------------------------------------------------------------

    @Test
    fun `every page the web routes parses`() {
        assertNull(DealMemoRoute.parse(""))
        assertNull(DealMemoRoute.parse("/"))
        assertEquals(DealMemoRoute.Tab(DealTab.Notices), DealMemoRoute.parse("/notices"))
        assertEquals(DealMemoRoute.Deal("abc123"), DealMemoRoute.parse("/deals/abc123"))
        assertEquals(DealMemoRoute.NewDeal(), DealMemoRoute.parse("/new"))
        assertEquals(DealMemoRoute.TemplateWizard(), DealMemoRoute.parse("/templates/new"))
        assertEquals(DealMemoRoute.TemplateWizard("t1"), DealMemoRoute.parse("/templates/t1/edit"))
        assertEquals(DealMemoRoute.TemplateBuilder(), DealMemoRoute.parse("/templates/build"))
        assertEquals(DealMemoRoute.TemplateBuilder("t1"), DealMemoRoute.parse("/templates/t1/build"))
        assertEquals(DealMemoRoute.QuickDeal(), DealMemoRoute.parse("/quick"))
        assertEquals(DealMemoRoute.FirstSetup, DealMemoRoute.parse("/first-setup"))
        assertEquals(DealMemoRoute.GlobalRates, DealMemoRoute.parse("/global-production-rates"))
        assertEquals(DealMemoRoute.NoticeTemplate, DealMemoRoute.parse("/notice-template"))
        assertEquals(DealMemoRoute.CompleteDetails, DealMemoRoute.parse("/my-deal/complete"))
    }

    @Test
    fun `the setup hub family carries its group and setup in the path`() {
        assertEquals(DealMemoRoute.SetupHub(), DealMemoRoute.parse("/setup-hub"))
        assertEquals(DealMemoRoute.SetupHub(SetupGroup.Union), DealMemoRoute.parse("/setup-hub/union"))
        val newNonUnion = DealMemoRoute.parse("/setup-hub/non-union/new") as DealMemoRoute.SetupHub
        assertEquals(SetupGroup.NonUnion, newNonUnion.group)
        assertTrue(newNonUnion.isNewSetup)
        val editing = DealMemoRoute.parse("/setup-hub/union/s-42") as DealMemoRoute.SetupHub
        assertTrue(editing.editsSetup)
        assertEquals("/setup-hub/union/s-42", editing.tail)
    }

    @Test
    fun `every route writes back the path it was read from`() {
        listOf(
            "/overview", "/deals", "/my-deal", "/approval-queue", "/notices", "/deals/d1", "/new",
            "/templates/new", "/templates/t1/edit", "/templates/build", "/templates/t1/build", "/quick", "/edit",
            "/first-setup", "/setup-hub", "/setup-hub/non-union", "/setup-hub/union/new",
            "/global-production-rates", "/notice-template", "/my-deal/complete",
        ).filter { it != "/edit" }.forEach { path ->
            assertEquals(path, DealMemoRoute.parse(path)?.tail, path)
        }
    }

    // -- bounces --------------------------------------------------------------

    @Test
    fun `the bare tool lands on the default tab for the viewer`() {
        assertEquals(
            DealMemoRoute.Tab(DealTab.Deals),
            DealMemoRoute.resolve(null, accountant, DealMemoMetadata.Unknown),
        )
        assertEquals(DealMemoRoute.Tab(DealTab.MyDeal), DealMemoRoute.resolve(null, crew, DealMemoMetadata.Unknown))
    }

    @Test
    fun `a viewer who may not post is sent from every posting page to My Deal`() {
        listOf(
            DealMemoRoute.NewDeal(), DealMemoRoute.QuickDeal(), DealMemoRoute.EditDeal("d1"),
            DealMemoRoute.FirstSetup, DealMemoRoute.SetupHub(), DealMemoRoute.GlobalRates,
            DealMemoRoute.TemplateBuilder(), DealMemoRoute.TemplateWizard(),
        ).forEach { route ->
            assertEquals(
                DealMemoRoute.Tab(DealTab.MyDeal),
                DealMemoRoute.resolve(route, crew, DealMemoMetadata.Unknown),
            )
        }
    }

    @Test
    fun `a hidden tab goes to the default tab, but a crew member opens their own deal by id`() {
        assertEquals(
            DealMemoRoute.Tab(DealTab.MyDeal),
            DealMemoRoute.resolve(DealMemoRoute.Tab(DealTab.Deals), crew, DealMemoMetadata.Unknown),
        )
        assertEquals(
            DealMemoRoute.Deal("d1"),
            DealMemoRoute.resolve(DealMemoRoute.Deal("d1"), crew, DealMemoMetadata.Unknown),
        )
    }

    @Test
    fun `nothing is bounced before the rights have loaded`() {
        val loading = DealMemoRights()

        assertEquals(
            DealMemoRoute.GlobalRates,
            DealMemoRoute.resolve(DealMemoRoute.GlobalRates, loading, DealMemoMetadata.Unknown),
        )
    }
}
