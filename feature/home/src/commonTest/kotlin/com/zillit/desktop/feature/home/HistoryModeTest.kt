package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.forDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The call sheet's history view.
 *
 * The same board in a different mode. The ordering key is the substance: the
 * live board follows `updated` so an edit surfaces, history follows `created`
 * so the record of what went out when does not reshuffle.
 */
class HistoryModeTest {

    private fun notice(id: String, created: Long, updated: Long = 0) =
        Notice(id = id, body = "b", authorName = "A", createdAtMillis = created, updatedAtMillis = updated)

    @Test
    fun `the live board follows the last edit`() {
        // An old post edited just now belongs at the bottom, where people
        // reading will see it changed.
        val board = listOf(
            notice("old-but-edited", created = 100, updated = 900),
            notice("recent", created = 500, updated = 500),
        ).forDisplay()

        assertEquals(listOf("recent", "old-but-edited"), board.map { it.id })
    }

    @Test
    fun `history follows publication order`() {
        // The same two posts, as a record: the edit must not move anything.
        val history = listOf(
            notice("old-but-edited", created = 100, updated = 900),
            notice("recent", created = 500, updated = 500),
        ).forDisplay(history = true)

        assertEquals(listOf("old-but-edited", "recent"), history.map { it.id })
    }

    @Test
    fun `a post with no updated timestamp falls back to created`() {
        // Otherwise a zero sorts to the very top and an old post leads the board.
        val board = listOf(notice("a", created = 300), notice("b", created = 100)).forDisplay()

        assertEquals(listOf("b", "a"), board.map { it.id })
    }

    @Test
    fun `pinned still wins in both modes`() {
        val posts = listOf(
            notice("pinned", created = 10).copy(isPinned = true),
            notice("newer", created = 900, updated = 900),
        )

        assertEquals("pinned", posts.forDisplay().first().id)
        assertEquals("pinned", posts.forDisplay(history = true).first().id)
    }
}
