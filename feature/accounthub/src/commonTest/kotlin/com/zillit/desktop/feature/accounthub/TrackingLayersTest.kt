package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.TrackingSets
import com.zillit.desktop.feature.accounthub.ui.ChartState
import com.zillit.desktop.feature.accounthub.ui.LayerDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Layers tab — the web's `TrackingCodesTab`.
 *
 * The tab is flat, as the web's v1 is: every code of a set in one list, by the
 * set's stored order and then by code. The schema's `parent_id` is reserved for
 * a deeper tree later and is neither drawn nor written, so a code that carries
 * one must still be listed — nesting it under a parent the tab never shows
 * would hide it.
 */
class TrackingLayersTest {

    private fun node(
        id: String,
        code: String,
        order: Int = 0,
        parentId: String? = null,
        active: Boolean = true,
    ) = TrackingNode(
        id = id,
        setId = "s1",
        code = code,
        name = code,
        parentId = parentId,
        isActive = active,
        sortOrder = order,
    )

    private fun set(vararg nodes: TrackingNode) =
        TrackingSet(id = "s1", name = "Locations", code = "LOC", nodes = nodes.toList())

    @Test
    fun `codes read by stored order, then by code`() {
        val layer = set(node("a", "ZZZ", order = 0), node("b", "AAA", order = 2), node("c", "MMM", order = 0))

        assertEquals(listOf("MMM", "ZZZ", "AAA"), ChartState().layerNodes(layer).map { it.code })
    }

    /** Unlike the chart's toggle, nothing hides a disabled code here — it is chipped "Disabled" instead. */
    @Test
    fun `every code is listed, disabled ones included`() {
        val layer = set(node("a", "LON"), node("b", "MAD", active = false))

        assertEquals(2, ChartState(showInactive = false).layerNodes(layer).size)
    }

    @Test
    fun `a code with a parent is listed flat rather than hidden beneath it`() {
        val layer = set(node("a", "LON"), node("b", "LON-01", parentId = "a"), node("c", "SOHO", parentId = "gone"))

        assertEquals(listOf("LON", "LON-01", "SOHO"), ChartState().layerNodes(layer).map { it.code })
    }

    @Test
    fun `an empty layer reads as empty rather than failing`() {
        assertEquals(emptyList(), ChartState().layerNodes(set()))
    }

    /** The confirmation counts what a set's delete takes with it; a code's names the code. */
    @Test
    fun `delete confirmations say what goes`() {
        val layer = set(node("a", "LON"), node("b", "MAD"))

        assertEquals(
            "Delete \"Locations\" and its 2 codes? This cannot be undone.",
            LayerDelete.WholeSet(layer).message,
        )
        assertEquals("Delete \"Locations\"?", LayerDelete.WholeSet(set()).message)
        assertEquals("Delete \"LON\"?", LayerDelete.OneNode("s1", node("a", "LON")).message)
        assertEquals("Can't delete this layer", LayerDelete.WholeSet(layer).refusalTitle)
        assertEquals("Can't delete this code", LayerDelete.OneNode("s1", node("a", "LON")).refusalTitle)
    }

    @Test
    fun `a prefix is upper-cased letters and digits, ten at most, and optional`() {
        assertEquals("LOCEUR1234", TrackingSets.normalisePrefix("loc-eur 12345"))
        assertNull(TrackingSets.setProblem("Locations", ""))
        assertEquals("A prefix is 2–10 letters or digits.", TrackingSets.setProblem("Locations", "L"))
        assertEquals("Give the layer a name.", TrackingSets.setProblem(" ", "LOC"))
    }

    @Test
    fun `a new layer takes the palette's next colour`() {
        assertEquals("#FB923C", TrackingSets.colorFor(0))
        assertEquals("#FB923C", TrackingSets.colorFor(TrackingSets.DEFAULT_COLORS.size))
    }
}
