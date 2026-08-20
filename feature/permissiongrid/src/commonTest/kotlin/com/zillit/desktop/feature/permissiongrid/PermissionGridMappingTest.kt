package com.zillit.desktop.feature.permissiongrid

import com.zillit.desktop.feature.permissiongrid.data.GridPageDto
import com.zillit.desktop.feature.permissiongrid.data.toPage
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning `permissions/{axis}/{section}/access` into a grid.
 *
 * A row is a heterogeneous array — subject first, tools after — and the lock
 * flags are camelCase beside their snake_case siblings. Both are the kind of
 * thing that reads fine and decodes to nothing.
 */
class PermissionGridMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val page = """
        {
          "headers": ["department_label", "designation_label", "catering_label", "drive_label"],
          "total_users": 57,
          "rows": [
            [
              {"user_id":"u1","full_name":"Vidya Pixel","department_name":"direction_label","designation_name":"ad_label"},
              {"unit_id":"c1","unit_name":"catering_label","view_access":true,"posting_access":false,"download_access":true},
              {"unit_id":"d1","unit_name":"drive_label","view_access":false,"postingUpdatable":false}
            ],
            [
              {"user_id":"u2","full_name":"Sahil K"},
              {"unit_id":"c1","unit_name":"catering_label","view_access":true}
            ]
          ]
        }
    """.trimIndent()

    private fun parsed(mine: String? = null) =
        json.decodeFromString(GridPageDto.serializer(), page).toPage(GridAxis.Crew, mine)

    @Test
    fun `a row becomes a subject and its cells`() {
        val row = parsed().rows.first()

        assertEquals("u1", row.subject.id)
        assertEquals("Vidya Pixel", row.subject.name)
        assertEquals(2, row.cells.size)

        val catering = row.cells.getValue("catering_label")
        assertTrue(catering.canView)
        assertFalse(catering.canPost)
        assertTrue(catering.canDownload)
    }

    @Test
    fun `an omitted right is denied, and only an explicit false locks one`() {
        val drive = parsed().rows.first().cells.getValue("drive_label")

        // Nothing said about posting or download, so neither is granted.
        assertFalse(drive.canPost)
        assertFalse(drive.canDownload)
        // `postingUpdatable: false` is the server saying "not yours to change".
        assertTrue(drive.locked(AccessKind.Post))
        // The other two were never mentioned — absent must not read as locked,
        // or a server predating the flags renders the whole grid read-only.
        assertFalse(drive.locked(AccessKind.View))
        assertFalse(drive.locked(AccessKind.Download))
    }

    @Test
    fun `the signed-in admin's own row is dropped, and the total is not`() {
        val mine = parsed(mine = "u1")

        assertEquals(listOf("u2"), mine.rows.map { it.subject.id })
        // The row we hid is still one the server is paging through.
        assertEquals(57, mine.total)
        assertNull(mine.rows.firstOrNull { it.subject.id == "u1" })
    }

    @Test
    fun `columns are the tools, in the production's order`() {
        // `department_label` and `designation_label` head frozen subject
        // columns, not tools — carrying them across would put two empty
        // checkbox columns at the front of every row.
        assertEquals(listOf("catering_label", "drive_label"), parsed().columns)
    }

    /**
     * Found live: the designations page took the window down with a duplicate
     * LazyColumn key, because every designation in a department carries that
     * department's id too.
     */
    @Test
    fun `a designation is identified by its own id, not its department's`() {
        val designations = """
            {"headers":[],"total_users":2,
             "rows":[
               [{"department_id":"dept1","department_name":"direction_label",
                 "designation_id":"des1","designation_name":"ad_label"},
                {"unit_id":"c1","unit_name":"catering_label","view_access":true}],
               [{"department_id":"dept1","department_name":"direction_label",
                 "designation_id":"des2","designation_name":"director_label"},
                {"unit_id":"c1","unit_name":"catering_label"}]
             ]}
        """.trimIndent()
        val page = json.decodeFromString(GridPageDto.serializer(), designations)
            .toPage(GridAxis.Designations, null)

        // Two rows, two ids — reading the first non-null id gave "dept1" twice
        // and every write would have landed on the department.
        assertEquals(listOf("des1", "des2"), page.rows.map { it.subject.id })
        assertEquals(2, page.rows.map { it.subject.id }.distinct().size)
        // Titled by its own name, not by the department it belongs to.
        assertEquals(listOf("Ad", "Director"), page.rows.map { it.subject.name })
        // And the department is not repeated as a sub-label on this axis.
        assertNull(page.rows.first().subject.department)
    }

    @Test
    fun `a department row is identified and titled by its department`() {
        val departments = """
            {"headers":[],"total_users":1,
             "rows":[[{"department_id":"dept9","department_name":"camera_label"},
                      {"unit_id":"c1","unit_name":"catering_label"}]]}
        """.trimIndent()
        val row = json.decodeFromString(GridPageDto.serializer(), departments)
            .toPage(GridAxis.Departments, null).rows.single()

        assertEquals("dept9", row.subject.id)
        assertEquals("Camera", row.subject.name)
    }

    @Test
    fun `a tool the headers forgot still gets a column`() {
        val extra = """
            {"headers":[],"total_users":1,
             "rows":[[{"user_id":"u9","full_name":"Zed"},
                      {"unit_id":"z1","unit_name":"weather_tool_label"},
                      {"unit_id":"a1","unit_name":"account_hub_label"}]]}
        """.trimIndent()
        val columns = json.decodeFromString(GridPageDto.serializer(), extra)
            .toPage(GridAxis.Crew, null).columns

        // Alphabetical by translated title once the headers run out, so a
        // column the server did not announce still reaches the screen.
        assertEquals(listOf("account_hub_label", "weather_tool_label"), columns)
    }
}
