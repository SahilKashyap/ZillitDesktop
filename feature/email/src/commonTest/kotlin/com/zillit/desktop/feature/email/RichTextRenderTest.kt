package com.zillit.desktop.feature.email

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.zillit.desktop.feature.email.domain.Mark
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.ui.annotated
import com.zillit.desktop.feature.email.ui.hexColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's render path.
 *
 * The document's marks reach the screen only through [annotated] — the field
 * itself is handed plain text, because spans carried inside a `TextFieldValue`
 * are dropped silently by `BasicTextField`. That drop is exactly the shipped
 * bug ("the formatting buttons do nothing"): storage and the HTML send path
 * were fine, the pixels were not. These tests pin the mapping the visual
 * transformation relies on.
 */
class RichTextRenderTest {

    @Test
    fun `each mark kind renders as its span style`() {
        val doc = RichText(
            text = "bold italic under",
            marks = listOf(
                Mark(0, 4, TextMark.Bold),
                Mark(5, 11, TextMark.Italic),
                Mark(12, 17, TextMark.Underline),
            ),
        )

        val spans = doc.annotated().spanStyles

        assertEquals(3, spans.size)
        assertEquals(FontWeight.Bold, spans[0].item.fontWeight)
        assertEquals(0 to 4, spans[0].start to spans[0].end)
        assertEquals(FontStyle.Italic, spans[1].item.fontStyle)
        assertEquals(5 to 11, spans[1].start to spans[1].end)
        assertEquals(TextDecoration.Underline, spans[2].item.textDecoration)
        assertEquals(12 to 17, spans[2].start to spans[2].end)
    }

    @Test
    fun `text survives unchanged`() {
        val doc = RichText("format test", listOf(Mark(0, 6, TextMark.Bold)))

        assertEquals("format test", doc.annotated().text)
    }

    @Test
    fun `mark ends are clamped to the text`() {
        // A mark can momentarily outlive the text it covered (delete-at-end
        // races the recomposition); an out-of-range span throws inside layout.
        val doc = RichText("hi", listOf(Mark(0, 9, TextMark.Bold)))

        val span = doc.annotated().spanStyles.single()

        assertTrue(span.end <= doc.text.length)
    }

    @Test
    fun `plain document renders without spans`() {
        assertEquals(0, RichText("plain").annotated().spanStyles.size)
    }

    @Test
    fun `underline and strike on one range combine into one decoration`() {
        // A later span's TextDecoration REPLACES an earlier one instead of
        // stacking — the reason rendering merges marks per segment. Underline
        // plus strike drawn as strike-only was the visible symptom.
        val doc = RichText(
            text = "both",
            marks = listOf(
                Mark(0, 4, TextMark.Underline),
                Mark(0, 4, TextMark.Strike),
            ),
        )

        val span = doc.annotated().spanStyles.single()

        assertTrue(TextDecoration.Underline in span.item.textDecoration!!)
        assertTrue(TextDecoration.LineThrough in span.item.textDecoration!!)
    }

    @Test
    fun `valued styles render as colour background and size`() {
        val doc = RichText(
            text = "styled",
            marks = listOf(
                Mark(0, 6, TextMark.TextColor("#e60000")),
                Mark(0, 6, TextMark.Highlight("#ffff00")),
                Mark(0, 6, TextMark.FontSize(24)),
            ),
        )

        val span = doc.annotated().spanStyles.single()

        assertEquals(hexColor("#e60000"), span.item.color)
        assertEquals(hexColor("#ffff00"), span.item.background)
        assertEquals(24f, span.item.fontSize.value)
    }

    @Test
    fun `overlapping marks split into correctly styled segments`() {
        // bold 0..6, red 3..9 over "overlapped": three segments, the middle
        // one carrying both.
        val doc = RichText(
            text = "overlapped",
            marks = listOf(
                Mark(0, 6, TextMark.Bold),
                Mark(3, 9, TextMark.TextColor("#0066cc")),
            ),
        )

        val spans = doc.annotated().spanStyles

        assertEquals(3, spans.size)
        assertEquals(FontWeight.Bold, spans[0].item.fontWeight)
        assertEquals(0 to 3, spans[0].start to spans[0].end)
        assertEquals(FontWeight.Bold, spans[1].item.fontWeight)
        assertEquals(hexColor("#0066cc"), spans[1].item.color)
        assertEquals(3 to 6, spans[1].start to spans[1].end)
        assertEquals(hexColor("#0066cc"), spans[2].item.color)
        assertEquals(6 to 9, spans[2].start to spans[2].end)
    }
}
