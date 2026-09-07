package com.zillit.desktop.core.badges

import kotlin.test.Test
import kotlin.test.assertEquals

/** The two vocabularies — the notification service's tool labels and the grid's identifiers. */
class ToolWireNamesTest {

    @Test
    fun `wire tool labels normalise to grid identifiers`() {
        assertEquals("location_tool", wireToolToIdentifier("location_tool_label"))
        assertEquals("forms_and_signature_tool", wireToolToIdentifier("forms_and_signature_label"))
        assertEquals("callsheet_tool", wireToolToIdentifier("callsheet_tool"))
    }

    @Test
    fun `the irregular pairs map by table, not by suffix`() {
        assertEquals("accounting_tool", wireToolToIdentifier("accounts_label"))
        assertEquals("callsheet_tool", wireToolToIdentifier("call_sheet_label"))
    }

    @Test
    fun `a read names the tool the way the wire does`() {
        // The wire's two shapes — one keeps `_tool`, one never had it.
        assertEquals("location_tool_label", identifierToWireTool("location_tool"))
        assertEquals("forms_and_signature_label", identifierToWireTool("forms_and_signature_tool"))
        // The irregulars, round-tripped.
        assertEquals("accounts_label", identifierToWireTool("accounting_tool"))
        assertEquals("call_sheet_label", identifierToWireTool("callsheet_tool"))
        // An identifier the table has never heard of falls back to the
        // majority shape rather than the old append-a-suffix rule.
        assertEquals("weather_label", identifierToWireTool("weather_tool"))
    }

    @Test
    fun `every wire label round-trips through its identifier`() {
        // A label whose identifier maps back to a DIFFERENT label would key
        // reads under a name the counts never used.
        listOf(
            "accounts_label", "call_sheet_label", "location_tool_label",
            "forms_and_signature_label", "box_schedule_label", "deal_memo_label",
            "map_label", "production_report_label", "casting_background_tool_label",
            "recce_label", "cost_report_label", "pre_production_label",
        ).forEach { label ->
            assertEquals(label, identifierToWireTool(wireToolToIdentifier(label)), "round trip of $label")
        }
    }
}
