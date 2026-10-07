package com.zillit.desktop.feature.permissiongrid.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * What this person may do with the rights grid itself.
 *
 * Reading the grid and changing it are separate rights on the same tool, so a
 * coordinator can be shown who may post where without being able to alter it.
 */
data class PermissionGridViewer(
    val canView: Boolean = false,
    val canPost: Boolean = false,
    /** Gates the Default Grid's Excel export (ZL-15045). */
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    /** False only while the tools call is still out — see [from]. */
    val ready: Boolean = true,
) {
    companion object {
        /** The backend's `project_tools_list` identifier. */
        const val TOOL_IDENTIFIER = "permission_grid_tool"

        /**
         * Rights from the production's permission grid.
         *
         * Before the tools call returns every flag is false, and treating that
         * as a refusal would show "no access" for the moment the window takes
         * to open — so an empty grid is "not known yet".
         *
         * Once known, only an **explicit** refusal shuts the door, as on the
         * web (`AccessGrid.jsx`): `permission_grid_tool` is a group-admin tool,
         * so somebody promoted a moment ago — the person this screen exists
         * for, arriving from Admin Settings — has no entry for it at all yet.
         * Absent opens the grid; it does not open editing to a non-admin.
         */
        fun from(permissions: ProjectPermissions): PermissionGridViewer {
            val issued = permissions.tools.firstOrNull { it.identifier == TOOL_IDENTIFIER }
            if (issued == null) {
                if (permissions.tools.isEmpty()) return PermissionGridViewer(ready = false)
                return PermissionGridViewer(
                    canView = true,
                    canPost = permissions.isAdmin,
                    canDownload = permissions.isAdmin,
                    isAdmin = permissions.isAdmin,
                )
            }
            val access = permissions.access(TOOL_IDENTIFIER)
            val view = access.enabled && (access.canView || access.canPost)
            return PermissionGridViewer(
                canView = view,
                canPost = view && access.canPost,
                canDownload = view && access.canDownload,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
