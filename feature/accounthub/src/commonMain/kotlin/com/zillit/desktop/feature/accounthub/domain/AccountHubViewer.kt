package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking at the Account Hub, and what the console will let them reach.
 *
 * ## Two gates, not one
 *
 * The hub is a console over a dozen areas, and entry is decided twice:
 *
 *  1. **Tool access** — `view_access` / `posting_access` on
 *     `account_hub_tool`, and on each hosted tool's own identifier. This is
 *     what an admin edits in the permission grid.
 *  2. **Department** — whether this person is in accounts. That is not a
 *     right; it is what distinguishes the accountant's console from a
 *     department user's short list of the three areas they raise spend in.
 *
 * Conflating them is how the web's own shell went wrong twice: every hub route
 * was gated on department alone, so revoking tool access mid-session left
 * somebody sitting inside the finance module (ZL-20533).
 *
 * ## The soft default
 *
 * Before the tools call answers, [ready] is false and access reads permissive —
 * the same choice the other film tools make here. A hard default flashes "no
 * access" at every user on every open while the call is in flight, and the
 * server enforces the real gate on every read and write regardless.
 */
data class AccountHubViewer(
    val userId: String = "",
    /**
     * In the accounts department.
     *
     * The web derives this from `department_identifier` containing "accounts";
     * it is passed in already resolved because department membership is the
     * session's to know, not this module's to fetch.
     */
    val isAccountant: Boolean = false,
    val isAdmin: Boolean = false,
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val canDownload: Boolean = true,
    /** False until the tools call has answered. See the class doc. */
    val ready: Boolean = false,
    /**
     * Which of the hosted tools this person may open.
     *
     * Held as a set rather than re-read per row so the sidebar does not ask the
     * permission grid the same question once per frame.
     */
    val viewableTools: Set<String> = emptySet(),
) {

    /** Whether the console should refuse to render. Only once rights are known. */
    val isBlocked: Boolean get() = ready && !canView

    /**
     * Whether writes are hidden.
     *
     * Reads across the hub are open to any authenticated user — the cost report
     * and every line-item picker need the chart of accounts — while writes are
     * accountant-only and the server 403s otherwise. Offering a save that will
     * be refused is worse than not offering it.
     */
    val canEdit: Boolean get() = canPost && (isAccountant || isAdmin)

    /**
     * Whether a vendor may be added.
     *
     * Anyone who may post, not only the accounts team. The web offers Add Vendor
     * to every viewer and gives department users an "Added by Me" tab to find
     * what they raised; gating it on [canEdit] left a department user with a tab
     * for vendors they had no way to create.
     */
    val mayAddVendor: Boolean get() = canPost

    /**
     * Whether this particular vendor may be edited or deleted.
     *
     * The web's own rule, per row: the accounts team may change any vendor, and
     * the person who added one may change theirs. The blank-id check is
     * load-bearing — a vendor with no recorded adder and a viewer with no id
     * would otherwise compare equal and hand every row to every viewer.
     */
    fun mayModifyVendor(vendor: Vendor): Boolean {
        if (!canPost) return false
        if (isAccountant || isAdmin) return true
        return userId.isNotBlank() && vendor.addedBy == userId
    }

    /**
     * Whether the operations the service reserves for the accounts department
     * may be performed.
     *
     * Stricter than [canEdit], and the difference is not cosmetic: these answer
     * `403 {"message":"accountant_access_only"}` to anyone outside accounts —
     * *including a production admin*. Both measured on dev 2026-08-12:
     *
     *  - every **chart of accounts** write (and its tracking codes);
     *  - **verifying a vendor** — though *creating* one is allowed;
     *  - saving an **approval chain**.
     *
     * The project-settings slices carry no such rule and accept an admin's
     * writes, which is why one flag cannot serve the whole console. The web
     * agrees: its chart module reads `isAccountant` and nothing else, and it
     * calls verification "a Vendors-module accountant action".
     */
    val canActAsAccountant: Boolean get() = canPost && isAccountant

    /**
     * Whether someone already inside [tool] may stay there.
     *
     * Permissive by design: a hosted tool is reachable with *either* its own
     * right or the hub's, because the web's sidebar links to these unfiltered
     * and an accountant navigating the console would otherwise be thrown out of
     * a module they legitimately reached. For someone who arrived at a tool
     * directly the OR collapses to that tool's own right, so the gate still
     * bites.
     *
     * This is the entry question, **not** the listing one — see [mayList].
     */
    fun mayOpen(tool: String?): Boolean {
        if (!ready) return true
        if (tool == null || tool == TOOL_IDENTIFIER) return canView
        return tool in viewableTools || canView
    }

    /**
     * Whether [tool] should appear in the sidebar at all.
     *
     * Stricter than [mayOpen], and deliberately so. The two answer different
     * questions: entry asks "should this person be ejected from where they
     * are", and the permissive answer is right there. Listing asks "should this
     * be offered", and advertising a tool whose own window will greet them with
     * a no-access screen is worse than leaving it out — on the desktop that
     * hand-off opens a whole window to say no.
     *
     * The hub's own areas ([tool] null) ride on the hub's right, since they
     * have no identifier of their own.
     */
    fun mayList(tool: String?): Boolean {
        if (!ready) return true
        if (tool == null || tool == TOOL_IDENTIFIER) return canView
        return tool in viewableTools
    }

    companion object {
        const val TOOL_IDENTIFIER = "account_hub_tool"

        /** The identifier the web reads for accounts-department membership. */
        const val ACCOUNTS_DEPARTMENT = "accounts"

        /**
         * Whether a department identifier list puts this person in accounts.
         *
         * Substring rather than equality, matching the web — the identifier is
         * a compound string on some productions ("production_accounts").
         */
        fun isAccountsDepartment(identifiers: List<String>): Boolean =
            identifiers.any { it.contains(ACCOUNTS_DEPARTMENT, ignoreCase = true) }

        /**
         * Reads this person's rights out of the production's permission set.
         *
         * [ProjectPermissions.Empty] — the state before the tools call returns —
         * answers false to everything, which would deny the whole console. An
         * empty set is therefore read as "not yet known" and resolves to the
         * soft default rather than to a denial.
         */
        fun from(
            permissions: ProjectPermissions,
            userId: String,
            isAccountant: Boolean,
        ): AccountHubViewer {
            if (permissions.visibleTools.isEmpty() &&
                !permissions.access(TOOL_IDENTIFIER).enabled
            ) {
                return AccountHubViewer(
                    userId = userId,
                    isAccountant = isAccountant,
                    isAdmin = permissions.isAdmin,
                    ready = false,
                )
            }
            return AccountHubViewer(
                userId = userId,
                isAccountant = isAccountant,
                isAdmin = permissions.isAdmin,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                ready = true,
                viewableTools = permissions.visibleTools.map { it.identifier }.toSet(),
            )
        }
    }
}
