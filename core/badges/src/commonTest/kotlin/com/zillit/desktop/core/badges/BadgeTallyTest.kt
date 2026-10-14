package com.zillit.desktop.core.badges

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS's composition — every unread row of a section counts on it (`getNotificationRootObject`). */
class BadgeTallyTest {

    private fun row(
        id: String,
        section: String,
        tool: String = "",
        unit: String = "",
        read: Boolean = false,
        ignored: Boolean = false,
        deleted: Boolean = false,
    ) = NotificationRecord(
        id = id, projectId = "p", section = section, tool = tool, unit = unit,
        messageRead = read, ignored = ignored, deleted = deleted,
    )

    @Test
    fun `read, ignored and deleted rows count nowhere`() {
        val counts = tallyBadges(
            listOf(
                row("a", BadgeSections.SOS, read = true),
                row("b", BadgeSections.SOS, ignored = true),
                row("c", BadgeSections.SOS, deleted = true),
                row("d", BadgeSections.SOS),
            ),
        )
        assertEquals(1, counts.section(BadgeSections.SOS))
    }

    /** iOS sums pending and expired invites into Home; an invite with no end is pending, not dropped. */
    @Test
    fun `home counts units, and every calendar invite, ended or not`() {
        val counts = tallyBadges(
            listOf(
                row("n1", BadgeSections.HOME, unit = "u1"),
                row("n2", BadgeSections.HOME, unit = "u1"),
                row("n3", BadgeSections.HOME, unit = "u2"),
                row("cal-expired", BadgeSections.HOME, tool = "calendar_label", unit = "cal").copy(calendarExpiry = 9L),
                row("cal-no-end", BadgeSections.HOME, tool = "calendar_label", unit = "cal"),
            ),
        )
        assertEquals(5, counts.section(BadgeSections.HOME))
        assertEquals(2, counts.unit("u1"))
        assertEquals(1, counts.unit("u2"))
        assertEquals(2, counts.unit("cal"))
    }

    /** iOS `ToolType.adDashboard` is a tool like any other — its rows are on the Tools tab. */
    @Test
    fun `tools count by grid identifier, the ad dashboard included`() {
        val counts = tallyBadges(
            listOf(
                row("t1", BadgeSections.TOOLS, tool = "call_sheet_label"),
                row("t2", BadgeSections.TOOLS, tool = "call_sheet_label"),
                row("t3", BadgeSections.TOOLS, tool = "location_tool_label", unit = "loc-unit"),
                row("t4", BadgeSections.TOOLS, tool = "ad_dashboard_label"),
            ),
        )
        assertEquals(4, counts.section(BadgeSections.TOOLS))
        assertEquals(2, counts["callsheet_tool"])
        assertEquals(1, counts["location_tool"])
        assertEquals(1, counts["ad_dashboard_tool"])
    }

    /** iOS `FilmToolsViewController`: one tile, every list it opens. */
    @Test
    fun `a tile that opens several lists wears all of their counts`() {
        val counts = tallyBadges(
            listOf(
                row("full", BadgeSections.TOOLS, tool = "script_distribution_label"),
                row("page", BadgeSections.TOOLS, tool = "script_distribution_pages_tool_label", unit = "12"),
                row("sched", BadgeSections.TOOLS, tool = "schedule_distribution_label"),
                row("spage", BadgeSections.TOOLS, tool = "schedule_distribution_pages_tool_label", unit = "12"),
                row("oneline", BadgeSections.TOOLS, tool = "schedule_oneline_label"),
                row("mail", BadgeSections.EMAIL, unit = "INBOX"),
            ),
        )
        assertEquals(2, counts["script_distribution_tool"])
        assertEquals(1, counts["script_distribution_pages_tool"], "the pages tab keeps its own count")
        assertEquals(3, counts["schedule_distribution_tool"])
        assertEquals(1, counts["schedule_oneline_tool"])
        assertEquals(1, counts["email_tool"])
        assertEquals(5, counts.section(BadgeSections.TOOLS), "the Tools total counts each row once")
    }

