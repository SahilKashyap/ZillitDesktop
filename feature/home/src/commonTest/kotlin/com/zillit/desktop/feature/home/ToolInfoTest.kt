package com.zillit.desktop.feature.home

import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.domain.ToolInfoViewer
import com.zillit.desktop.feature.home.domain.toolDescription
import com.zillit.desktop.feature.home.ui.ToolSection
import com.zillit.desktop.feature.home.ui.columnCount
import com.zillit.desktop.feature.home.ui.intoColumns
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ⓘ's text, as the web's `useAvailableFilmTools` picks it, and how the
 * redesigned grid shares its section cards between columns.
 */
class ToolInfoTest {

    private val crew = ToolInfoViewer()
    private val admin = ToolInfoViewer(isAdmin = true)

    private fun key(identifier: String, viewer: ToolInfoViewer = crew) =
        toolDescription(identifier, "Label", viewer).key

    @Test
    fun `the web's text and its admin or crew split`() {
        assertEquals(S.desktop_tool_info_purchase_order_tool_tooltip, key("purchase_order_tool"))
        assertEquals(S.desktop_tool_info_accounts_info_admin, key("accounting_tool", admin))
        assertEquals(S.desktop_tool_info_accounts_info_user, key("accounting_tool"))
        assertEquals(S.desktop_tool_info_timecard_tool_tooltip, key("timecard_tool"))
    }

    @Test
    fun `the first web entry whose name the identifier contains wins, as the web matches`() {
        // `account_hub` before `account`, `confidential_info` before `info`,
        // `pre_production_tool` before `production_tool`.
        assertEquals(S.desktop_tool_info_account_hub_tool_tooltip, key("account_hub_tool"))
        assertEquals(S.confidential_info_user, key("confidential_info_tool"))
        assertEquals(S.desktop_tool_info_info_user, key("info_tool"))
        assertEquals(S.desktop_tool_info_pre_production_info_admin, key("pre_production_tool", admin))
        assertEquals(S.desktop_tool_info_production_info_admin, key("production_tool", admin))
    }

    @Test
    fun `rights and production type pick the wording`() {
        val callSheetAuthor = ToolInfoViewer(canPost = { it == "callsheet_tool" })
        assertEquals(S.desktop_tool_info_call_sheet_info_admin, key("callsheet_tool", callSheetAuthor))
        assertEquals(S.desktop_tool_info_call_sheet_info_user, key("callsheet_tool"))
        // An accountant deals even without the posting right.
        assertEquals(
            S.desktop_tool_info_deal_memo_info_admin,
            key("deal_memo_tool", ToolInfoViewer(departmentIdentifier = "department_accounts")),
        )
        assertEquals(S.desktop_tool_info_deal_memo_info_user, key("deal_memo_tool"))
        val staff = ToolInfoViewer(isOtherProject = true)
        assertEquals(S.desktop_tool_info_info_user_other_project, key("info_tool", staff))
    }

    @Test
    fun `the crew list's text names the list`() {
        val described = toolDescription("generate_crew_list_tool", "Staff List", admin)
        assertEquals(S.desktop_tool_info_generatecrewlist_admin, described.key)
        assertEquals("Staff List", described.toolName)
    }

    @Test
    fun `every tool has something to say — Android, then the desktop, then a generic line`() {
        assertEquals(S.distribution_list_info, key("distribution_tool"))
        assertEquals(S.department_budget_info_user, key("department_budget_tool"))
        assertEquals(S.desktop_tool_info_supporting_artistes_tool_tooltip, key("sa_portal_tool"))
        assertEquals(S.desktop_tool_info_camera_sound_report, key("reports_tool"))
        assertEquals(S.desktop_tool_info_zillit_draft, key("zillit_draft"))
        val unknown = toolDescription("tool_shipped_tomorrow", "Clapper", crew)
        assertEquals(S.desktop_tool_info_generic, unknown.key)
        assertEquals("Clapper", unknown.toolName)
    }

    @Test
    fun `two web slips are not copied`() {
        // Crew would read the Main Cast list's text on the schedule, and the
        // main budget would be described as a department budget.
        assertEquals(S.schedule_distribution_info_user, key("schedule_distribution_tool"))
        assertEquals(S.main_budget_info_admin, key("main_budget_tool", admin))
    }

    private fun section(title: String, tools: Int) = ToolSection(
        title = title,
        tools = List(tools) {
            ToolPresentation("$title-$it", "$title $it", ZillitIcons.Tools, WorkspaceRoute.Tool("/x"))
        },
    )

    @Test
    fun `sections go to the shortest column, keeping their order within it`() {
        val columns = listOf(section("A", 7), section("B", 2), section("C", 2), section("D", 1)).intoColumns(2)

        assertEquals(listOf(listOf("A"), listOf("B", "C", "D")), columns.map { col -> col.map { it.title } })
    }

    @Test
    fun `no empty columns, and as many as the width allows`() {
        assertEquals(1, listOf(section("A", 3)).intoColumns(3).size)
        assertEquals(1, columnCount(300.dp))
        assertEquals(2, columnCount(700.dp))
        assertEquals(4, columnCount(3000.dp))
    }
}
