package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One row per production, and what the prunes read off it.
 *
 * The rule this serves is in `NoticeCache.prune`: age is the floor, a recent
 * open is the reprieve. Everything here is about the reprieve being earned
 * only where it was actually earned — a prune that could be switched off by a
 * row nobody wrote is a disk leak with no symptom.
 */
class ProjectVisitsTest {

    private fun database(): ZillitDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        return ZillitDatabase(driver)
    }

    private fun visits() = ProjectVisits(database())

    @Test
    fun `an open is remembered against its production`() {
        val visits = visits()

        visits.opened("p1", 1_700)

        assertEquals(1_700, visits.lastOpened("p1"))
    }

    @Test
    fun `the latest open is the one kept`() {
        val visits = visits()

        visits.opened("p1", 1_000)
        visits.opened("p1", 2_000)

        assertEquals(2_000, visits.lastOpened("p1"))
    }

    @Test
    fun `a production this machine has never opened has no record`() {
        assertNull(visits().lastOpened("p1"))
    }

    @Test
    fun `only the productions opened inside the window are named`() {
        val visits = visits()
        visits.opened("recent", 5_000)
        visits.opened("older", 1_000)

        assertEquals(listOf("recent"), visits.openedSince(2_000))
    }

    /** The cutoff is inclusive: an open exactly on it is inside the window. */
    @Test
    fun `an open on the cutoff counts`() {
        val visits = visits()
        visits.opened("p1", 2_000)

        assertEquals(listOf("p1"), visits.openedSince(2_000))
    }

    @Test
    fun `nothing opened means nothing kept`() {
        assertEquals(emptyList(), visits().openedSince(0))
    }

    /**
     * `appMeta` is untyped and shared, so a value that is not a timestamp has
     * to read as "no record" rather than as a reprieve that never expires.
     */
    @Test
    fun `a row that is not a timestamp is no reprieve`() {
        val database = database()
        database.appMetaQueries.upsert("visit.project.p1", "whenever")

        assertEquals(emptyList(), ProjectVisits(database).openedSince(0))
        assertNull(ProjectVisits(database).lastOpened("p1"))
    }

    /** The prefix is a namespace: nothing else in this table is a visit. */
    @Test
    fun `rows belonging to the database itself are not read as visits`() {
        val database = database()
        database.appMetaQueries.upsert("schema.builtBy", "1000")
        val visits = ProjectVisits(database)
        visits.opened("p1", 1_000)

        assertEquals(listOf("p1"), visits.openedSince(0))
    }

    @Test
    fun `sign-out forgets the visits and leaves the rest of the table alone`() {
        val database = database()
        database.appMetaQueries.upsert("schema.builtBy", "1000")
        val visits = ProjectVisits(database)
        visits.opened("p1", 1_000)

        visits.forgetAll()

        assertEquals(emptyList(), visits.openedSince(0))
        assertEquals("1000", database.appMetaQueries.selectByKey("schema.builtBy").executeAsOne())
    }

    @Test
    fun `a production with no id is not a visit`() {
        val visits = visits()

        visits.opened("", 1_000)

        assertTrue(visits.openedSince(0).isEmpty())
    }
}
