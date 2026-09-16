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
    val canDownloadMain: Boolean = false,
    val canDownloadDepartment: Boolean = false,
    /**
     * A production admin. The web hides the view/download *count* items from
     * everyone else (`CommonBudget.jsx:1780,1788`), and lets an admin see
     * every department's budget rather than only their own
     * (`AddAndShowDepartmentList.jsx:212-232`).
     */
    val isAdmin: Boolean = false,
    /** False until `project/tools` has answered — not a denial. See [from]. */
    val resolved: Boolean = false,
) {

    /** Whether this person may open [mode] at all — the web bounces them to the grid otherwise. */
    fun canView(mode: BudgetMode): Boolean = when (mode) {
        BudgetMode.Main -> canViewMain
        BudgetMode.Department -> canViewDepartment
    }

    /** Posting rights on the tile that opened the tool, not on the other one. */
    fun canPost(mode: BudgetMode): Boolean = when (mode) {
        BudgetMode.Main -> canPostMain
        BudgetMode.Department -> canPostDepartment
    }

    /**
     * The web's `getDownloadRight` is the opened tile's `download_access`
     * (`FullBudget.jsx:59`, `DepartmentBudget.jsx:46`) — so a department
     * budget's download is gated by the department tile even for a document
     * whose type reads `main`.
     */
    fun canDownload(mode: BudgetMode): Boolean = when (mode) {
        BudgetMode.Main -> canDownloadMain
        BudgetMode.Department -> canDownloadDepartment
    }

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

    /**
     * Whether this person may take a copy of [type]'s file.
     *
     * Per budget, not per screen. iOS picks the right off the matching tool —
     * `MAIN_BUDGET_TOOL` for the production's budget, `DEPARTMENT_BUDGET_TOOL`
     * for a department's (`BudgetDetailVC`) — where this port OR-ed the two
     * into one flag, so download rights on a department budget also opened the
     * production's, which is the more sensitive of the two.
     */
    fun canDownload(type: BudgetType): Boolean = when (type) {
        BudgetType.Main -> canDownloadMain
        BudgetType.Department -> canDownloadDepartment
        // A budget whose type this port does not recognise is not one to hand
        // out. The opposite default to a *validation* — refusing to understand
        // a day type blocks a person's own timecard, where refusing to
        // understand a budget only withholds a file they can still ask for.
        BudgetType.Unknown -> false
    }

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
                // The admin exception is iOS's, on this tool specifically:
                // `guard (hasAdminAccess || hasDownloadAccess)`, with an offer
                // to ask an admin for the right otherwise.
                canDownloadMain = main?.canDownload == true || permissions.isAdmin,
                canDownloadDepartment = department?.canDownload == true || permissions.isAdmin,
                isAdmin = permissions.isAdmin,
                resolved = true,
            )
        }
    }
}
