package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `cachedRead`'s half of the retention rule — the statement `SqlReadCache`
 * prunes with.
 *
 * Worth its own test because of which row this is. A board cannot draw a
 * single kept post until it knows its tab ids, and the tab strip is one of
 * these answers: the posts next door can be perfectly intact and still be
 * unreachable because this row went. The strip and the posts are also kept in
 * different tables, written at different moments, so before this rule they
 * could expire apart.
 */
class ReadCacheRetentionTest {

    private val window = 30L * 24 * 60 * 60_000

    private fun database(): ZillitDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        return ZillitDatabase(driver)
    }

    private fun ZillitDatabase.keep(projectId: String, name: String, fetchedAt: Long) {
        readCacheQueries.upsert(
            key = "$projectId/$name",
            userId = "me",
            projectId = projectId,
            body = """{"status":true}""",
            fetchedAt = fetchedAt,
        )
    }

    private fun ZillitDatabase.has(projectId: String, name: String): Boolean =
        readCacheQueries.selectByKey("$projectId/$name").executeAsOneOrNull() != null

    /** The prune as `SqlReadCache` performs it. */
    private fun ZillitDatabase.prune(now: Long) {
        val cutoff = now - window
        readCacheQueries.deleteAged(before = cutoff, kept = ProjectVisits(this).openedSince(cutoff))
    }

    @Test
    fun `the tab strip of a production still being opened survives its own age`() {
        val database = database()
        database.keep("worked-on", "home/unit/user", fetchedAt = 0)
        val now = 40L * 24 * 60 * 60_000
        ProjectVisits(database).opened("worked-on", now)

        database.prune(now)

        assertTrue(database.has("worked-on", "home/unit/user"))
    }

    @Test
    fun `a production nobody has opened in the window loses its answers`() {
        val database = database()
        database.keep("abandoned", "home/unit/user", fetchedAt = 0)

        database.prune(40L * 24 * 60 * 60_000)

        assertFalse(database.has("abandoned", "home/unit/user"))
    }

    /**
     * The empty list is the shape the first launch after this landed takes,
     * and `NOT IN ()` is a list SQLite accepts where most engines do not — so
     * it is asserted rather than assumed.
     */
    @Test
    fun `with nothing opened the rule is age alone`() {
        val database = database()
        val now = 40L * 24 * 60 * 60_000
        database.keep("p1", "old", fetchedAt = 0)
        database.keep("p1", "fresh", fetchedAt = now)

        database.prune(now)

        assertEquals(emptyList(), ProjectVisits(database).openedSince(0))
        assertFalse(database.has("p1", "old"))
        assertTrue(database.has("p1", "fresh"))
    }

    /** A reprieve covers the production it was earned for, and no other. */
    @Test
    fun `one production's open does not keep another's answers`() {
        val database = database()
        database.keep("worked-on", "home/unit/user", fetchedAt = 0)
        database.keep("abandoned", "home/unit/user", fetchedAt = 0)
        val now = 40L * 24 * 60 * 60_000
        ProjectVisits(database).opened("worked-on", now)

        database.prune(now)

        assertTrue(database.has("worked-on", "home/unit/user"))
        assertFalse(database.has("abandoned", "home/unit/user"))
    }
}
