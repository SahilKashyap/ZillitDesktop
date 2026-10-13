package com.zillit.desktop.core.workspace

import com.zillit.desktop.core.workspace.forward.ForwardGroup
import com.zillit.desktop.core.workspace.forward.ForwardPick
import com.zillit.desktop.core.workspace.forward.ForwardSection
import com.zillit.desktop.core.workspace.forward.ForwardStep
import com.zillit.desktop.core.workspace.forward.matching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The forward picker's one search box, over a list that is a tree: the web
 * has a search per list (users, groups), so a single field has to reach
 * every section — and a tool's submenu has to survive on its children's
 * names, not only its own.
 */
class ForwardSearchTest {

    private val sections = listOf(
        ForwardSection(
            "Units",
            listOf(
                ForwardPick(id = "unit:u1", label = "General"),
                ForwardPick(id = "unit:u2", label = "Camera"),
            ),
        ),
        ForwardSection(
            "People",
            listOf(ForwardPick(id = "user:u-aisha", label = "Aisha Khan", caption = "1st AD")),
        ),
        ForwardSection(
            "Tools",
            listOf(
                ForwardGroup(
                    id = "location",
                    label = "Location",
                    children = listOf(
                        ForwardStep(id = "location:selects", label = "Selects"),
                        ForwardStep(id = "location:final", label = "Final"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `an empty query keeps every section that has entries`() {
        assertEquals(3, sections.matching("   ").size)
        assertEquals(
            emptyList(),
            listOf(ForwardSection("Empty", emptyList())).matching(""),
        )
    }

    @Test
    fun `a query keeps only the sections with a hit, case blind`() {
        val found = sections.matching("cam")
        assertEquals(1, found.size)
        assertEquals("Units", found.single().title)
        assertEquals(listOf("unit:u2"), found.single().entries.map { it.id })
    }

    @Test
    fun `a caption is searched too — the designation people type`() {
        val found = sections.matching("1st")
        assertEquals(listOf("user:u-aisha"), found.single().entries.map { it.id })
    }

    @Test
    fun `a group survives on its own name, with every child kept`() {
        val group = sections.matching("locat").single().entries.single() as ForwardGroup
        assertEquals(2, group.children.size)
    }

    @Test
    fun `a group survives on a child's name, narrowed to that child`() {
        val group = sections.matching("final").single().entries.single() as ForwardGroup
        assertEquals(listOf("location:final"), group.children.map { it.id })
    }

    @Test
    fun `a query that matches nothing leaves nothing`() {
        assertTrue(sections.matching("zzz").isEmpty())
    }
}
