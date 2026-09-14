package com.zillit.desktop.feature.email

import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.email.ui.LIST_MAX
import com.zillit.desktop.feature.email.ui.LIST_MIN
import com.zillit.desktop.feature.email.ui.PANE_MIN
import com.zillit.desktop.feature.email.ui.listPaneWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the mailbox divides itself between the message list and the pane.
 *
 * The web remembers a pixel width the user dragged to, inside its bounds
 * (280–520); the desktop keeps the same bounds and adds one rule the browser
 * gets from its viewport for free: a window can be any width, and neither
 * pane may be left too narrow to use.
 */
class MailboxSplitTest {

    @Test
    fun `a dragged width inside the bounds is kept as dragged`() {
        assertEquals(360.dp, listPaneWidth(total = 1400.dp, wanted = 360.dp))
    }

    @Test
    fun `a drag cannot squeeze the listing below what a row needs`() {
        // Dragged hard left. The bar stops rather than the rows collapsing to
        // a column of initials.
        assertEquals(LIST_MIN, listPaneWidth(total = 1400.dp, wanted = 10.dp))
    }

    @Test
    fun `a drag cannot widen the listing past the web's ceiling`() {
        assertEquals(LIST_MAX, listPaneWidth(total = 2400.dp, wanted = 900.dp))
    }

    @Test
    fun `a drag cannot squeeze the message out either`() {
        // Dragged hard right on a window that cannot fit the ceiling: the
        // listing stops where the reading pane's own minimum begins.
        assertEquals(800.dp - PANE_MIN, listPaneWidth(total = 800.dp, wanted = 500.dp))
    }

    @Test
    fun `on a window too narrow for both minimums the listing yields first`() {
        // 600dp cannot hold 280 + 400. The listing stops at its minimum and the
        // message keeps the remainder — which is still the larger share.
        val width = listPaneWidth(total = 600.dp, wanted = 400.dp)

        assertEquals(LIST_MIN, width)
        assertTrue(600.dp - width > width, "the message should still be the wider pane, not $width of 600")
    }

    @Test
    fun `narrower than twice the listing minimum, the split is even`() {
        // No arrangement is good at 400dp. An even one is at least predictable,
        // and neither pane collapses to a sliver.
        assertEquals(200.dp, listPaneWidth(total = 400.dp, wanted = 300.dp))
    }

    @Test
    fun `the remembered width survives a resize that has room for it`() {
        // A width is what is remembered, not a share — a window dragged wider
        // must not move the divider the user placed.
        listOf(900.dp, 1200.dp, 1600.dp, 2400.dp).forEach { total ->
            assertEquals(320.dp, listPaneWidth(total, wanted = 320.dp))
        }
    }
}