    /** iOS `DocuSignBadgeReader.fetchTotalBadge` and `refreshDriveToolBadge`. */
    @Test
    fun `e-signature and drive tiles count what iOS's tiles count`() {
        val esign = "e_signature_label"
        val counts = tallyBadges(
            listOf(
                row("sign1", BadgeSections.TOOLS, tool = esign, unit = "sign_documents_label").copy(level3 = "env1"),
                row("sign2", BadgeSections.TOOLS, tool = esign, unit = "sign_documents_label").copy(level3 = "env1"),
                row("done", BadgeSections.TOOLS, tool = esign, unit = "manage_envelopes_label")
                    .copy(level1 = "completed", level3 = "env1"),
                row("sent", BadgeSections.TOOLS, tool = esign, unit = "manage_envelopes_label")
                    .copy(level1 = "sent", level3 = "env2"),
                row("lost", BadgeSections.TOOLS, tool = esign, unit = "sign_documents_label"),
                row("folder", BadgeSections.TOOLS, tool = "drive_label", unit = "drive_folder_label"),
                row("file", BadgeSections.TOOLS, tool = "drive_label", unit = "drive_file_label"),
                row("other", BadgeSections.TOOLS, tool = "drive_label", unit = "drive_other_label"),
            ),
        )
        // env1 once across the two units, at its larger count; `sent` and the envelope-less row are out.
        assertEquals(2, counts["e_signature_tool"])
        assertEquals(2, counts["drive_tool"], "folder and file units only")
        assertEquals(8, counts.section(BadgeSections.TOOLS), "the Tools total still counts every row")
    }

    @Test
    fun `production report chat counts on its own tile, apart from the report`() {
        val counts = tallyBadges(
            listOf(
                row("chat", BadgeSections.TOOLS, tool = "production_report_tool_chat_label", unit = "pr-unit"),
                row("draft", BadgeSections.TOOLS, tool = "production_report_label", unit = "drafts"),
            ),
        )
        assertEquals(2, counts.section(BadgeSections.TOOLS))
        assertEquals(1, counts["production_report_chat_tool"])
        assertEquals(1, counts["production_report_tool"])
        assertEquals(1, counts.unit("pr-unit"))
    }

    @Test
    fun `a board tool's rows badge their units, so the tab strip inside the tool lights`() {
        val counts = tallyBadges(
            listOf(
                row("a1", BadgeSections.TOOLS, tool = "accounts_label", unit = "unit-a"),
                row("a2", BadgeSections.TOOLS, tool = "accounts_label", unit = "unit-a"),
                row("c1", BadgeSections.TOOLS, tool = "catering_label", unit = "unit-c"),
                row("noUnit", BadgeSections.TOOLS, tool = "map_label"),
            ),
        )
        assertEquals(2, counts.unit("unit-a"))
        assertEquals(1, counts.unit("unit-c"))
        assertEquals(0, counts.unit(""))
    }

    /** iOS's C&C tab is `chat` — the sum of every `cnc_label` row, call or chat. */
    @Test
    fun `cnc counts every row, calls and chats alike`() {
        val counts = tallyBadges(
            listOf(
                row("call", BadgeSections.CNC, tool = "call_label", unit = "call_missed_label"),
                row("dm", BadgeSections.CNC, tool = "chat_label", unit = "chat_member_label").copy(senderId = "peer"),
                row("dm-by-sender", BadgeSections.CNC, tool = "chat_label", unit = "chat_member_label")
                    .copy(sender = "peer"),
                row("room", BadgeSections.CNC, tool = "chat_label", unit = "chat_group_label").copy(chatRoomId = "r1"),
                row("orphan", BadgeSections.CNC, tool = "chat_label", unit = "chat_group_label"),
            ),
        )
        assertEquals(5, counts.section(BadgeSections.CNC))
    }

