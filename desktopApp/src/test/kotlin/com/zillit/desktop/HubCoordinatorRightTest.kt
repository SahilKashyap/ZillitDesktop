package com.zillit.desktop

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Transportation Hub's coordinator door is the posting right as issued —
 * the web's `resolveHubRole` — never the admin grant every other tool honours.
 */
class HubCoordinatorRightTest {

    private fun rights(canPost: Boolean, isAdmin: Boolean, enabled: Boolean = true) = ProjectPermissions(
        listOf(ToolAccess(TOOL, enabled = enabled, canView = true, canPost = canPost)),
        isAdmin = isAdmin,
    )

    @Test
    fun `an admin without the posting right is not a coordinator`() {
        val admin = rights(canPost = false, isAdmin = true)
        // The general check grants it; the hub's must not.
        assertTrue(admin.canPost(TOOL))
        assertFalse(admin.postsAsIssued(TOOL))
    }

    @Test
    fun `the posting right makes a coordinator, admin or not`() {
        assertTrue(rights(canPost = true, isAdmin = false).postsAsIssued(TOOL))
        assertTrue(rights(canPost = true, isAdmin = true).postsAsIssued(TOOL))
    }

    @Test
    fun `a tool switched off for the production, or never issued, posts nothing`() {
        assertFalse(rights(canPost = true, isAdmin = true, enabled = false).postsAsIssued(TOOL))
        assertFalse(ProjectPermissions(emptyList(), isAdmin = true).postsAsIssued(TOOL))
    }

    private companion object {
        const val TOOL = "transportation_tool"
    }
}
