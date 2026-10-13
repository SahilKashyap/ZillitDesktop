package com.zillit.desktop.feature.budget

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.budget.cabinet.BudgetCabinetViewer
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.ui.BudgetScreenSeams
import kotlin.test.Test
import kotlin.test.assertEquals

/** The two cabinet rows composed for real, over a fake service. */
@OptIn(ExperimentalTestApi::class)
class BudgetCabinetRenderTest {

    private fun viewer(mode: BudgetMode, repository: RecordingBudgetRepository) = BudgetCabinetViewer(
        mode = mode,
        repository = repository,
        isAdmin = { true },
        host = CountingHost(),
        seams = BudgetScreenSeams(conversation = { ZillitText("the open conversation") }),
    )

    @Test
    fun `the identifiers are the cabinet rows`() {
        val repository = RecordingBudgetRepository()
        assertEquals("main_budget", viewer(BudgetMode.Main, repository).identifier)
        assertEquals("department_budget", viewer(BudgetMode.Department, repository).identifier)
    }

    /** The version and its rooms show; the private thread, the "+" and the chat bubble do not. */
    @Test
    fun `the full budget shows its version and rooms but no way to write`() {
        val repository = RecordingBudgetRepository(
            byDepartment = mapOf("dept-me" to listOf(cabinetVersion("v2", updated = 30))),
            chats = listOf(BudgetChatEntry.Person("u2", "Ravi Menon"), BudgetChatEntry.Group("r1", "Camera crew")),
        )
        val viewer = viewer(BudgetMode.Main, repository)
        runComposeUiTest {
            setContent { ZillitTheme { viewer.Content(null, Modifier) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Camera crew").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Budget v2").assertExists()
            onNodeWithText("v2.pdf").assertExists()
            onAllNodesWithText("Ravi Menon").assertCountEquals(0)
            // The window carries the title; the rail does not repeat it.
            onAllNodesWithText("Budget (Full)").assertCountEquals(0)
        }
    }

    /** The department tile opens on its directory, as the live one does. */
    @Test
    fun `the department budget opens on its directory`() {
        val repository = RecordingBudgetRepository(
            all = listOf(cabinetVersion("d1", BudgetType.Department, "cam", 5)),
        )
        val viewer = viewer(BudgetMode.Department, repository)
        runComposeUiTest {
            setContent { ZillitTheme { viewer.Content(null, Modifier) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Camera").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Camera").assertExists()
        }
    }
}
