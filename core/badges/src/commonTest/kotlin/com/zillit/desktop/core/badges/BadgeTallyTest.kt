package com.zillit.desktop.core.badges

import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's composition rules, one per surface — `CommonBadgesHandler.computeBadgesCount`. */
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

    @Test
    fun `home counts units, and a calendar row only when its event has an end`() {
        val counts = tallyBadges(
            listOf(
                row("n1", BadgeSections.HOME, unit = "u1"),
                row("n2", BadgeSections.HOME, unit = "u1"),
                row("n3", BadgeSections.HOME, unit = "u2"),
                row("cal-pending", BadgeSections.HOME, tool = "calendar_label", unit = "cal").copy(calendarEnd = 9L),
                row("cal-ack", BadgeSections.HOME, tool = "calendar_label", unit = "cal"),
            ),
        )
        assertEquals(4, counts.section(BadgeSections.HOME))
        assertEquals(2, counts.unit("u1"))
        assertEquals(1, counts.unit("u2"))
        assertEquals(1, counts.unit("cal"))
    }

    @Test
    fun `tools count by grid identifier and never the ad dashboard`() {
        val counts = tallyBadges(
            listOf(
                row("t1", BadgeSections.TOOLS, tool = "call_sheet_label"),
                row("t2", BadgeSections.TOOLS, tool = "call_sheet_label"),
                row("t3", BadgeSections.TOOLS, tool = "location_tool_label", unit = "loc-unit"),
                row("t4", BadgeSections.TOOLS, tool = "ad_dashboard_label"),
            ),
        )
        assertEquals(3, counts.section(BadgeSections.TOOLS))
        assertEquals(2, counts["callsheet_tool"])
        assertEquals(1, counts["location_tool"])
        assertEquals(0, counts["ad_dashboard_tool"])
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

    @Test
    fun `cnc is missed calls plus chat rows that belong to a conversation`() {
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
        assertEquals(4, counts.section(BadgeSections.CNC))
    }

    @Test
    fun `settings counts only the approval and onboarding units`() {
        val counts = tallyBadges(
            listOf(
                row("join", BadgeSections.SETTINGS, unit = "project_join_user_request_label"),
                row("profile", BadgeSections.SETTINGS, unit = "project_approve_profile_user_request_label"),
                row("deal", BadgeSections.SETTINGS, unit = "deal_memo_label"),
                row("other", BadgeSections.SETTINGS, unit = "something_else"),
            ),
        )
        assertEquals(3, counts.section(BadgeSections.SETTINGS))
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
}
