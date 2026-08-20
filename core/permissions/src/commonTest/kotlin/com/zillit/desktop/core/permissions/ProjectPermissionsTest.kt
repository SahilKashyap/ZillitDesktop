package com.zillit.desktop.core.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate every later feature module asks.
 *
 * The plan calls this out as the thing that must be right in M4, because it is
 * relied on by everything after it and expensive to unpick later. These tests
 * are the contract.
 */
class ProjectPermissionsTest {

    private val callsheet = ToolAccess(
        identifier = "callsheet_tool",
        canView = true,
        canPost = false,
        canDownload = true,
    )
    private val budget = ToolAccess(
        identifier = "budget_tool",
        canView = false,
        canPost = false,
    )
    private val disabled = ToolAccess(
        identifier = "wardrobe_tool",
        enabled = false,
        canView = true,
        canPost = true,
    )

    private val crew = ProjectPermissions(listOf(callsheet, budget, disabled), isAdmin = false)
    private val admin = ProjectPermissions(listOf(callsheet, budget, disabled), isAdmin = true)

    @Test
    fun `the three access flags are independent`() {
        // A driver views the call sheet without posting; an accountant downloads
        // a report they cannot edit.
        assertTrue(crew.canView("callsheet_tool"))
        assertFalse(crew.canPost("callsheet_tool"))
        assertTrue(crew.canDownload("callsheet_tool"))
    }

    @Test
    fun `an unknown tool is denied, not granted`() {
        // Rights come from the server. "Absent" must read as "denied", or every
        // backend addition becomes a hole.
        assertFalse(crew.canView("tool_that_does_not_exist"))
        assertFalse(crew.canPost("tool_that_does_not_exist"))
        assertFalse(crew.canDownload("tool_that_does_not_exist"))
    }

    @Test
    fun `an unknown tool does not throw`() {
        // A client that crashed on an unfamiliar identifier would break every
        // time the backend shipped a new tool.
        assertEquals("mystery", crew.access("mystery").identifier)
    }

    @Test
    fun `an admin passes every access check`() {
        assertTrue(admin.canView("budget_tool"), "admin should see a tool crew cannot")
        assertTrue(admin.canPost("budget_tool"))
        assertTrue(admin.canDownload("budget_tool"))
    }

    @Test
    fun `an admin still cannot see a tool the production disabled`() {
        // `enabled` is the production's own switch, not a right. Showing admins
        // a tool nobody else has would misrepresent the production.
        assertFalse(admin.canView("wardrobe_tool"))
        assertFalse(admin.canPost("wardrobe_tool"))
        assertTrue(disabled.canView, "the underlying flag is set — `enabled` is what blocks it")
    }

    @Test
    fun `an admin is not granted rights to a tool that was never issued`() {
        // Bypass applies to tools the production has, not to anything nameable.
        assertFalse(admin.canView("tool_that_does_not_exist"))
    }

    @Test
    fun `posting implies viewing`() {
        // The UI must never offer an edit on something it will not show.
        val odd = ProjectPermissions(
            listOf(ToolAccess("odd_tool", canView = false, canPost = true)),
            isAdmin = false,
        )

        assertFalse(odd.canPost("odd_tool"), "post without view is not a usable right")
    }

    @Test
    fun `downloading implies viewing`() {
        val odd = ProjectPermissions(
            listOf(ToolAccess("odd_tool", canView = false, canDownload = true)),
            isAdmin = false,
        )

        assertFalse(odd.canDownload("odd_tool"))
    }

    @Test
    fun `visible tools exclude the unviewable and the disabled`() {
        assertEquals(listOf("callsheet_tool"), crew.visibleTools.map { it.identifier })
    }

    @Test
    fun `an admin's grid is their own rights, not everything`() {
        // The bypass answers per-tool questions; it does not build the list.
        // Neither phone consults isAdmin while filling the tools grid, and a
        // tile granted here opens a tool that still refuses the data.
        assertEquals(
            listOf("callsheet_tool"),
            admin.visibleTools.map { it.identifier },
            "budget_tool has no view_access for this user, admin or not",
        )
        assertTrue(admin.canView("budget_tool"), "the per-tool check still bypasses")
    }

    @Test
    fun `home and grid are separate placements`() {
        val tools = ProjectPermissions(
            listOf(
                ToolAccess("a", canView = true, isTool = true, onHome = true),
                ToolAccess("b", canView = true, isTool = true, onHome = false),
                ToolAccess("c", canView = true, isTool = false, onHome = true),
            ),
        )

        assertEquals(listOf("a", "c"), tools.homeTools.map { it.identifier })
        assertEquals(listOf("a", "b"), tools.gridTools.map { it.identifier })
    }

    @Test
    fun `before the tools call returns, nothing is permitted`() {
        // The window between sign-in and the tools response must not be a moment
        // where everything is briefly allowed.
        assertFalse(ProjectPermissions.Empty.canView("callsheet_tool"))
        assertTrue(ProjectPermissions.Empty.visibleTools.isEmpty())
    }

    @Test
    fun `server order is preserved`() {
        // The grid renders in this order; sorting here would silently override
        // whatever ordering an admin configured for the production.
        val ordered = listOf("z_tool", "a_tool", "m_tool")
        val permissions = ProjectPermissions(ordered.map { ToolAccess(it, canView = true) })

        assertEquals(ordered, permissions.visibleTools.map { it.identifier })
    }
}
