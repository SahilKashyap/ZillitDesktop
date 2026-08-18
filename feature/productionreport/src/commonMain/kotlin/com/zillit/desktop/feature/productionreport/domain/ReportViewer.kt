package com.zillit.desktop.feature.productionreport.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking at the production report tool.
 *
 * Rights come from the production's permission grid (`callsheet_tool`).
 * Posting access is the "2nd AD" role: create, edit, send, publish and every
 * tab. Everyone else sees only the approvals that name them.
 */
data class ReportViewer(
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
        const val TOOL_IDENTIFIER = "production_report_tool"

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
            toolIdentifier: String = TOOL_IDENTIFIER,
        ): ReportViewer {
            val access = permissions.access(toolIdentifier)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return ReportViewer(
                    userId = userId,
                    displayName = displayName,
                    designation = designation,
                    ready = false,
                )
            }
            return ReportViewer(
                userId = userId,
                displayName = displayName,
                designation = designation,
                canView = permissions.canView(toolIdentifier),
                canPost = permissions.canPost(toolIdentifier),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
