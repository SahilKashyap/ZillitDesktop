package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenplayCacheTest {

    @Test
    fun `scripts round-trip per production, newest first, and delete`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        val cache = ScreenplayCache(ZillitDatabase(driver))
        cache.save(ScreenplaySnapshot("a", "p1", "Alpha", "{}", 1, 10))
        cache.save(ScreenplaySnapshot("b", "p1", "Beta", "{}", 2, 20))
        cache.save(ScreenplaySnapshot("c", "p2", "Gamma", "{}", 3, 30))
        assertEquals(listOf("b", "a"), cache.list("p1").map { it.id })
        assertEquals("Beta", cache.load("b")?.title)
        cache.save(ScreenplaySnapshot("a", "p1", "Alpha 2", "{\"x\":1}", 1, 40))
        assertEquals(listOf("a", "b"), cache.list("p1").map { it.id })
        cache.delete("a")
        assertNull(cache.load("a"))
    }
}
