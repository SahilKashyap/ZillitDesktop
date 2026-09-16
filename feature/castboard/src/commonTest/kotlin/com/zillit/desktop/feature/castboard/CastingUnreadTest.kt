package com.zillit.desktop.feature.castboard

import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingBadgeLeaf
import com.zillit.desktop.feature.castboard.domain.CastingBadges
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.domain.CastingUnread
import kotlin.test.Test
import kotlin.test.assertEquals

class CastingUnreadTest {

    private val main = "casting_main_tool_label"

    private fun leaf(
        tool: String = main,
        status: CastingStatus = CastingStatus.Selected,
        character: String = "Rani",
        episode: String = "",
        entryId: String = "",
        unread: Int = 1,
    ) = CastingBadgeLeaf(tool, status, character, episode, entryId, unread)

    private val unread = CastingUnread(
        listOf(
            leaf(),
            leaf(episode = "2", unread = 2),
            leaf(entryId = "e-1"),
            leaf(character = "Raja"),
            leaf(tool = "casting_background_tool_label", status = CastingStatus.Published),
        ),
    )

    @Test
    fun `list and stage tabs sum their own rows`() {
        assertEquals(5, unread.tool(main))
        assertEquals(1, unread.tool("casting_background_tool_label"))
        assertEquals(5, unread.status(main, CastingStatus.Selected))
        assertEquals(0, unread.status(main, CastingStatus.Published))
    }

    @Test
    fun `an entry takes its character's folder rows, its episode's when both name one, and its own thread`() {
        val rani = CastingEntry(id = "e-1", characterName = "Rani")
        assertEquals(4, unread.entry(main, CastingStatus.Selected, rani))
        assertEquals(3, unread.entry(main, CastingStatus.Selected, rani.copy(id = "e-9", episode = "2")))
        assertEquals(1, unread.entry(main, CastingStatus.Selected, CastingEntry(id = "e-2", characterName = "Raja")))
    }

    @Test
    fun `units and tools spell as the service files them`() {
        assertEquals("casting_final_label", CastingBadges.unitOf(BoardTool.Casting, CastingStatus.Published))
        assertEquals("wardrobe_select_label", CastingBadges.unitOf(BoardTool.Wardrobe, CastingStatus.Selected))
        assertEquals(CastingStatus.Shortlisted, CastingBadges.statusOf(BoardTool.Wardrobe, "wardrobe_shortlist_label"))
        assertEquals("wardrobe_background_tool_label", CastingBadges.toolOf(BoardTool.Wardrobe.units[1]))
    }
}

class CastingOrphansTest {
    @Test
    fun `rows no listed entry answers for are the stage's orphans`() {
        val main = "casting_main_tool_label"
        val unread = CastingUnread(
            listOf(
                CastingBadgeLeaf(main, CastingStatus.Selected, "Rani", "", "", 1),
                CastingBadgeLeaf(main, CastingStatus.Selected, "Gone", "", "", 1),
                CastingBadgeLeaf(main, CastingStatus.Selected, "", "", "e-gone", 1),
                CastingBadgeLeaf(main, CastingStatus.Published, "Rani", "", "", 1),
            ),
        )
        val listed = listOf(CastingEntry(id = "e-1", characterName = "Rani"))
        val orphans = unread.orphans(main, CastingStatus.Selected, listed)
        assertEquals(setOf("Gone", ""), orphans.map { it.character }.toSet())
        assertEquals(2, orphans.size)
        assertEquals(emptyList(), unread.orphans(main, CastingStatus.Published, listed))
    }
}
