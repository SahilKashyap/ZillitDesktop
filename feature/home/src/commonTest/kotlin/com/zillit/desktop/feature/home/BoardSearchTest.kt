package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.nextMatchIndex
import com.zillit.desktop.feature.home.domain.searchMatches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Find-in-board. Client-side over the loaded list, like both other clients —
 * there is no search endpoint, and the bodies only exist in clear here anyway.
 */
class BoardSearchTest {

    private val board = listOf(
        Notice(id = "n1", body = "Crew call moved to 6am", authorName = "Sam Field"),
        Notice(
            id = "n2",
            body = "Lunch at base camp",
            authorName = "Alex",
            comments = listOf(NoticeComment(id = "c1", body = "the crew bus leaves at 5")),
        ),
        Notice(
            id = "n3",
            body = "",
            authorName = "Priya",
            attachment = NoticeAttachment(media = "k", fileName = "CallSheet_Day12.pdf"),
        ),
    )

    @Test
    fun `matches by body, case-insensitively`() {
        assertEquals(listOf("n1"), board.searchMatches("CREW CALL"))
    }

    @Test
    fun `a hit inside a reply lands on the parent post`() {
        // The parent is the thing on screen to scroll to.
        assertEquals(listOf("n2"), board.searchMatches("bus"))
    }

    @Test
    fun `sender names and file names are searchable`() {
        assertEquals(listOf("n1"), board.searchMatches("Sam"))
        assertEquals(listOf("n3"), board.searchMatches("Day12"))
    }

    @Test
    fun `one character is not a search`() {
        // A single letter matches everything and calls it success — iOS
        // requires two, and so does this.
        assertTrue(board.searchMatches("c").isEmpty())
        assertTrue(board.searchMatches(" c ").isEmpty())
        assertEquals(listOf("n1", "n2"), board.searchMatches("cr"))
    }

    @Test
    fun `order follows the board, and misses are empty not errors`() {
        assertEquals(listOf("n1", "n2"), board.searchMatches("crew"))
        assertTrue(board.searchMatches("wrap party").isEmpty())
    }

    @Test
    fun `stepping wraps at both ends`() {
        assertEquals(1, nextMatchIndex(0, 3, forward = true))
        assertEquals(0, nextMatchIndex(2, 3, forward = true), "past the end wraps to first")
        assertEquals(2, nextMatchIndex(0, 3, forward = false), "before the start wraps to last")
        assertEquals(0, nextMatchIndex(5, 0, forward = true), "no matches, no crash")
    }

    @Test
    fun `no matches means no current match, not a crash`() {
        // The exact composition-time crash from the first live session: the
        // getter ran with zero matches on every render of a board with the
        // search closed, and coerceIn(0, -1) threw.
        val state = com.zillit.desktop.feature.home.ui.HomeFeedUiState(notices = board)

        assertEquals(null, state.currentSearchMatch)
        assertEquals(
            null,
            state.copy(searchQuery = "wrap party", searchIndex = 3).currentSearchMatch,
        )
        assertEquals(
            "n1",
            state.copy(searchQuery = "crew call").currentSearchMatch,
        )
    }
}