    /** iOS's Settings tab is `more` — every `settings_label` row, whatever its unit. */
    @Test
    fun `settings counts every row, by unit`() {
        val counts = tallyBadges(
            listOf(
                row("join", BadgeSections.SETTINGS, unit = "project_join_user_request_label"),
                row("profile", BadgeSections.SETTINGS, unit = "project_approve_profile_user_request_label"),
                row("deal", BadgeSections.SETTINGS, unit = "deal_memo_label"),
                row("other", BadgeSections.SETTINGS, unit = "something_else"),
            ),
        )
        assertEquals(4, counts.section(BadgeSections.SETTINGS))
        assertEquals(1, counts.unit("deal_memo_label"))
        assertEquals(1, counts.unit("something_else"))
    }

    @Test
    fun `each approval queue is counted by the unit the server files it under`() {
        // The server, the web and iOS all file profile changes under
        // `project_user_profile_change_request_label`; counting only Android's
        // name left the Approve User Profile row without a badge.
        val counts = tallyBadges(
            listOf(
                row("join", BadgeSections.SETTINGS, unit = BadgeSections.JOIN_REQUEST_UNIT),
                row("change1", BadgeSections.SETTINGS, unit = BadgeSections.PROFILE_CHANGE_UNIT),
                row("change2", BadgeSections.SETTINGS, unit = BadgeSections.PROFILE_CHANGE_UNIT),
            ),
        )
        assertEquals(3, counts.section(BadgeSections.SETTINGS))
        assertEquals(1, counts.unit(BadgeSections.JOIN_REQUEST_UNIT))
        assertEquals(2, counts.unit(BadgeSections.PROFILE_CHANGE_UNIT))
    }

    @Test
    fun `every other section is one per row`() {
        val counts = tallyBadges(
            listOf(
                row("g", BadgeSections.GLOBAL),
                row("e1", BadgeSections.EMAIL, unit = "inbox"),
                row("e2", BadgeSections.EMAIL, unit = "inbox"),
                row("blank", ""),
            ),
        )
        assertEquals(1, counts.section(BadgeSections.GLOBAL))
        assertEquals(2, counts.section(BadgeSections.EMAIL))
        assertEquals(3, counts.total)
    }

    @Test
    fun `a split scopes and groups by wire names`() {
        val po = "purchase_order_label"
        val rows = listOf(
            row("a", BadgeSections.TOOLS, tool = po, unit = po).copy(level1 = "queue"),
            row("b", BadgeSections.TOOLS, tool = po, unit = po).copy(level1 = "mine"),
            row("c", BadgeSections.TOOLS, tool = po, unit = "invoice_label").copy(level1 = "queue"),
            row("d", BadgeSections.TOOLS, tool = "sides_label"),
        )
        assertEquals(
            mapOf("queue" to 1, "mine" to 1),
            splitBadges(rows, BadgeDrilldownQuery(groupBy = "level_1", tool = po, unit = po)),
        )
        assertEquals(
            mapOf("purchase_order_label" to 3, "sides_label" to 1),
            splitBadges(rows, BadgeDrilldownQuery(groupBy = "tool", section = BadgeSections.TOOLS)),
        )
    }

    /** The picker's cards: the same composition, production by production. */
    @Test
    fun `the per-production tally is the same rule, split by production`() {
        val rows = listOf(
            row("a", BadgeSections.TOOLS, tool = "sides_label"),
            row("b", BadgeSections.TOOLS, tool = "sides_label").copy(projectId = "p2"),
            row("c", BadgeSections.TOOLS, tool = "ad_dashboard_label").copy(projectId = "p2"),
            row("d", BadgeSections.SOS),
        )

        val byProject = tallyByProject(rows)

        assertEquals(2, byProject.getValue("p").total)
        assertEquals(2, byProject.getValue("p2").total, "the dashboard row counts on the card as on the tab")
        assertEquals(tallyBadges(rows.filter { it.projectId == "p" }), byProject.getValue("p"))
    }
}
