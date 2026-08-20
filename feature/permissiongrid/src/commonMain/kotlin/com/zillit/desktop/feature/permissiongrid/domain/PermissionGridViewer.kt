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
    val isAdmin: Boolean = false,
    /** False only while the tools call is still out — see [from]. */
    val ready: Boolean = true,
) {
    companion object {
        /** The backend's `project_tools_list` identifier. */
        const val TOOL_IDENTIFIER = "permission_grid_tool"

        /**
         * Rights from the production's permission grid, with the desktop's
         * usual "empty grid = still loading" resolution: before the tools call
         * returns, every flag is false, and treating that as a refusal would
         * show "no access" for the moment the window takes to open.
         */
        fun from(permissions: ProjectPermissions): PermissionGridViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return PermissionGridViewer(ready = false)
            }
            val post = access.enabled && access.canPost
            return PermissionGridViewer(
                canView = post || (access.enabled && access.canView),
                canPost = post,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
