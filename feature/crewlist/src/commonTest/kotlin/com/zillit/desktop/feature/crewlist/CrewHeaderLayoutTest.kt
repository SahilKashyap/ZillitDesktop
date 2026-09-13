package com.zillit.desktop.feature.crewlist

import com.zillit.desktop.feature.crewlist.domain.HeaderArranger
import com.zillit.desktop.feature.crewlist.domain.HeaderLayout
import com.zillit.desktop.feature.crewlist.domain.HeaderSection.Company
import com.zillit.desktop.feature.crewlist.domain.HeaderSection.Logo
import com.zillit.desktop.feature.crewlist.domain.HeaderSection.Title
import com.zillit.desktop.feature.crewlist.domain.LayoutHistory
import com.zillit.desktop.feature.crewlist.domain.LogoAlign
import com.zillit.desktop.feature.crewlist.domain.SectionOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web drag layer's drop rules (`dropOnBlock`, `dropToGap`) and the undo history. */
class CrewHeaderLayoutTest {

    private val default = HeaderLayout.DEFAULT_ORDER

    @Test
    fun `dropping one of a pair onto the other swaps them wherever it lands`() {
        assertEquals(
            listOf(listOf(Company, Logo), listOf(Title)),
            HeaderArranger.dropOnSection(default, Logo, Company, before = true),
        )
        assertEquals(
            listOf(listOf(Company, Logo), listOf(Title)),
            HeaderArranger.dropOnSection(default, Company, Logo, before = false),
        )
    }

    @Test
    fun `joining a full row evicts its other member to the row below`() {
        // Title onto Logo's left: Company is pushed out to its own row under the pair.
        assertEquals(
            listOf(listOf(Title, Logo), listOf(Company)),
            HeaderArranger.dropOnSection(default, Title, Logo, before = true),
        )
    }

    @Test
    fun `joining a solo row pairs on the chosen side`() {
        val stacked = listOf(listOf(Title), listOf(Logo), listOf(Company))
        assertEquals(
            listOf(listOf(Logo, Title), listOf(Company)),
            HeaderArranger.dropOnSection(stacked, Title, Logo, before = false),
        )
    }

    @Test
    fun `a gap drop makes a full-width row, shifting only when a solo row collapses`() {
        val stacked = listOf(listOf(Title), listOf(Logo), listOf(Company))
        // Title's row collapses and was above the gap: the index shifts down by one.
        assertEquals(
            listOf(listOf(Logo), listOf(Title), listOf(Company)),
            HeaderArranger.dropInGap(stacked, Title, 2),
        )
        // Out of a pair nothing collapses, so the gap index stands.
        assertEquals(
            listOf(listOf(Company), listOf(Title), listOf(Logo)),
            HeaderArranger.dropInGap(default, Logo, 2),
        )
        assertEquals(
            listOf(listOf(Title), listOf(Logo, Company)),
            HeaderArranger.dropInGap(default, Title, 0),
        )
    }

    @Test
    fun `sanitise keeps each section once and appends the missing`() {
        assertEquals(
            listOf(listOf(Logo, Title), listOf(Company)),
            HeaderArranger.sanitise(listOf(listOf(Logo, Title, Logo), emptyList())),
        )
    }

    @Test
    fun `the logo gets its own cell once the order leaves the default`() {
        assertFalse(HeaderLayout().logoHasOwnCell)
        assertTrue(HeaderLayout(order = listOf(listOf(Title), listOf(Logo), listOf(Company))).logoHasOwnCell)
    }

    @Test
    fun `undo redo and reset walk the history`() {
        val moved = HeaderLayout(order = listOf(listOf(Title), listOf(Logo, Company)))
        var history = LayoutHistory().push(moved).push(moved.copy(logo = LogoAlign.Center))
        assertTrue(history.canUndo)
        history = history.undo()
        assertEquals(moved, history.current)
        assertTrue(history.canRedo)
        history = history.redo()
        assertEquals(LogoAlign.Center, history.current.logo)

        history = history.reset()
        assertEquals(HeaderLayout(), history.current)
        assertFalse(history.current.isCustomised)
        assertEquals(history, history.reset(), "resetting the default is a no-op")
        assertEquals(moved.copy(logo = LogoAlign.Center), history.undo().current, "reset is one undoable step")
    }

    @Test
    fun `a nudge back to zero is the default again`() {
        val nudged = HeaderLayout().let { it.copy(offsets = it.offsets + (Logo to SectionOffset(4, -2))) }
        assertTrue(nudged.isCustomised)
        assertFalse(nudged.copy(offsets = nudged.offsets + (Logo to SectionOffset())).isCustomised)
    }
}
