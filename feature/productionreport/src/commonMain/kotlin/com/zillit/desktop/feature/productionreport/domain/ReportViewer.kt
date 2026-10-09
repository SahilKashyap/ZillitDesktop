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
    /**
     * Viewing rights on the unit chat, which the backend made a tool of its
     * own (`production_report_chat_tool`, Oct 2026): its routes refuse a
     * viewer without them, whatever their report rights say. True until the
     * tools call answers, like [canView].
     */
    val canViewChat: Boolean = true,
    val isAdmin: Boolean = false,
    /** False until the tools call has answered. */
    val ready: Boolean = false,
) {

    val isBlocked: Boolean get() = ready && !canView && !isAdmin

    /** Create / edit / send / publish — the 2nd AD's whole toolbar. */
    val canAuthor: Boolean get() = isAdmin || canPost

    companion object {
        const val TOOL_IDENTIFIER = "production_report_tool"
        const val CHAT_TOOL_IDENTIFIER = "production_report_chat_tool"

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
            /** The tool whose rights open the unit chat; null for a report with no chat. */
            chatToolIdentifier: String? = null,
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
                // Admins pass the rights but not the production's switch: a
                // chat tool that is off, or not yet issued, is no chat.
                canViewChat = chatToolIdentifier?.let(permissions::canView) == true,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
