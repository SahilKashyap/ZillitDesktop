package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectListCacheTest {

    private lateinit var cache: ProjectListCache

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        cache = ProjectListCache(ZillitDatabase(driver)) { 1_000L }
    }

    private fun entry(id: String, name: String = id, pending: Boolean = false) = ProjectListSnapshot(
        projectId = id,
        name = name,
        code = "#$id",
        type = "Feature",
        region = null,
        isFavourite = false,
        userId = "u1",
        parentName = null,
        subType = null,
        isAdmin = true,
        isPending = pending,
        createdOnMillis = 5L,
    )

    @Test
    fun `round-trips in server order and replaces wholesale`() {
        cache.save(listOf(entry("b"), entry("a"), entry("c", pending = true)))
        assertEquals(listOf("b", "a", "c"), cache.list().map { it.projectId })
        assertTrue(cache.list().last().isPending)

        // The device was taken off "b": the next answer must not keep it.
        cache.save(listOf(entry("a")))
        assertEquals(listOf("a"), cache.list().map { it.projectId })
    }

    @Test
    fun `clear empties the list`() {
        cache.save(listOf(entry("a")))
        cache.clear()
        assertTrue(cache.list().isEmpty())
    }
}
