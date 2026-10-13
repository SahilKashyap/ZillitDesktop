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
 * A report's right to post into Production Report Chat, its own tool since
 * the backend split (Oct 2026) — the web's `resolvePrChatRights().canPost`:
 * read off the chat tool, never the report's, with the admin bypass on rights
 * but not on the production's switch, and only while the report row carries
 * the `unit_id` that addresses the chat.
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
    fun `chat posting comes from the chat tool, not the report`() {
        assertTrue(viewer(report, chat).canPostChat)
        assertFalse(viewer(report, chat.copy(canPost = false)).canPostChat, "report posting does not post in the chat")
        assertFalse(viewer(report, chat.copy(canView = false)).canPostChat, "posting implies viewing")
    }

    @Test
    fun `a chat tool that is off or not issued is no chat, for admins too`() {
        assertFalse(viewer(report, chat.copy(enabled = false)).canPostChat)
        assertFalse(viewer(report).canPostChat, "missing until the project is backfilled")
        assertFalse(viewer(report, chat.copy(enabled = false), admin = true).canPostChat)
        assertTrue(viewer(report, chat.copy(canPost = false), admin = true).canPostChat, "admins pass the rights")
    }

    @Test
    fun `without the report row's unit nothing addresses the chat`() {
        assertFalse(viewer(report.copy(unitId = null), chat).canPostChat)
    }

    @Test
    fun `only the production report has a chat`() {
        assertEquals("production_report_chat_tool", ReportKind.Production.chatToolIdentifier)
        assertEquals(null, ReportKind.Ad.chatToolIdentifier)
        assertFalse(viewer(ToolAccess("ad_report_tool", canView = true), chat, kind = ReportKind.Ad).canPostChat)
    }
}
