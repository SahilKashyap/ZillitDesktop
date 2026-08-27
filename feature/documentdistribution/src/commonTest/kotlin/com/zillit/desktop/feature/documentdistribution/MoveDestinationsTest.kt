package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a move may land.
 *
 * The rule that matters is the exclusion: a folder dropped into its own
 * subtree detaches that branch from the root, and every document inside it
 * becomes reachable from nothing — no error, no warning, just a chunk of the
 * library gone from the tree. The web offers every folder and lets it happen;
 * this list never offers the ones that would.
 */
class MoveDestinationsTest {

    /**
     * Library
     *  ├ Call sheets
     *  │   └ Week 1
     *  │       └ Monday
     *  └ Scripts
     */
    private val tree = listOf(
        LibraryFolder(id = "calls", name = "Call sheets"),
        LibraryFolder(id = "w1", name = "Week 1", parentId = "calls"),
        LibraryFolder(id = "mon", name = "Monday", parentId = "w1"),
        LibraryFolder(id = "scripts", name = "Scripts"),
    )

    private fun state(vararg selectedFolders: String) = DocDistUiState(
        folders = tree,
        selectedFolderIds = selectedFolders.toSet(),
    )

    @Test
    fun `with nothing selected every folder is a destination`() {
        val offered = state().moveDestinations()

        assertEquals(listOf("Call sheets", "Week 1", "Monday", "Scripts"), offered.map { it.name })
    }

    @Test
    fun `destinations are depth-ordered so the tree reads as a tree`() {
        val offered = state().moveDestinations()

        assertEquals(listOf(0, 1, 2, 0), offered.map { it.depth })
    }

    @Test
    fun `siblings are listed alphabetically, not in wire order`() {
        val shuffled = DocDistUiState(
            folders = listOf(
                LibraryFolder(id = "z", name = "Zulu"),
                LibraryFolder(id = "a", name = "alpha"),
                LibraryFolder(id = "m", name = "Mike"),
            ),
        )

        assertEquals(listOf("alpha", "Mike", "Zulu"), shuffled.moveDestinations().map { it.name })
    }

    @Test
    fun `a folder being moved is not offered as its own destination`() {
        val offered = state("calls").moveDestinations().map { it.id }

        assertFalse("calls" in offered)
    }

    /** The whole branch goes, not just the folder itself. */
    @Test
    fun `the descendants of a moved folder are not offered either`() {
        val offered = state("calls").moveDestinations().map { it.id }

        assertEquals(listOf("scripts"), offered, "Week 1 and Monday are inside Call sheets")
    }

    @Test
    fun `moving a leaf still leaves its ancestors available`() {
        val offered = state("mon").moveDestinations().map { it.id }

        assertEquals(listOf("calls", "w1", "scripts"), offered)
    }

    @Test
    fun `moving two folders bars both subtrees`() {
        val offered = state("calls", "scripts").moveDestinations()

        assertTrue(offered.isEmpty(), "only the root is left, and the root is not in this list")
    }

    @Test
    fun `moving a folder does not bar an unrelated branch`() {
        val offered = state("scripts").moveDestinations().map { it.id }

        assertEquals(listOf("calls", "w1", "mon"), offered)
    }

    /**
     * The tree comes from a server, so a parent chain that loops is possible.
     * A cycle must not hang the window — the walk is depth-capped.
     */
    @Test
    fun `a looping parent chain terminates instead of hanging`() {
        val looped = DocDistUiState(
            folders = listOf(
                LibraryFolder(id = "a", name = "A", parentId = "b"),
                LibraryFolder(id = "b", name = "B", parentId = "a"),
                LibraryFolder(id = "ok", name = "Reachable"),
            ),
        )

        assertEquals(listOf("Reachable"), looped.moveDestinations().map { it.name })
    }

    /** A cycle among the *selected* folders must not loop the exclusion walk either. */
    @Test
    fun `barring a looped selection terminates`() {
        val looped = DocDistUiState(
            folders = listOf(
                LibraryFolder(id = "a", name = "A", parentId = "b"),
                LibraryFolder(id = "b", name = "B", parentId = "a"),
                LibraryFolder(id = "ok", name = "Reachable"),
            ),
            selectedFolderIds = setOf("a"),
        )

        assertEquals(listOf("Reachable"), looped.moveDestinations().map { it.name })
    }

    @Test
    fun `the selection counts folders and documents together`() {
        val mixed = DocDistUiState(
            folders = tree,
            selectedFolderIds = setOf("calls"),
            selectedDocumentIds = setOf("d1", "d2"),
        )

        assertEquals(3, mixed.selectionCount)
        assertEquals(0, DocDistUiState().selectionCount)
    }
}
