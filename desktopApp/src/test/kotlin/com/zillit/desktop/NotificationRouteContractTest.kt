package com.zillit.desktop

import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.chat.ui.CNC_PATH
import com.zillit.desktop.feature.email.ui.EMAIL_PATH
import com.zillit.desktop.feature.home.domain.ToolCatalogue
import com.zillit.desktop.feature.home.ui.HOME_PATH
import com.zillit.desktop.feature.notifications.domain.NotificationDestination
import com.zillit.desktop.feature.notifications.domain.NotificationSectionPaths
import com.zillit.desktop.feature.notifications.domain.NotificationTarget
import com.zillit.desktop.feature.notifications.domain.matchNotificationRoute
import com.zillit.desktop.feature.settings.ui.SETTINGS_PATH
import com.zillit.desktop.feature.sos.ui.SOS_PATH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A clicked bell row and the module it lands on.
 *
 * `feature:notifications` depends on none of the modules it routes to, so it
 * carries their paths as strings of its own — the same arrangement
 * [AdminRouteContractTest] guards for Admin Settings, and the same risk:
 * rename a provider's path and the rows would point nowhere, with nothing to
 * say so until somebody clicked one. This is the only module that sees both
 * sides, so it is where they are held together.
 *
 * The film tools are checked through the catalogue, which is the other half of
 * the hop `main.kt`'s `openNotificationTarget` makes: the wire names a tool,
 * the catalogue names its route.
 */
class NotificationRouteContractTest {

    @Test
    fun `every section the matcher answers is a real provider's path`() {
        assertEquals(CNC_PATH, NotificationSectionPaths.CHAT)
        assertEquals(EMAIL_PATH, NotificationSectionPaths.EMAIL)
        assertEquals(HOME_PATH, NotificationSectionPaths.HOME)
        assertEquals(SETTINGS_PATH, NotificationSectionPaths.SETTINGS)
        assertEquals(SOS_PATH, NotificationSectionPaths.SOS)
    }

    @Test
    fun `a chat row lands on Chat and a mail row on the mailbox`() {
        assertEquals(NotificationDestination.Section(CNC_PATH), route(section = "cnc_label"))
        assertEquals(NotificationDestination.Section(EMAIL_PATH), route(section = "email_label"))
    }

    @Test
    fun `a tools row reaches the tool's own route`() {
        // The whole hop, as the app makes it: wire name → grid identifier →
        // the route the tile opens.
        val destination = route(section = "tools_label", tool = "call_sheet_label")

        val identifier = (destination as NotificationDestination.FilmTool).identifier
        assertEquals(WorkspaceRoute.Tool("/film-tools/call-sheet"), ToolCatalogue.routeFor(identifier))
    }

    /**
     * Every tool the notification service can name, end to end: the ones this
     * build draws reach their own route, and the one it does not draw reaches
     * none — never another tool's.
     */
    @Test
    fun `the service's own tool names either reach their tool or nothing`() {
        val routed = wireTools.associateWith { wire ->
            val destination = route(section = "tools_label", tool = wire)
            (destination as? NotificationDestination.FilmTool)
                ?.let { it.identifier to ToolCatalogue.routeFor(it.identifier) }
        }

        // No tool lands on another's window. By identifier, not by wire name,
        // because two names are deliberately one tool: a budget's chat files
        // under its own label and opens the budget.
        val landed = routed.values.filterNotNull().filter { it.second != null }.distinct()
        assertEquals(
            landed.size,
            landed.distinctBy { it.second }.size,
            "two tools share a route: $landed",
        )
        assertEquals(routed["main_budget_label"], routed["main_budget_tool_chat_label"], "a budget and its chat")

        // The tools with a screen here land on it.
        assertEquals(WorkspaceRoute.Tool("/film-tools/call-sheet"), routed["call_sheet_label"]?.second)
        assertEquals(WorkspaceRoute.Tool("/film-tools/location"), routed["location_tool_label"]?.second)
        assertEquals(
            WorkspaceRoute.Tool("/film-tools/production-report-chat"),
            routed["production_report_tool_chat_label"]?.second,
        )

        // The one name this build has no tool for at all: the oneline
        // schedule, which no provider and no catalogue entry claims. A row for
        // it must stay put rather than open the nearest schedule.
        assertNull(routed["schedule_oneline_label"]?.second, "the oneline schedule")
        assertEquals("schedule_oneline_tool", routed["schedule_oneline_label"]?.first)
    }

    private fun route(
        path: String = "",
        section: String = "",
        tool: String = "",
    ) = matchNotificationRoute(NotificationTarget(path = path, section = section, tool = tool))

    /**
     * The tool names the notification service speaks, as `ToolWireNames` lists
     * them (iOS `ToolType`, `FirebaseRealmTimeDB.swift:1766-1811`). Copied
     * rather than read from that private list so a name quietly dropped there
     * still has to answer for itself here.
     */
    private val wireTools = listOf(
        "accounts_label", "catering_label", "info_label", "confidential_info_label",
        "production_label", "box_schedule_label", "purchase_order_label",
        "forms_and_signature_label", "deal_memo_label", "timecard_label",
        "continuity_label", "script_notes_label", "reports_label",
        "script_distribution_label", "schedule_distribution_label",
        "location_tool_label", "main_budget_label", "department_budget_label",
        "casting_main_tool_label", "casting_background_tool_label",
        "wardrobe_main_tool_label", "wardrobe_background_tool_label",
        "transportation_label", "schedule_oneline_label", "dod_label",
        "production_report_label", "production_report_tool_chat_label", "map_label",
        "call_sheet_label", "drive_label", "account_hub_label", "cash_expenses_label",
        "card_expenses_label", "ad_dashboard_label", "supporting_artistes_extras_label",
        "e_signature_label", "sides_label", "recce_label", "cost_report_label",
        "pre_production_label", "costume_set_sync_tool_label", "document_distribution_label",
        "main_budget_tool_chat_label", "department_budget_tool_chat_label",
    )
}
