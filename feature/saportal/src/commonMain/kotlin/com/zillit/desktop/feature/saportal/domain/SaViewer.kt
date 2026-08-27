package com.zillit.desktop.feature.saportal.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking at the artiste portal.
 *
 * Two tool identifiers open the same surface: the web mounts it at
 * `/film-tools/supporting-artists` behind `supporting_artistes_extras_tool`,
 * while Android injects the same portal as `sa_portal_tool`. A production
 * granting either should see it, so both are checked.
 *
 * There is no posting right to speak of — everything here is the artiste's
 * own record, and the server decides what they may change. What this gates
 * is whether the tool opens at all.
 */
data class SaViewer(
    val userId: String = "",
    val displayName: String = "",
    val canView: Boolean = true,
    val ready: Boolean = false,
) {
    /** Only once rights are known: an unresolved viewer is never denied. */
    val isBlocked: Boolean get() = ready && !canView

    companion object {
        const val PORTAL_TOOL = "sa_portal_tool"
        const val EXTRAS_TOOL = "supporting_artistes_extras_tool"

        fun from(
            permissions: ProjectPermissions,
            userId: String,
            displayName: String,
        ): SaViewer {
            val known = permissions.access(PORTAL_TOOL).enabled ||
                permissions.access(EXTRAS_TOOL).enabled ||
                permissions.visibleTools.isNotEmpty()
            if (!known) {
                return SaViewer(userId = userId, displayName = displayName, ready = false)
            }
            return SaViewer(
                userId = userId,
                displayName = displayName,
                canView = permissions.canView(PORTAL_TOOL) || permissions.canView(EXTRAS_TOOL),
                ready = true,
            )
        }
    }
}
