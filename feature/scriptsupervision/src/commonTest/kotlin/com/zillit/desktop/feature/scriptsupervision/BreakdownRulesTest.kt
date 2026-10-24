package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.Breakdown
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The breakdown's two rules.
 *
 * Both exist because the service stores TAGS — one per mention — while the
 * sheet shows ELEMENTS, and a tag carries a position into the script that has
 * to match what the web would have written for the same text.
 */
class BreakdownRulesTest {

    private fun tag(elementId: String, name: String, text: String = name) = Rec(
        buildJsonObject {
            put("_id", "t_$text")
            put("element_id", elementId)
            put("element_name", name)
            put("tagged_text", text)
        },
    )

    @Test
    fun `tags collapse to the elements they name, keeping every tag`() {
        val tags = listOf(
            tag("e1", "Steel tiffin", "tiffin"),
            tag("e2", "Bicycle"),
            // The same element again, named differently in the script.
            tag("e1", "Steel tiffin", "the tiffin"),
        )

        val elements = Breakdown.byElement(tags)

        assertEquals(2, elements.size)
        assertEquals(listOf("Steel tiffin", "Bicycle"), elements.map { it.name })
        // Both tags travel with the element: removing it removes all of them.
        assertEquals(2, elements[0].tags.size)
        assertEquals(1, elements[1].tags.size)
    }

    @Test
    fun `an element sent as a record rather than an id still groups`() {
        // The service populates `element_id` in some answers and sends the
        // bare id in others; both have to land on one element.
        val populated = Rec(
            buildJsonObject {
                put("_id", "t1")
                put("element_name", "Bicycle")
                put("element_id", buildJsonObject { put("_id", "e9") })
            },
        )
        val bare = Rec(
            buildJsonObject {
                put("_id", "t2")
                put("element_name", "Bicycle")
                put("element_id", "e9")
            },
        )

        assertEquals(1, Breakdown.byElement(listOf(populated, bare)).size)
    }

    @Test
    fun `a tag with no element id at all falls back to its name`() {
        val a = Rec(buildJsonObject { put("_id", "t1"); put("element_name", "Candle") })
        val b = Rec(buildJsonObject { put("_id", "t2"); put("element_name", "Candle") })

        val elements = Breakdown.byElement(listOf(a, b))

        assertEquals(1, elements.size)
        assertEquals("Candle", elements.single().name)
    }

    @Test
    fun `no tags is no elements`() {
        assertEquals(emptyList(), Breakdown.byElement(emptyList()))
    }

    @Test
    fun `locate finds the line and the range within it`() {
        val lines = listOf("INT. KITCHEN - DAY", "RAVI picks up the tiffin.", "He leaves.")

        val at = Breakdown.locate(lines, "tiffin")

        assertEquals(1, at.lineIndex)
        assertEquals("RAVI picks up the ".length, at.charStart)
        assertEquals("RAVI picks up the tiffin".length, at.charEnd)
    }

    @Test
    fun `locate matches whatever the case, and the first line that has it`() {
        val lines = listOf("A TIFFIN sits there.", "Another tiffin.")

        val at = Breakdown.locate(lines, "Tiffin")

        assertEquals(0, at.lineIndex)
        assertEquals("A ".length, at.charStart)
    }

    /**
     * ⚠ Parity, not endorsement. The web searches the TRIMMED line but
     * reports the offset into the trimmed text, so on an indented line — and
     * screenplay text is heavily indented — the stored offset is short by the
     * indent. Pinned so that if the web fixes it, this fails and says why
     * rather than the two clients quietly disagreeing about where a tag is.
     */
    @Test
    fun `an indented line reports the offset into the trimmed text, as the web does`() {
        val lines = listOf("                    RAVI picks up the tiffin.")

        val at = Breakdown.locate(lines, "tiffin")

        // Into the TRIMMED line, so the 20 spaces are not counted.
        assertEquals("RAVI picks up the ".length, at.charStart)
    }

    @Test
    fun `text the script does not contain tags nothing in particular`() {
        val at = Breakdown.locate(listOf("INT. KITCHEN - DAY"), "helicopter")

        // The web's fallback: the tag exists, it just highlights nothing.
        assertEquals(0, at.lineIndex)
        assertEquals(0, at.charStart)
        assertEquals(0, at.charEnd)
    }

    @Test
    fun `empty text and no script both fall back rather than throwing`() {
        assertEquals(0, Breakdown.locate(emptyList(), "tiffin").charEnd)
        assertEquals(0, Breakdown.locate(listOf("a line"), "   ").charEnd)
    }
}
