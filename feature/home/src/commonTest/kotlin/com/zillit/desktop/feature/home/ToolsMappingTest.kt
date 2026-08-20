package com.zillit.desktop.feature.home

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.data.ToolInfoDto
import com.zillit.desktop.feature.home.domain.ToolCatalogue
import com.zillit.desktop.feature.home.ui.HomeUiState
import com.zillit.desktop.feature.home.ui.UNGROUPED_TOOLS
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.workspace.WorkspaceRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning `GET project/tools` into rights and tiles.
 *
 * The mapping is where a permissions bug would actually hide — the resolution
 * logic is covered in `core:permissions`, but a wrong default here would hand
 * out access before that logic ever sees it.
 */
class ToolsMappingTest {

    @Test
    fun `an omitted access flag means denied, not granted`() {
        // The single most dangerous default in the app. A server that omits
        // `posting_access` must not be read as "posting allowed".
        val access = ToolInfoDto(identifier = "callsheet_tool").toAccess()!!

        assertFalse(access.canView)
        assertFalse(access.canPost)
        assertFalse(access.canDownload)
    }

    @Test
    fun `an omitted enabled flag means the tool is on`() {
        // The opposite default, and deliberately so: `enabled` marks a tool
        // switched *off*, so reading silence as "off" would empty the grid.
        assertTrue(ToolInfoDto(identifier = "callsheet_tool").toAccess()!!.enabled)
        assertFalse(ToolInfoDto(identifier = "x", enabled = false).toAccess()!!.enabled)
    }

    @Test
    fun `a tool with no identifier is dropped`() {
        // It could not be looked up, so it could only ever resolve to denied —
        // carrying it would put an unopenable tile on the grid.
        assertNull(ToolInfoDto(identifier = null).toAccess())
        assertNull(ToolInfoDto(identifier = "  ").toAccess())
    }

    @Test
    fun `flags map to the right rights`() {
        val access = ToolInfoDto(
            identifier = "callsheet_tool",
            viewAccess = true,
            postingAccess = false,
            downloadAccess = true,
            home = true,
        ).toAccess()!!

        assertTrue(access.canView)
        assertFalse(access.canPost)
        assertTrue(access.canDownload)
        assertTrue(access.onHome)
    }

    // -- catalogue --------------------------------------------------------

    @Test
    fun `the tool's own name titles the tile, not its identifier`() {
        // Both phones title a tile from `unit_name`, and the two are not the
        // same string: on develop `accounting_tool` is named `accounts_label`.
        // With no dictionary installed both sides humanise, which is enough to
        // show *which* key was asked for.
        val named = ToolCatalogue.present(ToolAccess("accounting_tool", unitName = "accounts_label"))
        assertEquals("Accounts", named.label)

        // Only when the server sent no name does the identifier stand in.
        assertEquals("Accounting", ToolCatalogue.present(ToolAccess("accounting_tool")).label)
        assertEquals(
            "Accounting",
            ToolCatalogue.present(ToolAccess("accounting_tool", unitName = "  ")).label,
            "a blank name is no name",
        )
    }

    @Test
    fun `a known tool gets its designed icon and route`() {
        val presented = ToolCatalogue.present(ToolAccess("forms_and_signature_tool", canView = true))

        assertEquals("Forms And Signature", presented.label)
        // The web's path, singular — the desktop misspelled it until the tool
        // itself arrived and nothing could open.
        assertEquals(WorkspaceRoute.Tool("/film-tools/form-signature"), presented.route)
    }

    @Test
    fun `an unknown tool still gets a tile`() {
        // The backend adds tools. One shipped after this build must appear,
        // looking plain, rather than not appearing at all.
        val presented = ToolCatalogue.present(ToolAccess("weather_pro_tool", canView = true))

        assertEquals("Weather Pro", presented.label)
        assertEquals(WorkspaceRoute.Tool("/tool/weather_pro_tool"), presented.route)
    }

    @Test
    fun `two unknown tools do not collide on one window`() {
        val a = ToolCatalogue.present(ToolAccess("recce_tool"))
        val b = ToolCatalogue.present(ToolAccess("casting_tool"))

        assertNotEquals(a.route, b.route)
    }

    @Test
    fun `the production's real tools all reach the grid`() {
        // These nine are what QA actually returns from `project/tools`. None of
        // them is Home, Chat, Email or Settings — those are app sections, and
        // treating them as tools is what emptied the rail.
        val identifiers = listOf(
            "accounting_tool", "catering_tool", "confidential_info_tool",
            "forms_and_signature_tool", "info_tool", "permission_grid_tool",
            "script_distribution_tool", "ad_dashboard_tool",
            "supporting_artistes_extras_tool",
        )
        val permissions = ProjectPermissions(identifiers.map { ToolAccess(it, canView = true) })
        val state = com.zillit.desktop.feature.home.ui.HomeUiState(permissions = permissions)

        assertEquals(identifiers, state.gridTools.map { it.identifier })
        assertEquals(
            identifiers.size,
            state.gridTools.map { it.route.path }.distinct().size,
            "each tool needs its own window",
        )
    }

    @Test
    fun `an unmapped tool still gets a label, an icon and a route`() {
        // Four of the nine have no bespoke icon on purpose. They must not
        // degrade into a blank entry.
        val presented = ToolCatalogue.present(ToolAccess("accounting_tool", canView = true))

        assertEquals("Accounting", presented.label)
        assertTrue(presented.route.path.isNotBlank())
    }

}

/**
 * The grid's sections.
 *
 * The rules worth pinning are the ones that keep a tool on screen: a group the
 * server did not name still shows its tools, and an empty group shows nothing
 * rather than a heading over a gap.
 */
class ToolSectionsTest {

    private fun tool(identifier: String, group: String?) = ToolAccess(
        identifier = identifier,
        groupIdentifier = group,
        enabled = true,
        canView = true,
        isTool = true,
    )

    private fun state(tools: List<ToolAccess>, groups: List<ToolGroup>) = HomeUiState(
        permissions = ProjectPermissions(tools = tools, isAdmin = false),
        groups = groups,
    )

    @Test
    fun `sections follow the server's order and carry its names`() {
        val sections = state(
            tools = listOf(
                tool("catering_tool", "production"),
                tool("accounting_tool", "accounts"),
                tool("info_tool", "production"),
            ),
            groups = listOf(
                ToolGroup("production", "Production"),
                ToolGroup("accounts", "Accounts"),
            ),
        ).sections

        assertEquals(listOf("Production", "Accounts"), sections.map { it.title })
        assertEquals(2, sections.first().tools.size)
    }

    @Test
    fun `a tool with no known group still reaches the grid`() {
        val sections = state(
            tools = listOf(tool("catering_tool", "production"), tool("weather_tool", null)),
            groups = listOf(ToolGroup("production", "Production")),
        ).sections

        assertEquals(listOf("Production", UNGROUPED_TOOLS), sections.map { it.title })
        assertEquals(
            listOf("weather_tool"),
            sections.last().tools.map { it.identifier },
            "a tool the server left ungrouped must not vanish",
        )
    }

    @Test
    fun `an empty group is no section at all`() {
        val sections = state(
            tools = listOf(tool("catering_tool", "production")),
            groups = listOf(ToolGroup("production", "Production"), ToolGroup("empty", "Empty")),
        ).sections

        assertEquals(listOf("Production"), sections.map { it.title })
    }
}
