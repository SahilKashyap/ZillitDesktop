package com.zillit.desktop.feature.budget

import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetRules
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's list rules, each pinned on its own. */
class BudgetRulesTest {

    private val utc = TimeZone.UTC

    /** `getLatestBudgetDoc`: deleted rows go, the rest by `updated` descending. */
    @Test
    fun `sorted drops deleted rows and puts the newest update first`() {
        val rows = listOf(
            doc("old", updated = 10),
            doc("gone", updated = 99, deleted = true),
            doc("new", updated = 50),
        )

        assertEquals(listOf("new", "old"), BudgetRules.sorted(rows).map { it.id })
        assertEquals("new", BudgetRules.latest(rows)?.id)
    }

    /** `DepartmentBudget.jsx:105-121`: one row per department, newest created first. */
    @Test
    fun `departmentsWithBudgets keeps one newest row per department`() {
        val rows = listOf(
            doc("a1", type = BudgetType.Department, department = "cam", created = 1),
            doc("a2", type = BudgetType.Department, department = "cam", created = 9),
            doc("b1", type = BudgetType.Department, department = "art", created = 5),
            doc("m1", type = BudgetType.Main, created = 100),
        )

        val rows2 = BudgetRules.departmentsWithBudgets(rows)

        assertEquals(listOf("a2", "b1"), rows2.map { it.id })
    }

    /**
     * `AddAndShowDepartmentList.jsx:updateDepartment`: no main-budget right
     * and not an admin means only one's own department — added when it has
     * no budget yet, so there is somewhere to upload.
     */
    @Test
    fun `a department-only reader sees their own department alone`() {
        val viewer = BudgetViewer(canViewMain = false, canPostMain = false, canViewDepartment = true, resolved = true)

        assertEquals(listOf("cam"), BudgetRules.visibleDepartments(listOf("cam", "art"), viewer, "cam"))
        assertEquals(listOf("cam"), BudgetRules.visibleDepartments(listOf("art"), viewer, "cam"))
        assertFalse(BudgetRules.showsDepartmentSearch(viewer))
    }

    @Test
    fun `main-budget rights or admin open every department`() {
        val wide = BudgetViewer(canViewMain = true, canViewDepartment = true, resolved = true)
        val admin = BudgetViewer(canViewDepartment = true, isAdmin = true, resolved = true)

        assertEquals(listOf("cam", "art"), BudgetRules.visibleDepartments(listOf("cam", "art"), wide, "cam"))
        assertEquals(listOf("cam", "art"), BudgetRules.visibleDepartments(listOf("cam", "art"), admin, "cam"))
        assertTrue(BudgetRules.showsDepartmentSearch(admin))
    }

    /** The web's titles, character for character — the stored string is what every client reads back. */
    @Test
    fun `upload titles match the web`() {
        assertEquals("Budget (Full) -SEP 05, 2026", BudgetRules.uploadTitle(BudgetType.Main, "", "SEP 05, 2026"))
        assertEquals(
            "Camera - SEP 05, 2026",
            BudgetRules.uploadTitle(BudgetType.Department, "camera Label", "SEP 05, 2026"),
        )
    }

    /** `dayjs().format('MMM DD, YYYY')` upper-cased. */
    @Test
    fun `the date label is the web's dateStr`() {
        // 2026-09-05T12:00Z
        assertEquals("SEP 05, 2026", BudgetRules.dateLabel(1_788_609_600_000L, utc))
    }

    @Test
    fun `a date after today is refused`() {
        val today = 1_788_609_600_000L
        assertTrue(BudgetRules.dateIsAllowed(today, today, utc))
        assertTrue(BudgetRules.dateIsAllowed(today - 86_400_000L, today, utc))
        assertFalse(BudgetRules.dateIsAllowed(today + 86_400_000L, today, utc))
    }

    /** `MembersModal.jsx:handleCreateGroup`, in its order. */
    @Test
    fun `group complaints follow the web's order`() {
        assertEquals("Group name is mandatory.", BudgetRules.groupComplaint("  ", listOf("u1")))
        assertEquals("Please select a member to proceed.", BudgetRules.groupComplaint("Crew", emptyList()))
        assertTrue(BudgetRules.groupComplaint("ab", listOf("u1"))!!.contains("between 3 and 25"))
        assertNull(BudgetRules.groupComplaint("Camera crew", listOf("u1")))
    }

    @Test
    fun `stock thumbnails follow the extension and the storage`() {
        assertTrue(BudgetRules.stockThumbnail("a.PDF", isBox = false).endsWith(".png"))
        assertEquals("1581037016800", BudgetRules.stockThumbnail("a.pdf", isBox = true))
        assertTrue(BudgetRules.stockThumbnail("a.xlsx", isBox = false).contains("Excel"))
        assertEquals("", BudgetRules.stockThumbnail("a.docx", isBox = false))
        assertTrue(BudgetRules.acceptsFile("Budget.pdf"))
        assertFalse(BudgetRules.acceptsFile("Budget.xlsx"))
    }

    /** The picker's search matches uploaders, not titles (`CommonBudget.jsx:1900-1913`). */
    @Test
    fun `version search is by uploader name`() {
        val rows = listOf(doc("v1", uploader = "u1"), doc("v2", uploader = "u2"))
        val names = mapOf("u1" to "Ravi Menon", "u2" to "Anita Rao")

        assertEquals(listOf("v2"), BudgetRules.versionsByUploader(rows, "anita", names::get).map { it.id })
        assertEquals(2, BudgetRules.versionsByUploader(rows, "", names::get).size)
    }

    private fun doc(
        id: String,
        type: BudgetType = BudgetType.Main,
        department: String = "",
        created: Long = 0,
        updated: Long = created,
        deleted: Boolean = false,
        uploader: String = "",
    ) = BudgetDocument(
        id = id,
        type = type,
        departmentId = department,
        createdMillis = created,
        updatedMillis = updated,
        deleted = deleted,
        uploadedById = uploader,
    )
}
