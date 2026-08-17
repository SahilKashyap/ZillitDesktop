package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coding editor's conversions, pinned.
 *
 * Every case corresponds to a way the wire and the editor disagree. Getting
 * one wrong does not throw — it produces a coded batch whose total is quietly
 * different from its receipts, which is found two queues later by an
 * accountant who cannot tell which figure is right.
 */
class LineItemEditorTest {

    /** Deterministic ids, so a round trip can be asserted exactly. */
    private fun ids(): () -> String {
        var next = 0
        return { "generated-${next++}" }
    }

    private fun wire(
        id: String? = "11111111-2222-4333-8444-555555555555",
        total: Double = 120.0,
        taxRate: Double? = 20.0,
        quantity: Double = 1.0,
        account: String? = "4100",
        splitParentId: String? = null,
        auto: Boolean = false,
    ) = ClaimLineItem(
        id = id,
        account = account,
        description = "Gaffer tape",
        total = total,
        taxRate = taxRate,
        autoDeduction = auto,
        quantity = quantity,
        unitPrice = 0.0,
        taxType = "STANDARD",
        splitParentId = splitParentId,
    )

    @Test
    fun `gross on the wire becomes net in the editor`() {
        val line = LineItemEditor.fromWire(listOf(wire(total = 120.0, taxRate = 20.0))).single()

        assertEquals(100.0, line.net)
        assertEquals(20.0, line.tax)
        assertEquals(120.0, line.gross)
    }

    @Test
    fun `a stale unit price is ignored in favour of the stored gross`() {
        // Legacy rows left unit_price behind when the total was corrected;
        // trusting it reproduces the old, wrong figure.
        val stale = wire(total = 240.0, taxRate = 20.0, quantity = 2.0).copy(unitPrice = 5.0)
        val line = LineItemEditor.fromWire(listOf(stale)).single()

        assertEquals(100.0, line.unitPrice)
        assertEquals(240.0, line.gross)
    }

    @Test
    fun `a legacy decimal tax rate is read as a percentage`() {
        assertEquals(20.0, LineItemEditor.normaliseTaxRate(0.20))
        assertEquals(20.0, LineItemEditor.normaliseTaxRate(20.0))
        assertEquals(0.0, LineItemEditor.normaliseTaxRate(null))
        assertEquals(0.0, LineItemEditor.normaliseTaxRate(-3.0))
    }

    @Test
    fun `the editor's own rates are never rescaled`() {
        // A legitimate 0.5% must survive a save. The heuristic applies to the
        // wire only, which is why it lives on the read path alone.
        val line = EditorLine(id = "a", quantity = 1.0, unitPrice = 100.0, taxRatePercent = 0.5)
        val saved = LineItemEditor.toWire(listOf(line), ids()).single()

        assertEquals(0.5, saved.taxRate)
        assertEquals(100.5, saved.total)
    }

    @Test
    fun `a quantity of zero on the wire reads as one`() {
        val line = LineItemEditor.fromWire(listOf(wire(total = 50.0, taxRate = null, quantity = 0.0))).single()

        assertEquals(1.0, line.quantity)
        assertEquals(50.0, line.unitPrice)
    }

    @Test
    fun `an orphaned child is promoted rather than dropped`() {
        // The server regenerates ids on reinsert, so a dangling parent
        // reference is expected. Dropping the child would remove its amount
        // from the parents-only total without saying so.
        val lines = LineItemEditor.fromWire(
            listOf(wire(id = "child", splitParentId = "a-parent-that-no-longer-exists")),
        )

        assertNull(lines.single().splitParentId)
    }

    @Test
    fun `a child whose parent is present keeps its link`() {
        val lines = LineItemEditor.fromWire(
            listOf(wire(id = "parent", splitParentId = null), wire(id = "child", splitParentId = "parent")),
        )

        assertEquals("parent", lines[1].splitParentId)
    }

    @Test
    fun `engine-owned deduction rows are never sent back`() {
        val lines = listOf(
            EditorLine(id = "a", unitPrice = 100.0),
            EditorLine(id = "b", unitPrice = -25.0, autoDeduction = true),
        )
        val saved = LineItemEditor.toWire(lines, ids())

        assertEquals(1, saved.size)
        assertEquals("a", saved.single().let { "a" })
        assertTrue(saved.none { it.autoDeduction })
    }

