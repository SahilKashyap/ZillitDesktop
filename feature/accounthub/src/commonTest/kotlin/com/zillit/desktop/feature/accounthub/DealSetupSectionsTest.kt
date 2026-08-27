package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.pages.moved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two Deal Memo Setup sections whose data layer shipped without a screen.
 *
 * Standard deal conditions are ordered, and their order is positional rather
 * than stored — the server keeps whatever sequence it is handed, so a list
 * edited by delete or reorder has to be renumbered before it goes, or the
 * gaps become the real order on the next read.
 */
class DealSetupSectionsTest {

    private fun conditions(vararg text: String) =
        text.mapIndexed { index, body -> DealCondition("c$index", index + 1, body) }

    /** Mirrors the renumbering the view model applies on save. */
    private fun List<DealCondition>.renumbered() =
        mapIndexed { index, condition -> condition.copy(order = index + 1) }

    @Test
    fun `moving a clause up swaps it with the one above`() {
        val order = conditions("First", "Second", "Third").moved(from = 1, delta = -1)

        assertEquals(listOf("Second", "First", "Third"), order.map { it.condition })
    }

    @Test
    fun `moving a clause down swaps it with the one below`() {
        val order = conditions("First", "Second", "Third").moved(from = 0, delta = 1)

        assertEquals(listOf("Second", "First", "Third"), order.map { it.condition })
    }

    /** The buttons at the ends are inert rather than wrong. */
    @Test
    fun `moving off either end changes nothing`() {
        val list = conditions("First", "Second")

        assertEquals(list, list.moved(from = 0, delta = -1))
        assertEquals(list, list.moved(from = 1, delta = 1))
        assertEquals(list, list.moved(from = 5, delta = -1), "an index off the list is ignored")
    }

    @Test
    fun `an empty list survives a move`() {
        assertEquals(emptyList(), emptyList<DealCondition>().moved(from = 0, delta = 1))
    }

    @Test
    fun `renumbering closes the gap a deletion leaves`() {
        val afterDelete = conditions("First", "Second", "Third").filterNot { it.condition == "Second" }

        assertEquals(listOf(1, 3), afterDelete.map { it.order }, "the gap is real until it is closed")
        assertEquals(listOf(1, 2), afterDelete.renumbered().map { it.order })
    }

    @Test
    fun `renumbering follows a reorder rather than the stored order`() {
        val reordered = conditions("First", "Second", "Third").moved(from = 2, delta = -2)

        assertEquals(listOf("Third", "First", "Second"), reordered.map { it.condition })
        assertEquals(listOf(1, 2, 3), reordered.renumbered().map { it.order })
        assertEquals("Third", reordered.renumbered().first { it.order == 1 }.condition)
    }

    @Test
    fun `renumbering leaves the text and ids alone`() {
        val list = conditions("First", "Second")

        assertEquals(list.map { it.id }, list.renumbered().map { it.id })
        assertEquals(list.map { it.condition }, list.renumbered().map { it.condition })
    }

    // -- section editing ------------------------------------------------------

    @Test
    fun `a section is clean until it is edited`() {
        val section = SectionEdit(conditions("First"))

        assertFalse(section.dirty)
        assertTrue(section.edit(conditions("First", "Second")).dirty)
    }

    /**
     * A new clause carries no id: the server mints one and echoes it back,
     * and the section re-snapshots from that echo rather than from what it
     * sent — otherwise it stays dirty forever against a value it cannot reach.
     */
    @Test
    fun `a new clause is committed from the server's echo, not the draft`() {
        val drafted = SectionEdit(emptyList<DealCondition>())
            .edit(listOf(DealCondition(id = "", order = 1, condition = "Overtime after 10h")))
        val echoed = listOf(DealCondition(id = "c-99", order = 1, condition = "Overtime after 10h"))

        val committed = drafted.committed(echoed)

        assertFalse(committed.dirty, "the echo is the new truth")
        assertEquals("c-99", committed.saved.single().id)
    }

    @Test
    fun `reverting a section throws the edits away`() {
        val section = SectionEdit(conditions("First")).edit(conditions("First", "Second"))

        assertEquals(conditions("First"), section.reverted().edited)
        assertFalse(section.reverted().dirty)
    }

    /**
     * A load arriving while a section is being edited updates what the edits
     * are measured against, without discarding them.
     */
    @Test
    fun `a reload behind an unsaved edit keeps the edit`() {
        val editing = SectionEdit(conditions("First")).edit(conditions("First", "Second"))

        val reloaded = editing.loaded(conditions("First", "Changed elsewhere"))

        assertEquals(2, reloaded.edited.size)
        assertEquals("Second", reloaded.edited.last().condition, "the local edit survives")
        assertTrue(reloaded.dirty)
    }

    @Test
    fun `a reload with nothing being edited takes the new value whole`() {
        val clean = SectionEdit(conditions("First"))

        val reloaded = clean.loaded(conditions("First", "Second"))

        assertEquals(2, reloaded.edited.size)
        assertFalse(reloaded.dirty)
    }

    // -- payroll bureaux ------------------------------------------------------

    @Test
    fun `a bureau needs no description`() {
        val bureau = PayrollBureau(id = "b1", title = "Sargent-Disc")

        assertEquals("", bureau.description)
    }

    @Test
    fun `removing one bureau leaves the others`() {
        val bureaus = listOf(
            PayrollBureau("b1", "Sargent-Disc"),
            PayrollBureau("b2", "Entertainment Partners"),
            PayrollBureau("b3", "Cast & Crew"),
        )

        val kept = bureaus.filterIndexed { at, _ -> at != 1 }

        assertEquals(listOf("b1", "b3"), kept.map { it.id })
    }
}
