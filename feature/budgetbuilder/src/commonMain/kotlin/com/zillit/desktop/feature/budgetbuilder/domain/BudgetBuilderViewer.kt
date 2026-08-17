package com.zillit.desktop.feature.budgetbuilder.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is asking for the Budget Builder, and whether they get it.
 *
 * ## Deliberately not gated on the accounts department
 *
 * Line producers build budgets, not just the accounts team, so entry is
 * governed purely by the tool's own view/posting rights — the same rule every
 * other film tool follows. This mirrors the web's `useBudgetBuilderRights`
 * word for word, including the part it states in a comment: no department
 * check, and the preset seeds these rights from `department_producers`.
 *
 * ## Rights are the web hook's, not [ProjectPermissions]' derived ones
 *
 * The web computes `canView = posting_access || view_access` — posting alone
 * is enough to enter. [ProjectPermissions.canPost] instead requires the view
 * flag as a precondition, so building this viewer from the derived helpers
 * would deny a user the web admits. The raw flags are read via
 * [ProjectPermissions.access] and combined the web's way.
 *
 * One deliberate deviation: [ProjectPermissions.access] applies the desktop's
 * admin bypass (an admin passes every access check on a tool the production
 * has enabled), where the web hook reads the rights list alone. The desktop
 * convention wins — one tool stricter for admins than every other would read
 * as a bug — and the server remains the real gate either way.
 */
data class BudgetBuilderViewer(
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val isAdmin: Boolean = false,
    /** False until the production's tools call has answered. */
    val ready: Boolean = false,
) {

    /**
     * Only a *resolved* denial blocks. Before rights arrive nothing is
     * denied — the server enforces the real gate on every call regardless,
     * and flashing "no access" at every user while the tools call is in
     * flight is the alternative.
     */
    val isBlocked: Boolean get() = ready && !canView

    companion object {
        /** The backend's `project_tools_list` identifier for this tool. */
        const val TOOL_IDENTIFIER = "budget_builder_tool"

        /**
         * Reads this person's rights from the production's permission grid.
         *
         * [ProjectPermissions.Empty] — the state before the tools call
         * returns — answers false to everything, which would read as a
         * denial. An empty set therefore resolves to "not yet known", and
         * [ready] is what tells the two apart.
         */
        fun from(permissions: ProjectPermissions): BudgetBuilderViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return BudgetBuilderViewer(ready = false)
            }
            val post = access.enabled && access.canPost
            return BudgetBuilderViewer(
                canView = post || (access.enabled && access.canView),
                canPost = post,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
