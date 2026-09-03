package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.badgeArrivalFrom
import com.zillit.desktop.feature.home.data.badgeSuppressionFrom
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The two socket frames that move a badge without a refresh, as the wire
 * spells them — iOS's `ZillitNotificationApiModel` CodingKeys.
 */
class BadgeSocketPayloadsTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `a silent frame names the tools and units this person lost`() {
        val lost = badgeSuppressionFrom(
            json(
                """{"project_id":"p1","reference_data":{
                     "tools_no_view_access":["accounts_label","sides_label"],
                     "unit_no_view_access":["unit-a"]}}""",
            ),
        )

        assertNotNull(lost)
        // `accounts_label` is one of the two labels that break the suffix rule.
        assertEquals(setOf("accounting_tool", "sides_tool"), lost.toolIdentifiers)
        assertEquals(setOf("accounts_label", "sides_label"), lost.toolWireLabels, "the read needs the wire name")
        assertEquals(setOf("unit-a"), lost.units)
    }

    @Test
    fun `a silent frame that only clears rooms is nothing for the badges to forget`() {
        assertNull(badgeSuppressionFrom(json("""{"reference_data":{"chat_room_no_access":["r1"]}}""")))
    }

    @Test
    fun `a save frame says where the unread landed, under its data wrapper`() {
        val arrival = badgeArrivalFrom(
            json("""{"data":{"section":"tools_label","tool":"call_sheet_label","unit":"unit-a"}}"""),
        )

        assertNotNull(arrival)
        assertEquals("tools_label", arrival.section)
        assertEquals("callsheet_tool", arrival.toolIdentifier, "the other irregular label")
        assertEquals("unit-a", arrival.unit)
    }

    @Test
    fun `a save frame with none of the grouping keys is not an unread`() {
        assertNull(badgeArrivalFrom(json("""{"data":{"calendar_data":{}}}""")))
        assertNull(badgeArrivalFrom(json("""[]""")))
    }

    /** What the phones leave uncounted must not lift the badge either. */
    @Test
    fun `a silent, ignored or own-action frame does not lift the badge`() {
        val base = """"section":"cnc_label","tool":"sides_label""""
        assertNull(badgeArrivalFrom(json("""{"data":{$base,"silent":true}}""")))
        assertNull(badgeArrivalFrom(json("""{"data":{$base,"reference_data":{"ignore":true}}}""")))
        assertNull(badgeArrivalFrom(json("""{"data":{$base,"reference_data":{"self":true}}}""")))
        assertNotNull(badgeArrivalFrom(json("""{"data":{$base,"reference_data":{"self":false}}}""")))
    }


    /**
     * The shapes `unwrapRecord` in feature:notifications has met on this wire:
     * `data` as a JSON string, and an array's first record. A frame that could
     * not be opened would leave the badge exactly where it was — and nothing
     * would say why.
     */
    @Test
    fun `data sent as a string, or an array, still opens`() {
        val asString = badgeArrivalFrom(
            json("""{"data":"{\"section\":\"cnc_label\"}"}"""),
        )
        assertEquals("cnc_label", asString?.section, "a string-encoded data payload was not opened")

        val asArray = badgeSuppressionFrom(
            json("""[{"reference_data":{"tools_no_view_access":["sides_label"]}}]"""),
        )
        assertEquals(setOf("sides_tool"), asArray?.toolIdentifiers, "an array frame was not opened")
    }

}