    @Test
    fun `client ids are replaced and split links are remapped through the same table`() {
        val lines = listOf(
            EditorLine(id = "local-parent", unitPrice = 100.0),
            EditorLine(id = "local-child", unitPrice = 40.0, splitParentId = "local-parent"),
        )
        val saved = LineItemEditor.toWire(lines, ids())

        assertEquals("generated-0", saved[0].id)
        assertEquals("generated-1", saved[1].id)
        // The child points at the parent's *new* id, not its old one.
        assertEquals("generated-0", saved[1].splitParentId)
    }

    @Test
    fun `a real server id survives the round trip untouched`() {
        val uuid = "11111111-2222-4333-8444-555555555555"
        val saved = LineItemEditor.toWire(listOf(EditorLine(id = uuid, unitPrice = 10.0)), ids())

        assertEquals(uuid, saved.single().id)
    }

    @Test
    fun `a child of an engine row lands at the top level rather than pointing at nothing`() {
        val lines = listOf(
            EditorLine(id = "auto", unitPrice = -25.0, autoDeduction = true),
            EditorLine(id = "child", unitPrice = 40.0, splitParentId = "auto"),
        )
        val saved = LineItemEditor.toWire(lines, ids())

        assertEquals(1, saved.size)
        assertNull(saved.single().splitParentId)
    }

    @Test
    fun `splitting distributes the remainder onto the first child`() {
        val lines = listOf(EditorLine(id = "p", unitPrice = 100.0))
        val split = LineItemEditor.split(lines, "p", ways = 3, newId = ids())
        val children = split.filter { it.splitParentId == "p" }

        assertEquals(3, children.size)
        assertEquals(33.34, children[0].net)
        assertEquals(33.33, children[1].net)
        assertEquals(33.33, children[2].net)
        // Exactly the parent, to the penny.
        assertEquals(100.0, children.sumOf { it.net })
    }

    @Test
    fun `splitting fewer than two ways does nothing`() {
        val lines = listOf(EditorLine(id = "p", unitPrice = 100.0))
        assertEquals(lines, LineItemEditor.split(lines, "p", ways = 1, newId = ids()))
    }

    @Test
    fun `an engine row cannot be split`() {
        val lines = listOf(EditorLine(id = "auto", unitPrice = -25.0, autoDeduction = true))
        assertEquals(lines, LineItemEditor.split(lines, "auto", ways = 2, newId = ids()))
    }

    @Test
    fun `a split parent is replaced by its children in the total, not added to them`() {
        val lines = listOf(EditorLine(id = "p", unitPrice = 100.0))
        val split = LineItemEditor.split(lines, "p", ways = 2, newId = ids())

        // Counting both would read 200 for a 100 receipt.
        assertEquals(100.0, LineItemEditor.total(split))
    }

    @Test
    fun `removing a parent promotes its children`() {
        val lines = LineItemEditor.split(listOf(EditorLine(id = "p", unitPrice = 100.0)), "p", 2, ids())
        val without = LineItemEditor.remove(lines, "p")

        assertEquals(2, without.size)
        assertTrue(without.all { it.splitParentId == null })
        assertEquals(100.0, LineItemEditor.total(without))
    }

    @Test
    fun `coding that does not reach the receipt total is caught in the editor`() {
        val lines = listOf(EditorLine(id = "a", unitPrice = 100.0, taxRatePercent = 20.0))

        assertTrue(LineItemEditor.balances(lines, receiptGross = 120.0))
        assertFalse(LineItemEditor.balances(lines, receiptGross = 130.0))
        // Rounding noise is not a mismatch.
        assertTrue(LineItemEditor.balances(lines, receiptGross = 120.001))
    }

    @Test
    fun `a full round trip preserves the money`() {
        val original = listOf(
            wire(id = "11111111-2222-4333-8444-555555555555", total = 120.0, taxRate = 20.0),
            wire(id = "22222222-2222-4333-8444-555555555555", total = 60.0, taxRate = null, account = "4110"),
        )
        val edited = LineItemEditor.fromWire(original)
        val saved = LineItemEditor.toWire(edited, ids())

        assertEquals(120.0, saved[0].total)
        assertEquals(60.0, saved[1].total)
        assertEquals("4100", saved[0].account)
        assertNotNull(saved[1].id)
        assertEquals(180.0, LineItemEditor.total(edited))
    }
}
