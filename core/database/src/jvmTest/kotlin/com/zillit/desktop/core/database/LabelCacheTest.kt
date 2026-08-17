package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The label cache, against real in-memory SQLite.
 *
 * The behaviour that matters is the database's: replace-not-merge per
 * (language, kind), and the isolation between languages that lets someone
 * switch and switch back without two cold fetches.
 */
class LabelCacheTest {

    private lateinit var cache: LabelCache
    private var clock = 1_000L

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        cache = LabelCache(ZillitDatabase(driver)) { clock }
    }

    @Test
    fun `round-trips a dictionary`() {
        cache.save("en", "labels", mapOf("call_sheet_label" to "Call Sheet"))

        assertEquals(mapOf("labels" to mapOf("call_sheet_label" to "Call Sheet")), cache.load("en"))
    }

    @Test
    fun `keeps the three dictionaries apart`() {
        cache.save("en", "labels", mapOf("access_denied" to "No access"))
        cache.save("en", "messages", mapOf("access_denied" to "You do not have permission."))
        cache.save("en", "identifiers", mapOf("weather_tool" to "Forecast"))

        val loaded = cache.load("en")

        assertEquals("No access", loaded.getValue("labels").getValue("access_denied"))
        assertEquals("You do not have permission.", loaded.getValue("messages").getValue("access_denied"))
        assertEquals("Forecast", loaded.getValue("identifiers").getValue("weather_tool"))
    }

    @Test
    fun `a save replaces that dictionary rather than merging`() {
        // A key the server stopped sending has been retired. Merging would
        // leave the old translation answering for it forever.
        cache.save("en", "labels", mapOf("kept" to "Kept", "retired" to "Retired"))
        cache.save("en", "labels", mapOf("kept" to "Kept"))

        assertEquals(mapOf("kept" to "Kept"), cache.load("en").getValue("labels"))
    }

    @Test
    fun `replacing one dictionary leaves the others alone`() {
        cache.save("en", "labels", mapOf("a" to "A"))
        cache.save("en", "messages", mapOf("b" to "B"))

        cache.save("en", "labels", mapOf("a" to "A2"))

        assertEquals("A2", cache.load("en").getValue("labels").getValue("a"))
        assertEquals("B", cache.load("en").getValue("messages").getValue("b"))
    }

    @Test
    fun `languages do not see each other`() {
        // Switching language and back must not mean a cold fetch.
        cache.save("en", "labels", mapOf("call_sheet_label" to "Call Sheet"))
        cache.save("fr", "labels", mapOf("call_sheet_label" to "Feuille de service"))

        assertEquals("Call Sheet", cache.load("en").getValue("labels").getValue("call_sheet_label"))
        assertEquals("Feuille de service", cache.load("fr").getValue("labels").getValue("call_sheet_label"))
    }

    @Test
    fun `an empty dictionary is ignored, not written`() {
        // An empty response is a server or parse failure far more often than a
        // real emptying; writing it would blank every label on disk.
        cache.save("en", "labels", mapOf("call_sheet_label" to "Call Sheet"))
        cache.save("en", "labels", emptyMap())

        assertEquals("Call Sheet", cache.load("en").getValue("labels").getValue("call_sheet_label"))
    }

    @Test
    fun `an unknown language loads empty rather than failing`() {
        cache.save("en", "labels", mapOf("a" to "A"))

        assertTrue(cache.load("de").isEmpty())
    }

    @Test
    fun `clear drops every language`() {
        cache.save("en", "labels", mapOf("a" to "A"))
        cache.save("fr", "labels", mapOf("a" to "B"))

        cache.clear()

        assertTrue(cache.load("en").isEmpty())
        assertTrue(cache.load("fr").isEmpty())
    }
}
