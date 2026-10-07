package com.zillit.desktop.feature.selectstills.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * What the Select Stills tool lets this reader do, from the tool's own row.
 *
 * ⚠ Deny until confirmed (as Tasks and Costumes & Set Sync do): the service
 * answers `403 still_kills_tool_not_enabled` for a production without the
 * tool, and a 403 anywhere in this app is read as "no longer a member". So
 * [canCall] gates every request and stays false until the rights list has
 * confirmed an enabled, viewable row.
 *
 * [canPost] here is only half of the answer: the view model also asks the
 * service (`GET /me`), and a capability needs both to agree.
 */
data class StillsViewer(
    /** The rights list has answered. Until it has, nothing may call the service. */
    val resolved: Boolean = false,
    val enabled: Boolean = false,
    val canView: Boolean = false,
    val canPost: Boolean = false,
) {
    val canCall: Boolean get() = resolved && enabled && canView

    companion object {
        const val TOOL = "still_kills_tool"

        /**
         * From the tool's row **as issued**, with no admin bypass: the service
         * decides on `view_access` / `posting_access` alone, and a write it
         * refuses with a 403 would read as "no longer a member".
         */
        fun from(permissions: ProjectPermissions): StillsViewer {
            // An empty list is "not answered yet", not a denial.
            if (permissions.tools.isEmpty()) return StillsViewer()
            val row = permissions.tools.firstOrNull { it.identifier == TOOL }
            return StillsViewer(
                resolved = true,
                enabled = row?.enabled == true,
                canView = row?.canView == true,
                canPost = row?.canPost == true,
            )
        }
    }
}
