package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The production cache.
 *
 * Runs against a real in-memory SQLite rather than a fake, because the
 * behaviour that matters — replace-not-merge, and the wipe on project switch —
 * is the database's, not a mock's.
 */
class ProjectCacheTest {

    private lateinit var cache: ProjectCache
    private var clock = 1_000L

    private val projectA = "p-a"
    private val projectB = "p-b"

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        cache = ProjectCache(ZillitDatabase(driver)) { clock }
    }

    private fun tool(id: String, view: Boolean = true) =
        ToolSnapshot(id, null, null, null, true, view, false, false, isTool = true, onHome = false)

    private fun notice(id: String, at: Long) =
        NoticeSnapshot(id, "u1", "body", "Author", null, at, false, false, 0, 0)

    @Test
    fun `tools round-trip in server order`() {
        // Order is the production's configured order; sorting alphabetically
        // here would silently override whatever an admin arranged.
        cache.saveTools(projectA, listOf(tool("z_tool"), tool("a_tool"), tool("m_tool")))

        assertEquals(listOf("z_tool", "a_tool", "m_tool"), cache.tools(projectA).map { it.identifier })
    }

    @Test
    fun `saving tools replaces rather than merges`() {
        // A revoked right disappears from the response. Merging would leave the
        // user holding it — the worst thing this cache could do.
        cache.saveTools(projectA, listOf(tool("keep"), tool("revoked")))
        cache.saveTools(projectA, listOf(tool("keep")))

        assertEquals(listOf("keep"), cache.tools(projectA).map { it.identifier })
    }

    @Test
    fun `access flags survive the round trip`() {
        cache.saveTools(projectA, listOf(tool("t", view = false)))

        assertEquals(false, cache.tools(projectA).single().canView)
    }

    @Test
    fun `one production cannot see another's tools`() {
        cache.saveTools(projectA, listOf(tool("a_only")))
        cache.saveTools(projectB, listOf(tool("b_only")))

        assertEquals(listOf("a_only"), cache.tools(projectA).map { it.identifier })
        assertEquals(listOf("b_only"), cache.tools(projectB).map { it.identifier })
    }

    @Test
    fun `clearing a production leaves the other intact`() {
        // Cached rights surviving a switch would let one production's
        // permissions answer questions asked inside another.
        cache.saveTools(projectA, listOf(tool("a")))
        cache.saveTools(projectB, listOf(tool("b")))
        cache.saveProfile(
            projectA,
            ProfileSnapshot(
                userId = "u1",
                fullName = "Aisha",
                email = null,
                phone = null,
                avatarUrl = null,
                isAdmin = true,
            ),
        )
        cache.saveNotices(projectA, "u1", listOf(notice("n1", 10)))

        cache.clearProject(projectA)

        assertTrue(cache.tools(projectA).isEmpty())
        assertNull(cache.profile(projectA))
        assertTrue(cache.notices(projectA, "u1").isEmpty())
        assertEquals(1, cache.tools(projectB).size, "the other production was cleared too")
    }

    @Test
    fun `notices come back oldest first`() {
        cache.saveNotices(projectA, "u1", listOf(notice("c", 300), notice("a", 100), notice("b", 200)))

        assertEquals(listOf("a", "b", "c"), cache.notices(projectA, "u1").map { it.noticeId })
    }

    @Test
    fun `saving a unit's notices does not disturb another unit`() {
        cache.saveNotices(projectA, "u1", listOf(notice("n1", 10)))
        cache.saveNotices(projectA, "u2", listOf(notice("n2", 20).copy(unitId = "u2")))

        assertEquals(1, cache.notices(projectA, "u1").size)
        assertEquals(1, cache.notices(projectA, "u2").size)
    }

    @Test
    fun `users are replaced wholesale so a removed crew member disappears`() {
        cache.saveUsers(projectA, listOf(user("u1", "Aisha"), user("u2", "Ben")))
        cache.saveUsers(projectA, listOf(user("u1", "Aisha")))

        assertEquals(listOf("Aisha"), cache.users(projectA).map { it.fullName })
    }

    @Test
    fun `users come back alphabetically, ignoring case`() {
        cache.saveUsers(projectA, listOf(user("u1", "zoe"), user("u2", "Aisha"), user("u3", "ben")))

        assertEquals(listOf("Aisha", "ben", "zoe"), cache.users(projectA).map { it.fullName })
    }

    @Test
    fun `profile and project round-trip`() {
        cache.saveProfile(
            projectA,
            ProfileSnapshot(
                userId = "u1",
                fullName = "Aisha Khan",
                // Kept apart as well as joined: the profile form edits the two
                // halves separately, and splitting the joined name back would
                // rename anyone whose first name has two words in it.
                firstName = "Aisha",
                lastName = "Khan",
                email = "a@b.com",
                phone = null,
                avatarUrl = null,
                isAdmin = true,
            ),
        )
        cache.saveProject(ProjectSnapshot(projectA, "Dune", "DUNE-1", "feature", null, "Legendary"))

        assertEquals("Aisha Khan", cache.profile(projectA)?.fullName)
        assertEquals("Aisha", cache.profile(projectA)?.firstName)
        assertEquals("Khan", cache.profile(projectA)?.lastName)
        assertEquals(true, cache.profile(projectA)?.isAdmin)
        assertEquals("DUNE-1", cache.project(projectA)?.code)
    }

    @Test
    fun `an empty cache answers null rather than throwing`() {
        assertNull(cache.profile("never-opened"))
        assertNull(cache.project("never-opened"))
        assertTrue(cache.tools("never-opened").isEmpty())
    }

    private fun user(id: String, name: String) =
        UserSnapshot(id, name, null, null, null, null, isAdmin = false)
}
