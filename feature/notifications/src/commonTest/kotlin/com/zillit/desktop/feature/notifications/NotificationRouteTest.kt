package com.zillit.desktop.feature.notifications

import com.zillit.desktop.feature.notifications.domain.NotificationDestination
import com.zillit.desktop.feature.notifications.domain.NotificationTarget
import com.zillit.desktop.feature.notifications.domain.matchNotificationRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The matcher against the wire's own vocabulary: the seven sections the ledger
 * groups by, the tool names `ToolWireNames` translates, and the braced label
 * path a row carries when it says nothing else.
 */
class NotificationRouteTest {

    @Test
    fun `each app section lands on its own page`() {
        assertEquals(section("/cnc"), route(section = "cnc_label", action = "cnc_new_chat_group_created"))
        assertEquals(section("/email"), route(section = "email_label"))
        assertEquals(section("/home"), route(section = "home_label", unit = "7"))
        assertEquals(section("/settings"), route(section = "settings_label"))
        assertEquals(section("/sos"), route(section = "sos_label"))
    }

    @Test
    fun `a tools row lands on the tool, under the name the grid gives it`() {
        // The pair no suffix rule reaches: the wire says `call_sheet_label`,
        // the grid says `callsheet_tool`.
        assertEquals(
            NotificationDestination.FilmTool("callsheet_tool"),
            route(section = "tools_label", tool = "call_sheet_label", path = "{tools_label/call_sheet_label}"),
        )
        // The `_tool` in the middle, which the suffix rule must leave alone.
        assertEquals(
            NotificationDestination.FilmTool("location_tool"),
            route(section = "tools_label", tool = "location_tool_label"),
        )
        // A budget's chat opens the budget, as it does on the phones' tile.
        assertEquals(
            NotificationDestination.FilmTool("main_budget_tool"),
            route(section = "tools_label", tool = "main_budget_tool_chat_label"),
        )
    }

    @Test
    fun `the braced path stands in for a section or a tool the row left out`() {
        assertEquals(
            NotificationDestination.FilmTool("callsheet_tool"),
            route(path = "{tools_label/call_sheet_label}"),
        )
        assertEquals(section("/home"), route(path = "{home_label}"))
        // A path that starts at the tool, with the section carried separately.
        assertEquals(
            NotificationDestination.FilmTool("location_tool"),
            route(section = "tools_label", path = "{location_tool_label/folder_7}"),
        )
    }

    @Test
    fun `nothing deeper than the tool is read off the path`() {
        // `{tools_label/call_sheet_label/42}` is tool-then-unit: the unit is a
        // level, not another tool, and must not become one.
        assertEquals(
            NotificationDestination.FilmTool("callsheet_tool"),
            route(section = "tools_label", path = "{tools_label/call_sheet_label/42}"),
        )
    }

    @Test
    fun `a row that points nowhere stays quiet`() {
        assertNull(route(), "an empty target")
        assertNull(route(section = "a_section_from_the_future"), "a section this build does not know")
        assertNull(route(section = "global_label"), "the bell's own segment")
        assertNull(route(section = "tools_label"), "a tools row naming no tool")
        // A path of nothing but the section leaves a tools row with no tool.
        assertNull(route(section = "tools_label", path = "{tools_label}"), "a path naming no tool")
    }

    @Test
    fun `an unknown tool still opens its tool, and the host decides it is unknown`() {
        // The catalogue, not the matcher, knows which tools this build can
        // draw: a name shipped after this build comes through as itself.
        assertEquals(
            NotificationDestination.FilmTool("time_machine_tool"),
            route(section = "tools_label", tool = "time_machine_label"),
        )
    }

    private fun section(path: String) = NotificationDestination.Section(path)

    private fun route(
        path: String = "",
        section: String = "",
        tool: String = "",
        unit: String = "",
        action: String = "",
    ) = matchNotificationRoute(
        NotificationTarget(path = path, section = section, tool = tool, unit = unit, action = action),
    )
}
