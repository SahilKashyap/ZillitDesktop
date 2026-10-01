@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskComment
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.domain.toDraft
import com.zillit.desktop.feature.tasks.ui.TaskPanel
import com.zillit.desktop.feature.tasks.ui.TasksScreen
import com.zillit.desktop.feature.tasks.ui.TasksUiState
import com.zillit.desktop.feature.tasks.ui.TasksView
import kotlin.test.Test

/** Composes the real screens in each state, light and dark. */
@OptIn(ExperimentalTestApi::class)
class TasksScreenRenderTest {

    private val crew = listOf(
        TaskPerson("me", "Me Myself", "Producer", "Production"),
        TaskPerson("u1", "Una One", "Art Director", "Art"),
    )

    private fun task(id: String, title: String, status: TaskStatus = TaskStatus.Todo, parent: String? = null, self: Boolean = false, assignee: String? = "u1") = Task(
        id = id, title = title, status = status, parentId = parent, isSelf = self, assigneeId = assignee, createdBy = "me",
        departmentId = "d1", priority = TaskPriority.High, dueDate = "2026-10-06", scenes = "12", commentCount = 2,
        canDelete = true, canReassign = true,
    )

    private val tasks = listOf(
        task("a", "Dress the hero set"),
        task("b", "Order the paint", TaskStatus.Progress),
        task("c", "Strike the old set", TaskStatus.Done),
        task("d", "Cancelled errand", TaskStatus.Cancelled),
        task("s", "Buy brushes", parent = "a"),
        task("p", "Call the vet", self = true, assignee = null),
    )

    private fun state(view: TasksView = TasksView.Board) = TasksUiState(
        viewer = TasksViewer(resolved = true, enabled = true, canView = true, canPost = true),
        loaded = true,
        tasks = tasks,
        me = "me",
        crew = crew,
        departments = listOf(TaskDepartment("d1", "Art")),
        view = view,
    )

    @Test
    fun `the board draws three columns in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { TasksScreen(state(), onEvent = {}, mentionable = { crew }) } }
                onNodeWithText("Dress the hero set").assertExists()
                onNodeWithText("Order the paint").assertExists()
                // A cancelled card sits in Done with the finished ones.
                onNodeWithText("Cancelled errand").assertExists()
                // Subtasks live inside their main task, not on the board.
                onAllNodesWithText("Buy brushes").assertCountEquals(0)
            }
        }
    }

    @Test
    fun `my tasks lists what is assigned to me, subtasks included`() {
        runComposeUiTest {
            val mine = state(TasksView.Mine).copy(tasks = tasks.map { it.copy(assigneeId = "me") })
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(mine, onEvent = {}, mentionable = { crew }) } }
            onNodeWithText("Buy brushes").assertExists()
            onNodeWithText("Subtask of Dress the hero set").assertExists()
        }
    }

    @Test
    fun `self tasks show the private to-do`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(state(TasksView.Self), onEvent = {}, mentionable = { crew }) } }
            onNodeWithText("Call the vet").assertExists()
        }
    }

    @Test
    fun `an open task shows its fields, subtasks and comments`() {
        runComposeUiTest {
            val open = tasks[0].copy(
                comments = listOf(TaskComment("c1", "u1", "Looks good", createdMillis = 1)),
                history = emptyList(),
            )
            val withPanel = state().copy(
                panel = TaskPanel(key = 1, creating = false, taskId = "a", task = open, orig = open.toDraft(), draft = open.toDraft()),
            )
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(withPanel, onEvent = {}, mentionable = { crew }) } }
            onNodeWithText("Buy brushes").assertExists()
            onNodeWithText("Looks good").assertExists()
            onNodeWithText("Add subtask").assertExists()
        }
    }

    @Test
    fun `a viewer without view rights is told so`() {
        runComposeUiTest {
            val denied = state().copy(viewer = TasksViewer(resolved = true, enabled = true, canView = false))
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(denied, onEvent = {}, mentionable = { crew }) } }
            onNodeWithText("You can't view Tasks").assertExists()
        }
    }

    @Test
    fun `a production without the tool is told so`() {
        runComposeUiTest {
            val off = state().copy(viewer = TasksViewer(resolved = true, enabled = false))
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(off, onEvent = {}, mentionable = { crew }) } }
            onNodeWithText("Tasks isn't turned on").assertExists()
        }
    }
}
