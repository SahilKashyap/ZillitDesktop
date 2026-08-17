package com.zillit.desktop.feature.budgetbuilder

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who gets into Budget Builder.
 *
 * The rule under test is the web's `useBudgetBuilderRights`, transcribed:
 * entry is governed purely by the tool's own view/posting rights. There is
 * deliberately no accounts-department gate — line producers build budgets —
 * and posting access alone is enough to enter.
 */
class BudgetBuilderAccessTest {

    private fun permissions(
        canView: Boolean = true,
        canPost: Boolean = true,
        enabled: Boolean = true,
        isAdmin: Boolean = false,
    ) = ProjectPermissions(
        listOf(
            ToolAccess(
                identifier = BudgetBuilderViewer.TOOL_IDENTIFIER,
                enabled = enabled,
                canView = canView,
                canPost = canPost,
                canDownload = true,
            ),
        ),
        isAdmin = isAdmin,
    )

    /**
     * Before rights arrive, nothing is denied. The server enforces the real
     * gate on every call regardless; a hard default here just flashes
     * "no access" at every user while the tools call is in flight.
     */
    @Test
    fun `an unresolved viewer is not blocked`() {
        val viewer = BudgetBuilderViewer.from(ProjectPermissions.Empty)

        assertFalse(viewer.ready)
        assertFalse(viewer.isBlocked)
        assertTrue(viewer.canView)
    }

    @Test
    fun `a viewer without rights is blocked once rights are known`() {
        val viewer = BudgetBuilderViewer.from(permissions(canView = false, canPost = false))

        assertTrue(viewer.ready)
        assertTrue(viewer.isBlocked)
    }

    /**
     * Posting access alone admits — the web computes
     * `canView = posting_access || view_access`, and a viewer built from
     * [ProjectPermissions]' derived helpers would get this wrong, because
     * `canPost(id)` there requires the view flag first.
     */
    @Test
    fun `posting access alone is enough to enter`() {
        val viewer = BudgetBuilderViewer.from(permissions(canView = false, canPost = true))

        assertTrue(viewer.canView)
        assertTrue(viewer.canPost)
        assertFalse(viewer.isBlocked)
    }

    @Test
    fun `view access without posting opens read-only`() {
        val viewer = BudgetBuilderViewer.from(permissions(canView = true, canPost = false))

        assertTrue(viewer.canView)
        assertFalse(viewer.canPost)
    }

    /**
     * A disabled tool row denies, whatever its flags say.
     *
     * The grid carries another visible tool so the rights read as *resolved*:
     * an all-empty grid is indistinguishable from one still loading, and
     * resolves to "not yet known" instead.
     */
    @Test
    fun `a disabled tool does not admit`() {
        val viewer = BudgetBuilderViewer.from(
            ProjectPermissions(
                listOf(
                    ToolAccess(
                        identifier = BudgetBuilderViewer.TOOL_IDENTIFIER,
                        enabled = false,
                        canView = true,
                        canPost = true,
                    ),
                    ToolAccess("chat_tool", enabled = true, canView = true),
                ),
            ),
        )

        assertTrue(viewer.ready)
        assertTrue(viewer.isBlocked)
    }

    /**
     * The desktop's admin bypass applies, as it does on every other tool:
     * an admin passes access checks on a tool the production has enabled.
     * (The web hook reads the rights list alone — a deliberate deviation,
     * documented on the viewer; the server remains the real gate.)
     */
    @Test
    fun `an admin passes access checks on an enabled tool`() {
        val viewer = BudgetBuilderViewer.from(
            permissions(canView = false, canPost = false, isAdmin = true),
        )

        assertTrue(viewer.canView)
        assertTrue(viewer.canPost)
        assertFalse(viewer.isBlocked)
    }
}
