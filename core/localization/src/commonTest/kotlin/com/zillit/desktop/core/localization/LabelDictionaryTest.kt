package com.zillit.desktop.core.localization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lookup, precedence, and the three shapes a key arrives in.
 *
 * The fallback cases matter as much as the hits: every one of them is a screen
 * that showed a user a raw identifier in one of the other clients.
 */
class LabelDictionaryTest {

    private val dictionary = LabelDictionary.Empty
        .with(
            LabelKind.Labels,
            mapOf(
                "call_sheet_label" to "Call Sheet",
                "access_denied" to "No access",
                "empty_key" to "",
            ),
        )
        .with(
            LabelKind.Messages,
            mapOf(
                "access_denied" to "You do not have permission to do that.",
                "trip_id_required" to "Pick a trip first.",
            ),
        )
        .with(
            LabelKind.Identifiers,
            mapOf("transportation_tool" to "Transport"),
        )

    @Test
    fun `resolves a plain key`() {
        assertEquals("Call Sheet", dictionary.translate("call_sheet_label"))
    }

    @Test
    fun `the preferred dictionary wins a shared key`() {
        // The whole reason the three tables are kept apart: the same name is a
        // heading in one and a refusal in the other.
        assertEquals("No access", dictionary.translate("access_denied", LabelKind.Labels))
        assertEquals(
            "You do not have permission to do that.",
            dictionary.translate("access_denied", LabelKind.Messages),
        )
    }

    @Test
    fun `falls through to the other dictionaries`() {
        // Asked as a label, found among the messages — a miss in the preferred
        // table is not a miss.
        assertEquals("Pick a trip first.", dictionary.translate("trip_id_required", LabelKind.Labels))
        assertEquals("Transport", dictionary.translate("transportation_tool", LabelKind.Labels))
    }

    @Test
    fun `an unknown key is humanised, never shown raw`() {
        assertEquals("Second Ad", dictionary.translate("second_ad_label"))
        assertEquals("Weather", dictionary.translate("weather_tool"))
        assertEquals("Permanent Trip Id Required", dictionary.translate("permanent_trip_id_required"))
    }

    @Test
    fun `prose is left exactly as the server wrote it`() {
        // Backends answer some failures in sentences rather than codes.
        // Title-casing one is a corruption of deliberate text — and unlike a
        // raw key, invisible in review.
        assertEquals("already exists", dictionary.translate("already exists", LabelKind.Messages))
        assertEquals("in use", dictionary.translate("in use", LabelKind.Messages))
        assertEquals(
            "That folder is not empty.",
            dictionary.translate("That folder is not empty.", LabelKind.Messages),
        )
        // A bare token asked for as a *message* is the server's word, kept.
        assertEquals("nope", dictionary.translate("nope", LabelKind.Messages))
    }

    @Test
    fun `a bare token asked for as a label is still capitalised`() {
        // `preset/project-types` returns `other` alongside
        // `entertainment_industry_label`, and it is a dropdown option either
        // way. Nothing but the caller's kind distinguishes it from `nope`.
        assertEquals("Other", dictionary.translate("other", LabelKind.Labels))
        assertEquals("Camera", dictionary.translate("Camera", LabelKind.Labels))
    }

    @Test
    fun `an admin-typed name survives a label lookup untouched`() {
        // Unit names are not always keys — someone types them. Whitespace is
        // the tell, and it outranks the kind.
        assertEquals("2nd unit — nights", dictionary.translate("2nd unit — nights", LabelKind.Labels))
    }

    @Test
    fun `a blank translation counts as missing`() {
        // The server ships empty strings for keys awaiting translation. An
        // empty label is indistinguishable from a missing control.
        assertEquals("Empty Key", dictionary.translate("empty_key"))
        assertNull(dictionary.exact("empty_key"))
    }

    @Test
    fun `strips the namespace wrapper the user endpoints emit`() {
        // `{designation:call_sheet_label}` is what project/users sends.
        assertEquals("Call Sheet", dictionary.translate("{designation:call_sheet_label}"))
        assertEquals("Second Ad", dictionary.translate("{designation:second_ad_label}"))
    }

    @Test
    fun `pulls the key out of a failure envelope`() {
        // ZL-19288 on Android: callers pass `error.message`, which for some
        // failures is the entire response body, and the raw JSON reached a toast.
        val envelope = """{"status":0,"message":"trip_id_required","messageElements":[],"data":{}}"""

        assertEquals("Pick a trip first.", dictionary.translate(envelope, LabelKind.Messages))
    }

    @Test
    fun `an unmatched envelope still yields the inner key, not the JSON`() {
        val envelope = """{"status":0,"message":"unknown_backend_code","messageElements":[],"data":{}}"""

        assertEquals("Unknown Backend Code", dictionary.translate(envelope, LabelKind.Messages))
    }

    @Test
    fun `text that merely looks like an envelope is left alone`() {
        // No `status`, so not the envelope — losing the caller's string would
        // be worse than failing to improve it.
        val notAnEnvelope = """{"message":"hello"}"""

        assertEquals(notAnEnvelope, dictionary.translate(notAnEnvelope))
        // Malformed JSON must not throw on the way to a label.
        assertEquals("""{"status":0,"message":""", dictionary.translate("""{"status":0,"message":"""))
    }

    @Test
    fun `renders a notification path segment by segment`() {
        val paths = LabelDictionary.Empty.with(
            LabelKind.Labels,
            mapOf("notices" to "Notices", "call_sheet_label" to "Call Sheet"),
        )

        assertEquals("Notices : Call Sheet", paths.translatePath("{notices/call_sheet_label}"))
    }

    @Test
    fun `an empty key translates to an empty string`() {
        assertEquals("", dictionary.translate(""))
        assertEquals("", dictionary.translate("   "))
    }

    @Test
    fun `reverse lookup finds the key behind a translation`() {
        assertEquals("call_sheet_label", dictionary.keyFor("Call Sheet"))
        // Unknown text is its own answer — the search box gets no worse.
        assertEquals("Camera", dictionary.keyFor("Camera"))
    }

    @Test
    fun `with replaces a dictionary rather than merging it`() {
        // A key the server stopped sending has been retired; merging would
        // leave the old translation answering for it forever.
        val replaced = dictionary.with(LabelKind.Labels, mapOf("call_sheet_label" to "Sheet"))

        assertEquals("Sheet", replaced.translate("call_sheet_label"))
        // `access_denied` is no longer among the labels, so asking for it as a
        // label now falls through to the messages table.
        assertEquals(
            "You do not have permission to do that.",
            replaced.translate("access_denied", LabelKind.Labels),
        )
        // The other two dictionaries are untouched by a Labels replacement.
        assertEquals("Transport", replaced.translate("transportation_tool"))
    }

    @Test
    fun `an empty dictionary humanises everything`() {
        assertTrue(LabelDictionary.Empty.isEmpty)
        assertEquals("Call Sheet", LabelDictionary.Empty.translate("call_sheet_label"))
    }
}
