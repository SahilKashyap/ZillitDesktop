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
     * Posting rights in Production Report Chat, its own tool since Oct 2026
     * (`production_report_chat_tool`). Publishing and Attach to Published
     * post into that chat, and its routes refuse a writer without them,
     * whatever their report rights say — the web's `usePrChatRights().canPost`.
     */
    val canPostChat: Boolean = false,
    val isAdmin: Boolean = false,
    /** False until the tools call has answered. */
    val ready: Boolean = false,
) {

    val isBlocked: Boolean get() = ready && !canView && !isAdmin

    /** Create / edit / send / publish — the 2nd AD's whole toolbar. */
    val canAuthor: Boolean get() = isAdmin || canPost

    companion object {
        /**
         * `resolvePrChatRights`: the chat tool's rights (admins pass them, not
         * the production's switch — a chat tool off or not yet issued is no
         * chat), and a report row whose `unit_id` addresses the chat at all.
         */
        fun chatPostingRight(permissions: ProjectPermissions, chatTool: String, reportTool: String): Boolean =
            !permissions.access(reportTool).unitId.isNullOrBlank() && permissions.canPost(chatTool)

        const val TOOL_IDENTIFIER = "production_report_tool"
        const val CHAT_TOOL_IDENTIFIER = "production_report_chat_tool"
        const val CHAT_TOOL_LABEL = "production_report_tool_chat_label"

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
            /** The tool whose rights let a report post into its chat; null for a report with no chat. */
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
                canPostChat = chatToolIdentifier?.let { chatPostingRight(permissions, it, toolIdentifier) } == true,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
