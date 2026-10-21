package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.BlockKind
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.domain.indentBy
import com.zillit.desktop.feature.email.domain.toggleList
import com.zillit.desktop.feature.email.ui.linePrefixes
import com.zillit.desktop.feature.email.ui.transformed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bullets and numbers are drawn, not typed.
 *
 * They live only in the visual transformation, so two things have to hold:
 * the text the field reports is still the user's own, and the offset mapping
 * is a true inverse. A mapping that is off by one is the bug where backspace
 * at the start of an item eats a character the user cannot see.
 */
class RichTextMarkerRenderTest {

    private fun listed() = RichText.plain("one\ntwo\nthree")
        .toggleList(BlockKind.Ordered, 0, 13)
        .indentBy(1, 4, 7)

    @Test
    fun `markers are drawn in front of each line`() {
        assertEquals(listOf("1.  ", "    1.  ", "2.  "), listed().linePrefixes())
    }

    @Test
    fun `an unmarked document is drawn untouched`() {
        val plain = RichText.plain("one\ntwo")

        assertEquals(plain.text, plain.transformed().text.text)
        assertEquals(listOf("", ""), plain.linePrefixes())
    }

    @Test
    fun `the drawn text carries the markers and the document does not`() {
        val doc = listed()

        assertEquals("one\ntwo\nthree", doc.text)
        assertEquals("1.  one\n    1.  two\n2.  three", doc.transformed().text.text)
    }

    @Test
    fun `every document offset maps out and back to itself`() {
        val doc = listed()
        val mapping = doc.transformed().offsetMapping

        (0..doc.text.length).forEach { offset ->
            assertEquals(
                offset,
                mapping.transformedToOriginal(mapping.originalToTransformed(offset)),
                "offset $offset did not survive the round trip",
            )
        }
    }

    @Test
    fun `a line's first character sits after its own marker`() {
        val doc = listed()
        val mapping = doc.transformed().offsetMapping

        // "two" starts at 4 in the document and after "\n    1.  " in the
        // drawing — the caret must land on the t, never inside the number.
        assertEquals("1.  one\n    1.  ".length, mapping.originalToTransformed(4))
    }

    @Test
    fun `an offset inside a marker is pulled onto the line's own text`() {
        val doc = listed()
        val mapping = doc.transformed().offsetMapping

        // Anywhere in the leading "1.  " of the first line is offset 0.
        (0..3).forEach { assertEquals(0, mapping.transformedToOriginal(it)) }
    }

    @Test
    fun `the end of the drawn text maps to the end of the document`() {
        val doc = listed()
        val transformed = doc.transformed()

        assertEquals(doc.text.length, transformed.offsetMapping.transformedToOriginal(transformed.text.length))
        assertEquals(transformed.text.length, transformed.offsetMapping.originalToTransformed(doc.text.length))
    }

    @Test
    fun `marks still cover their own words once the markers are drawn`() {
        val doc = RichText.plain("one\ntwo")
            .toggleList(BlockKind.Bullet, 0, 7)
            .toggle(TextMark.Bold, 4, 7)

        val transformed = doc.transformed()
        val bold = transformed.text.spanStyles.single()

        assertEquals("two", transformed.text.text.substring(bold.start, bold.end))
    }

    @Test
    fun `an indented plain line is drawn pushed with no marker`() {
        val pushed = RichText.plain("one").indentBy(2, 0, 3)

        assertEquals(listOf("        "), pushed.linePrefixes())
        assertEquals("        one", pushed.transformed().text.text)
    }
}
