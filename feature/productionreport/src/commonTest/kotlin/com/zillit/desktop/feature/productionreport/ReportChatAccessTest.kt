package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The unit chat's rights since the backend split it into
 * `production_report_chat_tool` (Oct 2026): read off that tool, never the
 * report's, with the admin bypass on rights but not on the production's switch.
 */
class ReportChatAccessTest {

    private val report = ToolAccess("production_report_tool", unitId = "pr-unit", canView = true, canPost = true)
    private val chat = ToolAccess("production_report_chat_tool", canView = true, canPost = true)

    private fun viewer(vararg tools: ToolAccess, admin: Boolean = false, kind: ReportKind = ReportKind.Production) =
        ReportViewer.from(
            permissions = ProjectPermissions(tools.toList(), isAdmin = admin),
            userId = "me",
            displayName = "Me",
            designation = "2nd AD",
            toolIdentifier = kind.toolIdentifier,
            chatToolIdentifier = kind.chatToolIdentifier,
        )

    @Test
    fun `chat rights come from the chat tool, not the report`() {
        assertTrue(viewer(report, chat).canViewChat)
        assertFalse(viewer(report, chat.copy(canView = false)).canViewChat, "report rights do not open the chat")
        val chatOnly = viewer(report.copy(canView = false, canPost = false), chat)
        assertTrue(chatOnly.canViewChat)
        assertFalse(chatOnly.canView)
    }

    @Test
    fun `a chat tool that is off or not issued is no chat, for admins too`() {
        assertFalse(viewer(report, chat.copy(enabled = false)).canViewChat)
        assertFalse(viewer(report).canViewChat, "missing until the project is backfilled")
        assertFalse(viewer(report, chat.copy(enabled = false), admin = true).canViewChat)
        assertTrue(viewer(report, chat.copy(canView = false), admin = true).canViewChat, "admins pass the rights")
    }

    @Test
    fun `only the production report has a chat`() {
        assertEquals("production_report_chat_tool", ReportKind.Production.chatToolIdentifier)
        assertEquals(null, ReportKind.Ad.chatToolIdentifier)
        assertFalse(viewer(ToolAccess("ad_report_tool", canView = true), chat, kind = ReportKind.Ad).canViewChat)
    }
}
