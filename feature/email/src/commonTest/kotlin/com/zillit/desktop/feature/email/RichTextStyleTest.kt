package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.EditHistory
import com.zillit.desktop.feature.email.domain.MarkFamily
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The valued styles — colour, highlight, size — and the undo history.
 *
 * The switches' behaviour is pinned in RichTextTest; these tests cover what
 * valued styles add: one value per family on a range, replacement instead of
 * stacking, and a picker's view of "the current value".
 */
class RichTextStyleTest {

    private val red = TextMark.TextColor("#e60000")
    private val blue = TextMark.TextColor("#0066cc")

    @Test
    fun `setting a colour over another replaces it`() {
        val doc = RichText.plain("call sheet")
            .set(red, 0, 10)
            .set(blue, 0, 10)

        assertTrue(doc.isApplied(blue, 0, 10))
        assertFalse(doc.marks.any { it.style == red }, "the old colour survived under the new one")
    }

    @Test
    fun `setting a colour over part of a run splits the rest`() {
        val doc = RichText.plain("call sheet")
            .set(red, 0, 10)
            .set(blue, 5, 10)

        assertTrue(doc.isApplied(red, 0, 5))
        assertTrue(doc.isApplied(blue, 5, 10))
        assertFalse(doc.isApplied(red, 5, 10))
    }

    @Test
    fun `re-picking the same colour stays picked`() {
        val doc = RichText.plain("call").set(red, 0, 4).set(red, 0, 4)

        assertTrue(doc.isApplied(red, 0, 4))
    }

    @Test
    fun `clear returns the family to default and leaves other families alone`() {
        val doc = RichText.plain("call sheet")
            .set(red, 0, 10)
            .toggle(TextMark.Bold, 0, 10)
            .clear(MarkFamily.TextColor, 0, 10)

        assertTrue(doc.marks.none { it.style == red })
        assertTrue(doc.isApplied(TextMark.Bold, 0, 10))
    }

    @Test
    fun `valueOf reports a uniform value and refuses a mixed range`() {
        val doc = RichText.plain("call sheet")
            .set(red, 0, 5)
            .set(blue, 5, 10)

        assertEquals(red, doc.valueOf(MarkFamily.TextColor, 0, 5))
        assertEquals(blue, doc.valueOf(MarkFamily.TextColor, 5, 10))
        assertNull(doc.valueOf(MarkFamily.TextColor, 0, 10), "a mixed range has no single value")
        assertNull(doc.valueOf(MarkFamily.Highlight, 0, 5))
    }

    @Test
    fun `valueOf at a caret answers from the run behind it`() {
        val doc = RichText.plain("call sheet").set(TextMark.FontSize(24), 0, 4)

        assertEquals(TextMark.FontSize(24), doc.valueOf(MarkFamily.FontSize, 4, 4))
        assertNull(doc.valueOf(MarkFamily.FontSize, 0, 0), "before the run is not inside it")
        assertNull(doc.valueOf(MarkFamily.FontSize, 7, 7))
    }

    @Test
    fun `strike toggles like the other switches`() {
        val doc = RichText.plain("call sheet").toggle(TextMark.Strike, 0, 4)

        assertTrue(doc.isApplied(TextMark.Strike, 0, 4))
        assertFalse(doc.toggle(TextMark.Strike, 0, 4).isApplied(TextMark.Strike, 0, 4))
    }

    @Test
    fun `editing text carries valued marks like switches`() {
        val doc = RichText.plain("call sheet").set(red, 5, 10)
        val edited = doc.withText("call the sheet")

        assertTrue(edited.isApplied(red, 9, 14))
    }

    @Test
    fun `undo returns the earlier document and redo brings the edit back`() {
        val before = RichText.plain("call")
        val after = before.toggle(TextMark.Bold, 0, 4)

        val history = EditHistory().record(before)

        val (afterUndo, restored) = history.undo(after)!!
        assertEquals(before, restored)
        assertTrue(afterUndo.canRedo)

        val (afterRedo, replayed) = afterUndo.redo(restored)!!
        assertEquals(after, replayed)
        assertFalse(afterRedo.canRedo)
    }

    @Test
    fun `a new edit after undo forks the timeline`() {
        val a = RichText.plain("a")
        val b = RichText.plain("ab")

        val history = EditHistory().record(a)
        val (afterUndo, _) = history.undo(b)!!

        val forked = afterUndo.record(a)
        assertFalse(forked.canRedo, "redo survived a new edit")
    }

    @Test
    fun `empty history has nothing to undo`() {
        assertNull(EditHistory().undo(RichText.plain("a")))
        assertNull(EditHistory().redo(RichText.plain("a")))
    }
}
