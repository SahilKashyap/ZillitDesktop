package com.zillit.desktop.feature.home

import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.data.UnreadRowDto
import com.zillit.desktop.feature.home.data.tallyRows
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decoding `notification/project/level/unread`.
 *
 * Every grouped query answers the same flat shape — rows of
 * `{"data": "<group value>", "unread": n}` — verified live 2026-08-11 after
 * the first model (an object of `*_label` groups, copied from Android's
 * commented-out legacy path) turned out never to occur on the wire.
 */
class BadgeDecodeTest {

    private fun rows(json: String) =
        HttpClientFactory.json.decodeFromString(ListSerializer(UnreadRowDto.serializer()), json)

    @Test
    fun `a grouped answer tallies per key`() {
        val counts = tallyRows(
            rows("""[{"data":"home_label","unread":3},{"data":"cnc_label","unread":2}]"""),
        )

        assertEquals(3, counts["home_label"])
        assertEquals(2, counts["cnc_label"])
    }

    @Test
    fun `repeated keys sum rather than replace`() {
        val counts = tallyRows(
            rows("""[{"data":"callsheet_tool","unread":2},{"data":"callsheet_tool","unread":5}]"""),
        )

        assertEquals(7, counts["callsheet_tool"])
    }

    @Test
    fun `the ungrouped null row is dropped, not fatal`() {
        // Called bare the endpoint answers `{"data":null,"unread":n}` — a
        // count with no place to be drawn.
        val counts = tallyRows(rows("""[{"data":null,"unread":1}]"""))

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `zero and negative counts create no keys`() {
        val counts = tallyRows(
            rows("""[{"data":"a_tool","unread":0},{"data":"b_tool","unread":-4}]"""),
        )

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `wire tool labels normalise to grid identifiers`() {
        assertEquals(
            "location_tool",
            com.zillit.desktop.feature.home.data.wireToolToIdentifier("location_tool_label"),
        )
        assertEquals(
            "forms_and_signature_tool",
            com.zillit.desktop.feature.home.data.wireToolToIdentifier("forms_and_signature_label"),
        )
        assertEquals(
            "callsheet_tool",
            com.zillit.desktop.feature.home.data.wireToolToIdentifier("callsheet_tool"),
        )
    }

    @Test
    fun `the irregular pairs map by table, not by suffix`() {
        assertEquals(
            "accounting_tool",
            com.zillit.desktop.feature.home.data.wireToolToIdentifier("accounts_label"),
        )
        assertEquals(
            "callsheet_tool",
            com.zillit.desktop.feature.home.data.wireToolToIdentifier("call_sheet_label"),
        )
    }

    @Test
    fun `a read names the tool the way the wire does`() {
        val toWire = { id: String -> com.zillit.desktop.feature.home.data.identifierToWireTool(id) }

        // The wire's two shapes — one keeps `_tool`, one never had it.
        assertEquals("location_tool_label", toWire("location_tool"))
        assertEquals("forms_and_signature_label", toWire("forms_and_signature_tool"))
        // The irregulars, round-tripped.
        assertEquals("accounts_label", toWire("accounting_tool"))
        assertEquals("call_sheet_label", toWire("callsheet_tool"))
        // An identifier the table has never heard of falls back to the
        // majority shape rather than the old append-a-suffix rule.
        assertEquals("weather_label", toWire("weather_tool"))
    }

    @Test
    fun `every wire label round-trips through its identifier`() {
        // A label whose identifier maps back to a DIFFERENT label would key
        // reads under a name the counts never used.
        val toIdentifier = { key: String -> com.zillit.desktop.feature.home.data.wireToolToIdentifier(key) }
        val toWire = { id: String -> com.zillit.desktop.feature.home.data.identifierToWireTool(id) }

        listOf(
            "accounts_label", "call_sheet_label", "location_tool_label",
            "forms_and_signature_label", "box_schedule_label", "deal_memo_label",
            "map_label", "production_report_label", "casting_background_tool_label",
        ).forEach { label ->
            assertEquals(label, toWire(toIdentifier(label)), "round trip of $label")
        }
    }

    @Test
    fun `unknown extra fields do not break the decode`() {
        // The server is free to grow rows; the client must not shatter.
        val counts = tallyRows(
            rows("""[{"data":"a_tool","unread":2,"brand_new_field":true}]"""),
        )

        assertEquals(2, counts["a_tool"])
    }
}
