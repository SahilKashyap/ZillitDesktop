package com.zillit.desktop.core.common

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Placeholder substitution in server messages.
 *
 * The cases are ported from the web client's own tests
 * (`accountHub/__tests__/apiErrorToast.messageElements.test.js`), because this
 * is a wire contract and the two clients must agree on it exactly — including
 * the awkward parts, like a null replacer meaning "nothing" rather than
 * "leave the braces".
 */
class MessageElementTest {

    private fun element(search: String?, replacer: String?) = MessageElement(search, replacer)

    @Test
    fun `text with nothing to substitute is returned unchanged`() {
        // The common path. It must be byte-for-byte what it was before.
        assertEquals("Cannot edit", applyMessageElements("Cannot edit", emptyList()))
    }

    @Test
    fun `a placeholder is replaced by its value`() {
        assertEquals(
            "Cannot edit this timecard because it is paid",
            applyMessageElements(
                "Cannot edit this timecard because it is {{status}}",
                listOf(element("{{status}}", "paid")),
            ),
        )
    }

    @Test
    fun `a replacer the dictionary knows is translated`() {
        assertEquals(
            "It is Paid",
            applyMessageElements(
                "It is {{status}}",
                listOf(element("{{status}}", "paid")),
                translate = { if (it == "paid") "Paid" else null },
            ),
        )
    }

    @Test
    fun `a replacer the dictionary does not know is used verbatim`() {
        // Replacers are values — names, counts, ids. A miss is the normal case
        // and must not be reworded into something the server never said.
        assertEquals(
            "Assigned to ada.lovelace",
            applyMessageElements(
                "Assigned to {{user}}",
                listOf(element("{{user}}", "ada.lovelace")),
                translate = { null },
            ),
        )
    }

    @Test
    fun `punctuation next to a placeholder survives`() {
        assertEquals(
            "It is paid.",
            applyMessageElements("It is {{status}}.", listOf(element("{{status}}", "paid"))),
        )
    }

    @Test
    fun `every occurrence is replaced, across several elements`() {
        assertEquals(
            "one then one then two",
            applyMessageElements(
                "{{a}} then {{a}} then {{b}}",
                listOf(element("{{a}}", "one"), element("{{b}}", "two")),
            ),
        )
    }

    @Test
    fun `malformed elements are skipped and a null replacer empties the slot`() {
        assertEquals(
            "x KEPT y ",
            applyMessageElements(
                "x {{keep}} y {{gone}}",
                listOf(
                    element(search = null, replacer = "no-search"),
                    element(search = "", replacer = "empty-search"),
                    element("{{keep}}", "KEPT"),
                    element("{{gone}}", null),
                ),
            ),
        )
    }

    @Test
    fun `empty text is left alone`() {
        assertEquals("", applyMessageElements("", listOf(element("{{a}}", "one"))))
    }

    // -- the last line of defence ------------------------------------------

    @Test
    fun `an unfilled placeholder is dropped, and the gap closed`() {
        assertEquals(
            "Cannot edit this timecard because it is.",
            "Cannot edit this timecard because it is {{status}}.".withoutUnfilledPlaceholders(),
        )
    }

    @Test
    fun `a placeholder mid-sentence does not leave a double space`() {
        assertEquals(
            "Ada moved to Lighting",
            "Ada {{verb}} moved to Lighting".withoutUnfilledPlaceholders(),
        )
    }

    @Test
    fun `text with no placeholders is returned as it stands`() {
        val clean = "Nothing to see here."
        assertEquals(clean, clean.withoutUnfilledPlaceholders())
        // Single braces are not placeholders — a label key wrapper uses them.
        assertEquals("{designation:driver_label}", "{designation:driver_label}".withoutUnfilledPlaceholders())
    }

    // -- the wire shape ----------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes the shape the server actually sends`() {
        val decoded = json.decodeFromString(
            MessageElement.serializer(),
            """{"search":"{{status}}","replacer":"paid"}""",
        )

        assertEquals("{{status}}", decoded.search)
        assertEquals("paid", decoded.replacer)
    }

    @Test
    fun `a numeric replacer does not fail the envelope`() {
        // The server sends counts and ids unquoted. A strict String decode
        // would lose the whole error over one of them — the Android client
        // carries the same tolerance.
        val decoded = json.decodeFromString(
            MessageElement.serializer(),
            """{"search":"{{count}}","replacer":3}""",
        )

        assertEquals("3", decoded.replacer)
    }

    @Test
    fun `a null replacer decodes to null, not to the string null`() {
        val decoded = json.decodeFromString(
            MessageElement.serializer(),
            """{"search":"{{who}}","replacer":null}""",
        )

        assertNull(decoded.replacer)
    }
}
