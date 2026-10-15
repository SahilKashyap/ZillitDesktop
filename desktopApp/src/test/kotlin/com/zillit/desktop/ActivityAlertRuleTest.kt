package com.zillit.desktop

import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.feature.notifications.domain.ActivityBanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which `notification:save` records ActivityAlerts banners, and under which
 * switch.
 *
 * The rule is pinned by test because the three fields it reads are easy to
 * confuse: the wire has only seven sections (`BadgeSections`), while a
 * budget's chat and a post on a board are a TOOL and an ACTION. Naming either
 * as a section is a mistake that cannot be seen from the outside — the earlier
 * `main_budget_label` / `department_budget_label` entries matched nothing and
 * every budget chat message bannered twice, once from the chat alert and once
 * from here.
 */
class ActivityAlertRuleTest {

    private fun row(
        section: String,
        tool: String = "default_tool",
        action: String = "something_happened",
    ) = ActivityBanner(
        id = "n1",
        section = section,
        tool = tool,
        action = action,
        area = "Area",
        body = "Something happened",
    )

    @Test
    fun `the areas with an alert of their own are left to it`() {
        assertTrue(coveredByItsOwnAlert(row("email_label")))
        assertTrue(coveredByItsOwnAlert(row("group_refresh")))
    }

    @Test
    fun `an ordinary chat record is left to the chat alerts`() {
        for (action in listOf(
            "cnc_new_chat",
            "cnc_new_chat_private",
            "cnc_new_chat_group",
            "cnc_new_chat_group_text",
            "cnc_new_chat_with_file_private",
            "cnc_new_chat_with_file_group",
            "cnc_reaction_message_personal",
            "cnc_new_call",
            // An action this rule has never heard of stays quiet too: the chat
            // vocabulary is long, and a guess that lets one through would
            // banner the most frequent event in the app twice.
            "cnc_something_nobody_has_shipped_yet",
        )) {
            assertTrue(coveredByItsOwnAlert(row("cnc_label", action = action)), "should stay quiet: $action")
        }
    }

    @Test
    fun `a missed call and the life of a room banner here`() {
        for (action in listOf(
            "cnc_missed_call_private",
            "cnc_missed_call_group",
            "cnc_new_chat_group_created",
            "cnc_updated_chat_group",
            "cnc_chat_room_member_leaving",
            "cnc_chat_room_removed",
            "cnc_chat_group_name_change",
            "cnc_added_admin_chat_group",
            "cnc_removed_admin_chat_group",
        )) {
            assertFalse(coveredByItsOwnAlert(row("cnc_label", action = action)), "should banner: $action")
        }
    }

    @Test
    fun `a missed call answers to the calls switch and a room event to messages`() {
        assertEquals(
            ZillitPreferences.NotifyCalls,
            row("cnc_label", action = "cnc_missed_call_private").switch(),
        )
        assertEquals(
            ZillitPreferences.NotifyMessages,
            row("cnc_label", action = "cnc_chat_room_removed").switch(),
        )
    }

    @Test
    fun `every other area banners here`() {
        for (section in listOf("tools_label", "global_label", "sos_label", "settings_label")) {
            assertFalse(coveredByItsOwnAlert(row(section)), "section should banner: $section")
        }
    }

    @Test
    fun `a budget chat message is left to the chat alert, by its tool`() {
        // section is tools_label: the budget is a tool, never a section.
        assertTrue(coveredByItsOwnAlert(row("tools_label", tool = "main_budget_tool_chat_label")))
        assertTrue(coveredByItsOwnAlert(row("tools_label", tool = "department_budget_tool_chat_label")))
    }

    @Test
    fun `a budget notification that is not chat still banners`() {
        assertFalse(coveredByItsOwnAlert(row("tools_label", tool = "main_budget_label")))
        assertFalse(coveredByItsOwnAlert(row("tools_label", tool = "department_budget_label")))
    }

    @Test
    fun `a post on a notice board is left to the Home alert`() {
        for (action in listOf(
            "home_new_unit_chat",
            "home_new_unit_chat_message",
            "home_new_unit_chat_file_received",
            "home_new_unit_file_received",
        )) {
            assertTrue(
                coveredByItsOwnAlert(row("home_label", action = action)),
                "post should be left to UpdateAlerts: $action",
            )
        }
    }

    @Test
    fun `the rest of Home banners here, as it does on the web`() {
        for (action in listOf(
            "home_comment_chat_message",
            "home_reply_chat_message",
            "home_deleted_chat",
            "home_new_unit_created_message",
            "home_new_unit_updated_message",
            "home_new_unit_deleted_message",
            "calendar_invite_users",
            "calendar_update",
            "calendar_accepted",
            "calendar_rejected",
        )) {
            assertFalse(
                coveredByItsOwnAlert(row("home_label", action = action)),
                "Home activity should banner: $action",
            )
        }
    }

    @Test
    fun `Home records answer to the Home switch, everything else to Activity`() {
        assertEquals(ZillitPreferences.NotifyUpdates, row("home_label", action = "home_comment_chat_message").switch())
        assertEquals(ZillitPreferences.NotifyActivity, row("tools_label").switch())
        assertEquals(ZillitPreferences.NotifyActivity, row("global_label").switch())
    }
}
