package com.zillit.desktop.feature.callsheet.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking at the call sheet tool.
 *
 * Rights come from the production's permission grid (`callsheet_tool`).
 * Posting access is the "2nd AD" role: create, edit, send, publish and every
 * tab. Everyone else sees only the approvals that name them.
 */
data class CallSheetViewer(
    val userId: String = "",
    val displayName: String = "",
    val designation: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    /** False until the tools call has answered. */
    val ready: Boolean = false,
) {

    val isBlocked: Boolean get() = ready && !canView && !isAdmin

    /** Create / edit / send / publish — the 2nd AD's whole toolbar. */
    val canAuthor: Boolean get() = isAdmin || canPost

    companion object {
        const val TOOL_IDENTIFIER = "callsheet_tool"

        /**
         * [ProjectPermissions.Empty] — before the tools call returns — answers
         * false to everything, which would read as a denial. An empty set
         * resolves to "not yet known" instead, and [ready] tells them apart.
         */
        fun from(
            permissions: ProjectPermissions,
            userId: String,
            displayName: String,
            designation: String,
        ): CallSheetViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return CallSheetViewer(
                    userId = userId,
                    displayName = displayName,
                    designation = designation,
                    ready = false,
                )
            }
            return CallSheetViewer(
                userId = userId,
                displayName = displayName,
                designation = designation,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
