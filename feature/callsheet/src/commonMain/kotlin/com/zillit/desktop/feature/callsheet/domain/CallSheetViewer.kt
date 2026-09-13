package com.zillit.desktop.feature.callsheet.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking at the call sheet tool.
 *
 * Posting rights on `callsheet_tool` make the "2nd AD": create, edit, send,
 * publish, templates and the Permission tab. A project admin without them is
 * NOT an author here — the web's `toolDisplayName` grants admins authorship of
 * the production report alone (`ADMIN_AUTHORS`), and the service refuses a
 * call-sheet write without posting rights (ZL-18241). Everyone else sees the
 * drafts they are asked to comment on, the approvals that name them, and what
 * was published.
 */
data class CallSheetViewer(
    val userId: String = "",
    val displayName: String = "",
    val designation: String = "",
    val canView: Boolean = true,
    /** `callsheet_tool.posting_access` as issued — no admin bypass. */
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    /** `permission_grid_tool.view_access` as issued — opens the Permission tab. */
    val canViewGrid: Boolean = false,
    /** `permission_grid_tool.posting_access` as issued — enables its checkboxes. */
    val canEditGrid: Boolean = false,
    /** False until the tools call has answered. */
    val ready: Boolean = false,
) {

    val isBlocked: Boolean get() = ready && !canView && !canPost && !isAdmin

    /** The web's `is2ndAD`. */
    val isPoster: Boolean get() = canPost

    companion object {
        const val TOOL_IDENTIFIER = "callsheet_tool"
        const val GRID_IDENTIFIER = "permission_grid_tool"

        /**
         * [ProjectPermissions.Empty] — before the tools call returns — answers
         * false to everything, which would read as a denial. An empty set
         * resolves to "not yet known" instead, and [ready] tells them apart.
         *
         * Rights are read as issued ([ProjectPermissions.tools]); the admin
         * bypass [ProjectPermissions.access] applies is exactly what this tool
         * must not inherit.
         */
        fun from(
            permissions: ProjectPermissions,
            userId: String,
            displayName: String,
            designation: String,
        ): CallSheetViewer {
            val issued = permissions.tools
            if (issued.isEmpty()) {
                return CallSheetViewer(userId = userId, displayName = displayName, designation = designation)
            }
            val sheet = issued.firstOrNull { it.identifier == TOOL_IDENTIFIER }
            val grid = issued.firstOrNull { it.identifier == GRID_IDENTIFIER }
            return CallSheetViewer(
                userId = userId,
                displayName = displayName,
                designation = designation,
                canView = sheet?.let { it.enabled && it.canView } ?: permissions.isAdmin,
                canPost = sheet?.let { it.enabled && it.canPost } == true,
                isAdmin = permissions.isAdmin,
                canViewGrid = grid?.let { it.enabled && it.canView } == true,
                canEditGrid = grid?.let { it.enabled && it.canPost } == true,
                ready = true,
            )
        }
    }
}
