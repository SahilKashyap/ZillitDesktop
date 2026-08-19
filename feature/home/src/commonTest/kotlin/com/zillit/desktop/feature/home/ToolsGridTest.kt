package com.zillit.desktop.feature.home

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.ui.ToolSection
import com.zillit.desktop.feature.home.ui.matching
import com.zillit.desktop.feature.home.ui.moved
import com.zillit.desktop.feature.home.ui.orderedBy
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tools grid's search filter and the user's own section order. */
class ToolsGridTest {

    private fun tool(label: String) =
        ToolPresentation(label.lowercase(), label, ZillitIcons.Tools, WorkspaceRoute.Tool("/x/$label"))

    private val sections = listOf(
        ToolSection("Camera", listOf(tool("Camera report"), tool("Sound report")), "camera"),
        ToolSection("Accounts", listOf(tool("Payroll"), tool("Timecards")), "accounts"),
    )

    @Test
    fun `a blank query keeps everything, a word keeps the tools that carry it`() {
        assertEquals(sections, sections.matching("  "))
        val hits = sections.matching("report")
        assertEquals(listOf("Camera"), hits.map { it.title })
        assertEquals(listOf("Camera report", "Sound report"), hits.single().tools.map { it.label })
        // Case-folded, and a matching section title keeps its whole run.
        assertEquals(listOf("Payroll", "Timecards"), sections.matching("ACCOUNTS").single().tools.map { it.label })
        assertEquals(emptyList(), sections.matching("catering"))
    }

    @Test
    fun `the user's order leads, unknown ids are ignored, new groups follow`() {
        val groups = listOf(ToolGroup("a", "A"), ToolGroup("b", "B"), ToolGroup("c", "C"))
        assertEquals(listOf("c", "a", "b"), groups.orderedBy(listOf("c", "gone", "a")).map { it.identifier })
        assertEquals(groups, groups.orderedBy(emptyList()))
    }

    /** The drag's step: the row lifted out and dropped in, everything between shifting. */
    @Test
    fun `moving a group shifts the rows between, and a no-op or bad index leaves the list alone`() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), order.moved(0, 2))
        assertEquals(listOf("d", "a", "b", "c"), order.moved(3, 0))
        assertEquals(order, order.moved(1, 1))
        assertEquals(order, order.moved(1, 9))
    }
}
