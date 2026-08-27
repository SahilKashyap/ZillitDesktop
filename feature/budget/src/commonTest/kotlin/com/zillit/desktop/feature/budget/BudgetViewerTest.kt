package com.zillit.desktop.feature.budget

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rights gate, held to the web's own reading of it.
 *
 * One screen serves two tools, so who sees what is decided here rather than
 * by which tile was clicked.
 */
class BudgetViewerTest {

    /**
     * No rights yet is not the same as no access.
     *
     * `project/tools` is still in flight when the tool first composes; a
     * viewer that read an empty set as a refusal would flash "no access" at
     * everyone, every time.
     */
    @Test
    fun `an empty permission set is unresolved, not a refusal`() {
        val viewer = BudgetViewer.from(ProjectPermissions(emptyList()))

        assertFalse(viewer.resolved)
        assertFalse(viewer.hasNoAccess, "an unanswered call must never read as a denial")
    }

    /** Neither budget readable: the screen has nothing to show at all. */
    @Test
    fun `neither budget readable is no access`() {
        val viewer = BudgetViewer.from(permissions(mainView = false, departmentView = false))

        assertTrue(viewer.resolved)
        assertTrue(viewer.hasNoAccess)
    }

    /** The common case: a head of department sees their own budget only. */
    @Test
    fun `department rights alone open the department half`() {
        val viewer = BudgetViewer.from(permissions(mainView = false, departmentView = true))

        assertFalse(viewer.hasNoAccess)
        assertTrue(viewer.departmentOnly)
        assertTrue(viewer.canViewDepartment)
    }

    /**
     * A main budget you may post to but not view still counts as main access
     * — the web decides `showDeptOnly` on both rights being absent
     * (`BudgetMain.jsx:101-106`), and this copies that rather than tidying it.
     */
    @Test
    fun `posting rights alone keep the main half in view`() {
        val viewer = BudgetViewer.from(permissions(mainView = false, mainPost = true, departmentView = true))

        assertFalse(viewer.departmentOnly)
    }

    /** Download is a right of its own, and either budget granting it is enough. */
    @Test
    fun `download comes from either budget`() {
        val viewer = BudgetViewer.from(
            permissions(mainView = true, departmentView = false, departmentDownload = true),
        )

        assertTrue(viewer.canDownload)
    }

    private fun permissions(
        mainView: Boolean = false,
        mainPost: Boolean = false,
        departmentView: Boolean = false,
        departmentDownload: Boolean = false,
    ) = ProjectPermissions(
        listOf(
            ToolAccess(
                identifier = BudgetViewer.MAIN_TOOL,
                canView = mainView,
                canPost = mainPost,
            ),
            ToolAccess(
                identifier = BudgetViewer.DEPARTMENT_TOOL,
                canView = departmentView,
                canDownload = departmentDownload,
            ),
        ),
    )
}
