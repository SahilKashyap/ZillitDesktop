package com.zillit.desktop.feature.budget.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * What this person may do with each of the two budgets.
 *
 * One screen serves both tools, as the web's does: `main_budget_tool` and
 * `department_budget_tool` are separate rights rows, and the screen shows
 * whichever sections the rights allow (`BudgetMain.jsx:88-115`).
 */
data class BudgetViewer(
    val canViewMain: Boolean = false,
    val canPostMain: Boolean = false,
    val canViewDepartment: Boolean = false,
    val canPostDepartment: Boolean = false,
    val canDownload: Boolean = false,
    /** False until `project/tools` has answered — not a denial. See [from]. */
    val resolved: Boolean = false,
) {

    /**
     * Neither budget is readable, so there is nothing to show at all — the web
     * bounces such a user back to the tools grid (`BudgetMain.jsx:96-100`).
     * Unresolved rights are never a refusal: the call is simply still out.
     */
    val hasNoAccess: Boolean get() = resolved && !canViewMain && !canViewDepartment

    /**
     * Only the department half is theirs. The web decides this on the main
     * budget having *neither* right (`BudgetMain.jsx:101-106`) — a main budget
     * you may post to but not view still counts as access, odd as that reads,
     * and is copied here rather than corrected: the rights grid can express it.
     */
    val departmentOnly: Boolean get() = !canViewMain && !canPostMain

    companion object {
        const val MAIN_TOOL = "main_budget_tool"
        const val DEPARTMENT_TOOL = "department_budget_tool"

        /**
         * An empty permission set means the tools call has not landed yet.
         * Treating that as "no access" would flash a refusal at everyone
         * while the call is in flight, so it stays [resolved] = false.
         */
        fun from(permissions: ProjectPermissions): BudgetViewer {
            if (permissions.tools.isEmpty()) return BudgetViewer()
            val main = permissions.tools.firstOrNull { it.identifier == MAIN_TOOL }
            val department = permissions.tools.firstOrNull { it.identifier == DEPARTMENT_TOOL }
            return BudgetViewer(
                canViewMain = main?.canView == true,
                canPostMain = main?.canPost == true,
                canViewDepartment = department?.canView == true,
                canPostDepartment = department?.canPost == true,
                canDownload = main?.canDownload == true || department?.canDownload == true,
                resolved = true,
            )
        }
    }
}
