package com.zillit.desktop.feature.permissiongrid

import com.zillit.desktop.feature.permissiongrid.data.GridPageDto
import com.zillit.desktop.feature.permissiongrid.data.toPage
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.SubjectColumn
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
        json.decodeFromString(GridPageDto.serializer(), page).toPage(GridAxis.CrewList, mine)

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
    fun `an omitted right is denied, and the locks read as the web reads them`() {
        val row = parsed().rows.first()
        val drive = row.cells.getValue("drive_label")
        val catering = row.cells.getValue("catering_label")

        // Nothing said about posting or download, so neither is granted.
        assertFalse(drive.canPost)
        assertFalse(drive.canDownload)
        // View and Download shut only on an explicit `false` …
        assertFalse(drive.locked(AccessKind.View))
        assertFalse(drive.locked(AccessKind.Download))
        // … Posting opens only on an explicit `postingUpdatable: true`
        // (`AccessGrid.jsx`: `disabled={… || !postingUpdatable}`).
        assertTrue(drive.locked(AccessKind.Post))
        assertTrue(catering.locked(AccessKind.Post))
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
    fun `label headers are frozen subject columns, the rest are tools by title`() {
        // `department_label` and `designation_label` head frozen subject
        // columns, not tools — carrying them across would put two empty
        // checkbox columns at the front of every row.
        assertEquals(listOf("catering_label", "drive_label"), parsed().columns)
        assertEquals(listOf(SubjectColumn.Department, SubjectColumn.Designation), parsed().subjects)
    }

    @Test
    fun `the crew-list axis keeps the server's order, user permissions sort by name`() {
        val unsorted = """
            {"headers":["user_label","department_label"],"total_users":2,
             "rows":[[{"user_id":"z","full_name":"Zara","admin_access":true}],
                     [{"user_id":"a","full_name":"Amir"}]]}
        """.trimIndent()
        val dto = json.decodeFromString(GridPageDto.serializer(), unsorted)

        assertEquals(listOf("z", "a"), dto.toPage(GridAxis.CrewList, null).rows.map { it.subject.id })
        assertEquals(listOf("a", "z"), dto.toPage(GridAxis.Users, null).rows.map { it.subject.id })
        assertTrue(dto.toPage(GridAxis.CrewList, null).rows.first().subject.isAdmin)
        assertEquals(
            listOf(SubjectColumn.User, SubjectColumn.Department),
            dto.toPage(GridAxis.CrewList, null).subjects,
        )
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
        // Its department is kept for the frozen Department column.
        assertEquals("Direction", page.rows.first().subject.department)
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
            .toPage(GridAxis.Users, null).columns

        // Alphabetical by translated title once the headers run out, so a
        // column the server did not announce still reaches the screen.
        assertEquals(listOf("account_hub_label", "weather_tool_label"), columns)
    }
}
