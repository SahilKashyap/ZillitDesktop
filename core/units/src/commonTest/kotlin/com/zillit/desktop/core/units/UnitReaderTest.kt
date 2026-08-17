package com.zillit.desktop.core.units

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading the server's unit list.
 *
 * Lenient on purpose: a typed reader has failed against an HTTP 200 on this API
 * four separate times, and a unit list that comes up empty blocks someone from
 * joining a production or from correcting the unit they are on.
 */
class UnitReaderTest {

    private val main = ProductionUnit("u1", "Main Unit")
    private val second = ProductionUnit("u2", "Second Unit")

    private fun units(json: String) = Json.parseToJsonElement(json).toUnits()

    @Test
    fun `a plain array of units is read`() {
        assertEquals(listOf(main), units("""[{"_id":"u1","unit_name":"Main Unit"}]"""))
    }

    @Test
    fun `a list wrapped in an object is read too`() {
        // `storage_folders` came back as an object wrapping its entries and
        // broke an entire screen's decode. Assume nothing about the wrapper.
        assertEquals(listOf(main), units("""{"units":[{"_id":"u1","unit_name":"Main Unit"}]}"""))
        assertEquals(listOf(main), units("""{"data":[{"id":"u1","name":"Main Unit"}]}"""))
    }

    @Test
    fun `a unit with no name still appears`() {
        // Better a row labelled by its id than a user who cannot pick a unit
        // because the server omitted a field.
        val read = units("""[{"_id":"u1"}]""")

        assertEquals(1, read.size)
        assertEquals("u1", read.first().name)
    }

    @Test
    fun `rows with no id are skipped, not fatal`() {
        assertEquals(
            listOf(second),
            units("""[{"unit_name":"Nameless"},{"_id":"u2","unit_name":"Second Unit"}]"""),
        )
    }

    @Test
    fun `an unexpected shape yields no units rather than failing`() {
        assertTrue(units("""{"message":"nothing here"}""").isEmpty())
        assertTrue(units("""null""").isEmpty())
        assertTrue(units(""""a string"""").isEmpty())
    }
}
