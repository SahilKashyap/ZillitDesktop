package com.zillit.desktop.feature.email

import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.email.ui.DEFAULT_LIST_FRACTION
import com.zillit.desktop.feature.email.ui.MIN_LIST_WIDTH
import com.zillit.desktop.feature.email.ui.MIN_READING_WIDTH
import com.zillit.desktop.feature.email.ui.listPaneWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the mailbox divides itself once a message is open.
 *
 * The rule is a fraction, but the fraction is not the whole rule: a drag can
 * ask for anything and a window can be any width, and neither may leave a pane
 * too narrow to use.
 */
class MailboxSplitTest {

    @Test
    fun `the listing keeps a quarter and the message gets the rest`() {
        assertEquals(250.dp, listPaneWidth(total = 1000.dp, fraction = DEFAULT_LIST_FRACTION))
    }

    @Test
    fun `a drag cannot squeeze the listing below what a row needs`() {
        // Dragged hard left. The bar stops rather than the rows collapsing to
        // a column of initials.
        assertEquals(MIN_LIST_WIDTH, listPaneWidth(total = 1400.dp, fraction = 0.01f))
    }

    @Test
    fun `a drag cannot squeeze the message out either`() {
        // Dragged hard right: the listing stops where the reading pane's own
        // minimum begins.
        assertEquals(1400.dp - MIN_READING_WIDTH, listPaneWidth(total = 1400.dp, fraction = 0.99f))
    }

    @Test
    fun `on a window too narrow for both minimums the listing takes its own`() {
        // 600dp cannot hold 260 + 400. The listing stops at its minimum and the
        // message keeps the remainder — which is still the larger share.
        val width = listPaneWidth(total = 600.dp, fraction = DEFAULT_LIST_FRACTION)

        assertEquals(MIN_LIST_WIDTH, width)
        assertTrue(600.dp - width > width, "the message should still be the wider pane, not $width of 600")
    }

    @Test
    fun `narrower than twice the listing minimum, the split is even`() {
        // No arrangement is good at 400dp. An even one is at least predictable,
        // and neither pane collapses to a sliver.
        assertEquals(200.dp, listPaneWidth(total = 400.dp, fraction = DEFAULT_LIST_FRACTION))
    }

    @Test
    fun `a quarter of a small window still clears the listing's minimum`() {
        // A quarter of 800 is 200, under the minimum — the listing takes the
        // minimum rather than a column too narrow to read.
        assertEquals(MIN_LIST_WIDTH, listPaneWidth(total = 800.dp, fraction = DEFAULT_LIST_FRACTION))
    }

    @Test
    fun `the split holds its share as the window is resized`() {
        // The fraction is what is remembered, not a width — a window dragged
        // wider must not leave the message pane where it was.
        listOf(900.dp, 1200.dp, 1600.dp, 2400.dp).forEach { total ->
            assertEquals(total * DEFAULT_LIST_FRACTION, listPaneWidth(total, DEFAULT_LIST_FRACTION))
        }
    }
}
