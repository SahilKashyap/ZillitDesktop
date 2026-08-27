package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.ui.ChartState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Layers tab's reading order.
 *
 * Tracking codes nest by `parentId`, unlike the nominal chart which nests by
 * code prefix — so depth has to be walked rather than read off the code. Two
 * things then matter: a code whose parent is inactive must still appear (it
 * is still postable, and a screen listing every dimension that quietly omits
 * one is worse than useless), and a parent chain that loops must not hang the
 * window.
 */
class TrackingLayersTest {

    private fun node(
        id: String,
        code: String,
        name: String = code,
        parentId: String? = null,
        active: Boolean = true,
    ) = TrackingNode(id = id, setId = "s1", code = code, name = name, parentId = parentId, isActive = active)

    private fun set(vararg nodes: TrackingNode) =
        TrackingSet(id = "s1", name = "Locations", code = "LOC", nodes = nodes.toList())

    private fun state(showInactive: Boolean = false) = ChartState(showInactive = showInactive)

    @Test
    fun `codes read parents before children`() {
        val tree = set(
            node("a", "LON", "London"),
            node("b", "LON-01", "Soho", parentId = "a"),
            node("c", "MAN", "Manchester"),
        )

        val rows = state().rows(tree)

        assertEquals(listOf("LON", "LON-01", "MAN"), rows.map { it.node.code })
        assertEquals(listOf(0, 1, 0), rows.map { it.depth })
    }

    @Test
    fun `siblings are ordered by code, not by wire order`() {
        val tree = set(node("c", "ZZZ"), node("a", "AAA"), node("b", "MMM"))

        assertEquals(listOf("AAA", "MMM", "ZZZ"), state().rows(tree).map { it.node.code })
    }

    @Test
    fun `depth follows the parent chain rather than the code`() {
        // The code says nothing about nesting here — only parentId does.
        val tree = set(
            node("a", "100"),
            node("b", "200", parentId = "a"),
            node("c", "300", parentId = "b"),
        )

        assertEquals(listOf(0, 1, 2), state().rows(tree).map { it.depth })
    }

    @Test
    fun `inactive codes are hidden until asked for`() {
        val tree = set(node("a", "LON"), node("b", "OLD", active = false))

        assertEquals(listOf("LON"), state().rows(tree).map { it.node.code })
        assertEquals(listOf("LON", "OLD"), state(showInactive = true).rows(tree).map { it.node.code })
    }

    /** Still postable, so still listed — flagged rather than dropped. */
    @Test
    fun `a code whose parent is inactive is surfaced as an orphan`() {
        val tree = set(
            node("a", "LON", active = false),
            node("b", "LON-01", "Soho", parentId = "a"),
        )

        val rows = state().rows(tree)

        assertEquals(listOf("LON-01"), rows.map { it.node.code })
        assertTrue(rows.single().orphaned)
        assertEquals(0, rows.single().depth, "an orphan reads at the top level")
    }

    @Test
    fun `a code whose parent is missing entirely is an orphan too`() {
        val tree = set(node("b", "LON-01", parentId = "ghost"))

        assertTrue(state().rows(tree).single().orphaned)
    }

    @Test
    fun `a properly parented code is not marked an orphan`() {
        val tree = set(node("a", "LON"), node("b", "LON-01", parentId = "a"))

        assertTrue(state().rows(tree).none { it.orphaned })
    }

    @Test
    fun `an orphan is not listed twice`() {
        val tree = set(node("a", "LON", active = false), node("b", "LON-01", parentId = "a"))

        assertEquals(1, state().rows(tree).size)
    }

    /** With inactives shown, the parent reappears and its child stops being an orphan. */
    @Test
    fun `showing inactives re-parents the orphan`() {
        val tree = set(node("a", "LON", active = false), node("b", "LON-01", parentId = "a"))

        val rows = state(showInactive = true).rows(tree)

        assertEquals(listOf("LON", "LON-01"), rows.map { it.node.code })
        assertEquals(listOf(0, 1), rows.map { it.depth })
        assertTrue(rows.none { it.orphaned })
    }

    /**
     * The tree comes from a server, so a parent chain that loops is possible.
     * A cycle must not hang the window — the walk is depth-capped, and the
     * looped codes then surface as orphans rather than vanishing.
     */
    @Test
    fun `a looping parent chain terminates`() {
        val tree = set(
            node("a", "AAA", parentId = "b"),
            node("b", "BBB", parentId = "a"),
            node("c", "CCC"),
        )

        val rows = state().rows(tree)

        assertEquals(3, rows.size, "nothing is lost to the loop")
        assertTrue(rows.first { it.node.code == "CCC" }.depth == 0)
        assertTrue(rows.filter { it.node.code != "CCC" }.all { it.orphaned })
    }

    @Test
    fun `an empty layer reads as empty rather than failing`() {
        assertTrue(state().rows(set()).isEmpty())
    }

    @Test
    fun `a deep chain is not truncated at a shallow depth`() {
        val nodes = (0 until 20).map { index ->
            node("n$index", "C$index", parentId = if (index == 0) null else "n${index - 1}")
        }

        val rows = state().rows(set(*nodes.toTypedArray()))

        assertEquals(20, rows.size)
        assertEquals(19, rows.last().depth)
        assertFalse(rows.any { it.orphaned })
    }
}
