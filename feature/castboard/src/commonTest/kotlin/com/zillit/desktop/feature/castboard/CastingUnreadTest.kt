package com.zillit.desktop.feature.castboard

import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingBadgeLeaf
import com.zillit.desktop.feature.castboard.domain.CastingBadges
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.domain.CastingUnread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastingUnreadTest {

    private val main = "casting_main_tool_label"

    private fun leaf(
        tool: String = main,
        status: CastingStatus = CastingStatus.Selected,
        character: String = "Rani",
        episode: String = "",
        entryId: String = "",
        unread: Int = 1,
    ) = CastingBadgeLeaf(tool, status, character, episode, entryId, unread, action = "casting_created")

    private fun CastingBadgeLeaf.at(folder: String, action: String = this.action) =
        copy(folder = folder, action = action)

    private val unread = CastingUnread(
        listOf(
            leaf(),
            leaf(episode = "2", unread = 2),
            leaf(entryId = "e-1"),
            leaf(character = "Raja"),
            leaf(tool = "casting_background_tool_label", status = CastingStatus.Finalized),
        ),
    )

    @Test
    fun `list and stage tabs sum their own rows`() {
        assertEquals(5, unread.tool(main))
        assertEquals(1, unread.tool("casting_background_tool_label"))
        assertEquals(5, unread.status(main, CastingStatus.Selected))
        assertEquals(0, unread.status(main, CastingStatus.Finalized))
    }

    @Test
    fun `an entry takes its character's folder rows, its episode's when both name one, and its own thread`() {
        val rani = CastingEntry(id = "e-1", characterName = "Rani")
        assertEquals(4, unread.entry(main, CastingStatus.Selected, rani))
        assertEquals(3, unread.entry(main, CastingStatus.Selected, rani.copy(id = "e-9", episode = "2")))
        assertEquals(1, unread.entry(main, CastingStatus.Selected, CastingEntry(id = "e-2", characterName = "Raja")))
    }

    @Test
    fun `a casting folder is its character and talent, a wardrobe one its character and scene`() {
        val talented = CastingUnread(
            listOf(
                leaf().at("Asha"),
                leaf(unread = 2).at("Meera"),
                leaf().at(""),
            ),
        )
        val asha = CastingEntry(id = "e-1", characterName = "Rani", talentNames = listOf("Asha", "Kiran"))
        assertEquals(2, talented.entry(main, CastingStatus.Selected, asha), "Asha's row and the talent-less one")
        assertEquals(1, talented.entry(main, CastingStatus.Selected, asha.copy(talentNames = emptyList())))

        val wardrobe = "wardrobe_main_tool_label"
        val costumes = CastingUnread(
            listOf(
                leaf(tool = wardrobe).at("12", action = "wardrobe_created"),
                leaf(tool = wardrobe).at("14", action = "wardrobe_created"),
            ),
            board = BoardTool.Wardrobe,
        )
        val costume = CastingEntry(
            id = "w-1",
            characterName = "Rani",
            talentNames = listOf("12"),
            sceneNumbers = listOf("14", "15"),
        )
        assertEquals(1, costumes.entry(wardrobe, CastingStatus.Selected, costume), "wardrobe keys by the first scene")
        assertEquals("14", CastingBadges.folderOf(BoardTool.Wardrobe, costume))
        assertEquals("12", CastingBadges.folderOf(BoardTool.Casting, costume))
    }

    @Test
    fun `opening an entry reads its folder rows, never a sibling's comments`() {
        val asha = CastingEntry(id = "e-1", characterName = "Rani", talentNames = listOf("Asha"))
        val board = BoardTool.Casting

        assertTrue(CastingBadges.readsWithEntry(board, leaf().at("Asha"), asha))
        assertTrue(CastingBadges.readsWithEntry(board, leaf().at("Asha", action = "casting_moved"), asha))
        val updated = leaf(entryId = "e-2").at("Asha", action = "casting_updated")
        assertTrue(CastingBadges.readsWithEntry(board, updated, asha), "an updated row is the folder's, as on iOS")
        val comment = leaf(entryId = "e-2").at("Asha", action = "casting_new_unit_chat")
        assertFalse(CastingBadges.readsWithEntry(board, comment, asha), "a comment on a sibling entry survives")
        assertFalse(CastingBadges.readsWithEntry(board, leaf().at("Meera"), asha))
        assertEquals(setOf("wardrobe_created", "wardrobe_updated"), CastingBadges.readActions(BoardTool.Wardrobe))
    }

    @Test
    fun `units and tools spell as the service files them`() {
        assertEquals("casting_final_label", CastingBadges.unitOf(BoardTool.Casting, CastingStatus.Finalized))
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
                CastingBadgeLeaf(main, CastingStatus.Finalized, "Rani", "", "", 1),
            ),
        )
        val listed = listOf(CastingEntry(id = "e-1", characterName = "Rani"))
        val orphans = unread.orphans(main, CastingStatus.Selected, listed)
        assertEquals(setOf("Gone", ""), orphans.map { it.character }.toSet())
        assertEquals(2, orphans.size)
        assertEquals(emptyList(), unread.orphans(main, CastingStatus.Finalized, listed))
    }
}
