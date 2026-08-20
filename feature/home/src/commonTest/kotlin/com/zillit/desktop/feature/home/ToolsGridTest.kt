package com.zillit.desktop.feature.home

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.ui.ToolSection
import com.zillit.desktop.feature.home.ui.highlightedLabel
import com.zillit.desktop.feature.home.ui.matching
import com.zillit.desktop.feature.home.ui.moved
import com.zillit.desktop.feature.home.ui.orderedBy
import com.zillit.desktop.feature.home.ui.sortedForDisplay
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
        // Case-folded, on the tool's own name.
        assertEquals(listOf("Payroll"), sections.matching("PAYROLL").single().tools.map { it.label })
        assertEquals(emptyList(), sections.matching("catering"))
    }

    @Test
    fun `a section name is not searchable, only the tools in it`() {
        // Both phones match the tile's name and nothing else. Matching the
        // heading too made "accounts" mean "everything filed under Accounts"
        // here and "tools called Accounts" on a phone.
        assertEquals(emptyList(), sections.matching("ACCOUNTS"))
    }

    @Test
    fun `tiles lead with unread work, then run alphabetically`() {
        val section = ToolSection(
            "Accounts",
            listOf(tool("Timecards"), tool("Payroll"), tool("Invoices")),
            "accounts",
        )
        val ordered = listOf(section)
            .sortedForDisplay(mapOf("timecards" to 3, "invoices" to 12))
            .single()
            .tools
            .map { it.label }

        // 12 then 3, then the unbadged one — not the order the server sent.
        assertEquals(listOf("Invoices", "Timecards", "Payroll"), ordered)
    }

    @Test
    fun `equal tiles keep a stable order, so a recomposition cannot reshuffle them`() {
        // Same badge, same label: without the identifier tiebreak the sort has
        // no answer and two tiles could swap between frames.
        val a = ToolPresentation("a_tool", "Same", ZillitIcons.Tools, WorkspaceRoute.Tool("/x/a"))
        val b = ToolPresentation("b_tool", "Same", ZillitIcons.Tools, WorkspaceRoute.Tool("/x/b"))
        val section = ToolSection("Group", listOf(b, a), "g")

        val ordered = listOf(section).sortedForDisplay(emptyMap()).single().tools
        assertEquals(listOf("a_tool", "b_tool"), ordered.map { it.identifier })
    }

    @Test
    fun `the query is lit wherever it lands in a name`() {
        val marked = highlightedLabel("Report on reports", "REPORT")

        assertEquals("Report on reports", marked.text)
        assertEquals(
            listOf(0 to 6, 10 to 16),
            marked.spanStyles.map { it.start to it.end },
            "both occurrences, case-folded",
        )
        assertEquals(0, highlightedLabel("Payroll", "  ").spanStyles.size, "a blank query marks nothing")
        assertEquals(0, highlightedLabel("Payroll", "zzz").spanStyles.size)
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
