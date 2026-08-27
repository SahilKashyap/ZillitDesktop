package com.zillit.desktop.feature.addashboard.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking at the AD dashboard.
 *
 * The web reads `view_access` and `posting_access` on `ad_dashboard_tool`
 * (`useAdAccess`) and hides every mutating control behind posting — an AD
 * without it can read the roster and the day but change neither.
 */
data class AdViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView

    /** Whether to say "you can look but not change" rather than leaving it a mystery. */
    val isReadOnly: Boolean get() = ready && !canPost

    companion object {
        const val TOOL_IDENTIFIER = "ad_dashboard_tool"

        fun from(permissions: ProjectPermissions, userId: String): AdViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return AdViewer(userId = userId, ready = false)
            }
            return AdViewer(
                userId = userId,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                ready = true,
            )
        }
    }
}
