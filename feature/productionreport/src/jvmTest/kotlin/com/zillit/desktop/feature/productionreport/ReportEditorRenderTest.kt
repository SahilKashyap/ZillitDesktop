package com.zillit.desktop.feature.productionreport

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.productionreport.ReportRenderFixtures.NOW
import com.zillit.desktop.feature.productionreport.ReportRenderFixtures.editor
import com.zillit.desktop.feature.productionreport.ReportRenderFixtures.state
import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.CellRow
import com.zillit.desktop.feature.productionreport.domain.CellValue
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.ui.DocumentEvent
import com.zillit.desktop.feature.productionreport.ui.EditorEvent
import com.zillit.desktop.feature.productionreport.ui.EditorState
import com.zillit.desktop.feature.productionreport.ui.ProductionReportScreen
import com.zillit.desktop.feature.productionreport.ui.RemovedDefault
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.UndoAction
import com.zillit.desktop.feature.productionreport.ui.UndoRecord
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The editor: the preview with every pane beside it, light and dark, and the
 * menus and pickers the pane and preview open.
 */
@OptIn(ExperimentalTestApi::class)
class ReportEditorRenderTest {

    private val events = mutableListOf<ReportEvent>()

    private fun render(editor: EditorState, dark: Boolean = false, body: ComposeUiTest.() -> Unit = {}) {
        events.clear()
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent {
                ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                    ProductionReportScreen(state.copy(editor = editor), onEvent = { events += it }, nowMillis = { NOW })
                }
            }
            waitForIdle()
            body()
        }
    }

    private fun ComposeUiTest.seen(text: String) {
        val found = onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes()
        assertTrue(found.isNotEmpty(), "expected to find \"$text\" on screen")
    }

    private fun SemanticsNodeInteraction.tap() {
        runCatching { performScrollTo() }
        performClick()
    }

    /** A table with every typed column the grid editor dispatches on. */
    private fun typedTable() = PageCell(
        order = 0,
        kind = CellKind.Table,
        title = "Unit Moves",
        columns = listOf(
            ColumnSpec(type = "text", label = "Note"),
            ColumnSpec(type = "time", label = "Call"),
            ColumnSpec(type = "date", label = "Day"),
            ColumnSpec(type = "users", label = "Who"),
            ColumnSpec(type = "location", label = "Where"),
        ),
        rows = listOf(
            CellRow(0, listOf(CellValue("Base"), CellValue("07:30"), CellValue(), CellValue("u2"), CellValue())),
        ),
    )

    private fun weather() = PageCell(
        order = 0,
        renderAs = RenderKind.Weather,
        title = "Weather",
        columns = listOf(ColumnSpec(label = "Weather")),
        rows = listOf(CellRow(0, listOf(CellValue("Sunny, 25°C")))),
    )

    private fun withExtraRows(): EditorState {
        val base = editor()
        val rows = base.document.rows + PageRow(0, cells = listOf(typedTable())) + PageRow(0, cells = listOf(weather()))
        return base.copy(document = base.document.copy(rows = rows.mapIndexed { i, row -> row.copy(order = i) }))
    }

    @Test
    fun `the preview composes with every pane in both themes`() {
        val extra = withExtraRows()
        val last = extra.document.rows.lastIndex
        val panes = listOf<Pair<EditorState, String>>(
            editor() to "Select a section from preview to edit",
            editor(sidebar = true) to "SECTIONS",
            editor(EditorSelection.Shared) to "Production Report Info",
            editor(EditorSelection.Approvers) to "Add Approver",
            editor(EditorSelection.Cell(0, 1)) to "Key Personnel",
            editor(EditorSelection.Cell(1, 0)) to "Schedule Summary",
            editor(ReportRenderFixtures.crewCell()) to "Apply All",
            extra.copy(selection = EditorSelection.Cell(last - 1, 0), paneOpen = true) to "Unit Moves",
            extra.copy(selection = EditorSelection.Cell(last, 0), paneOpen = true) to "Weather",
        )
        listOf(false, true).forEach { dark ->
            panes.forEach { (ui, text) -> render(ui, dark) { seen(text) } }
        }
    }

    @Test
    fun `the save menu opens and saves the draft`() {
        render(editor(saveMenuOpen = true)) {
            seen("Keep this one and save a copy")
            onAllNodesWithText("Update this draft").onLast().performClick()
            waitForIdle()
        }
        assertTrue(EditorEvent.Save in events, "got $events")
    }

    @Test
    fun `an insert strip opens its menu and inserts a table`() {
        render(editor()) {
            onAllNodesWithContentDescription("Insert section").onFirst().tap()
            waitForIdle()
            seen("Page Break")
            onAllNodesWithText("Table").onLast().performClick()
            waitForIdle()
        }
        assertTrue(events.any { it is DocumentEvent.InsertRow || it is DocumentEvent.InsertCell }, "got $events")
    }

    @Test
    fun `the header pane's day type menu opens and picks`() {
        render(editor(EditorSelection.Shared)) {
            // The pane sits after the preview, whose title bar shows the day type too.
            onAllNodesWithText("SWD").onLast().tap()
            waitForIdle()
            onAllNodesWithText("CWD").onLast().performClick()
            waitForIdle()
        }
        assertTrue(DocumentEvent.SetDayType("CWD") in events, "got $events")
    }

    @Test
    fun `the crew pane's Apply All and IN selector menus open`() {
        val crew = ReportRenderFixtures.crewCell()
        render(editor(crew)) {
            onAllNodesWithText("Apply All").onFirst().tap()
            waitForIdle()
            seen("Clear All")
            onAllNodesWithText("O/C").onLast().performClick()
            waitForIdle()
            onAllNodesWithText("Select...").onFirst().tap()
            waitForIdle()
            onAllNodesWithText("Per HOD").onLast().performClick()
            waitForIdle()
        }
        assertTrue(events.any { it is DocumentEvent.ApplyToColumn && it.value == "O/C" }, "got $events")
        assertTrue(events.any { it is DocumentEvent.SetValue && it.value == "Per HOD" }, "got $events")
    }

    @Test
    fun `a typed table's time picker and user picker open`() {
        val extra = withExtraRows()
        render(extra.copy(selection = EditorSelection.Cell(extra.document.rows.lastIndex - 1, 0), paneOpen = true)) {
            onAllNodesWithContentDescription("Pick a time").onLast().tap()
            waitForIdle()
            onAllNodesWithText("Done").onLast().performClick()
            waitForIdle()
            onAllNodesWithText("Maya Fernandes").onLast().tap()
            waitForIdle()
            seen("Select Users")
        }
    }

    @Test
    fun `the undo bar and Restore Fields compose`() {
        val base = editor(sidebar = true)
        val removed = base.document.rows.first().cells.first()
        val ui = base.copy(
            undo = UndoRecord("Address", UndoAction.Cell(removed, 0, 0), serial = 1),
            removedDefaults = listOf(RemovedDefault(removed, 0, 0)),
        )
        render(ui) {
            seen("Undo")
            seen("RESTORE FIELDS")
            onAllNodesWithText("Restore").onLast().tap()
            waitForIdle()
        }
        assertTrue(EditorEvent.RestoreDefault(0) in events, "got $events")
    }

    private companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1100
    }
}
