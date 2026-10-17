@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import com.zillit.desktop.core.designsystem.ZillitTheme
import androidx.compose.runtime.mutableStateOf
import com.zillit.desktop.feature.tasks.domain.AttachmentKind
import com.zillit.desktop.feature.tasks.domain.PreparedAttachment
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TaskComment
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
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
            onNodeWithText("Shared from Dress the hero set").assertExists()
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
            onNodeWithText("Share task and assign").assertExists()
        }
    }

    /** The confirmation opens at the ⋯ button, inside the card — never as a page dialog drawn inline in the column. */
    @Test
    fun `cancelling from a card's menu asks first, naming its open subtasks`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(state(), onEvent = {}, mentionable = { crew }) } }
            onAllNodesWithContentDescription("Task options")[0].performClick()
            onNodeWithText("Open task").assertExists()
            onNodeWithText("Cancel task").performClick()
            onNodeWithText("Cancel this task?").assertExists()
            onNodeWithText("Its 1 open shared task will be cancelled too.").assertExists()
            onNodeWithText("Keep it").assertExists()
        }
    }

    @Test
    fun `an assignee who has left reads as a former crew member, not as nobody`() {
        runComposeUiTest {
            val open = tasks[0].copy(assigneeId = "gone", comments = emptyList(), history = emptyList())
            val withPanel = state().copy(
                panel = TaskPanel(key = 1, creating = false, taskId = "a", task = open, orig = open.toDraft(), draft = open.toDraft()),
            )
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(withPanel, onEvent = {}, mentionable = { crew }) } }
            onAllNodesWithText("Former crew member").onFirst().assertExists()
        }
    }

    @Test
    fun `a viewer without view rights is told so`() {
        runComposeUiTest {
            val denied = state().copy(viewer = TasksViewer(resolved = true, enabled = true, canView = false))
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(denied, onEvent = {}, mentionable = { crew }) } }
            onNodeWithText("You don't have access to Tasks").assertExists()
        }
    }

    @Test
    fun `a production without the tool is told so`() {
        runComposeUiTest {
            val off = state().copy(viewer = TasksViewer(resolved = true, enabled = false))
            setContent { ZillitTheme(darkTheme = false) { TasksScreen(off, onEvent = {}, mentionable = { crew }) } }
            onNodeWithText("Tasks is not switched on for this project").assertExists()
        }
    }

    /**
     * The description's images and files, and the two controls that add them.
     *
     * The controls appear only where the service said it can keep them; the
     * attachments a task already has are drawn either way.
     */
    @Test
    fun `the description draws its images and files, and offers them only where the service keeps them`() = runComposeUiTest {
        val attachments = listOf(
            TaskAttachment(id = "a1", name = "recce.jpg", kind = AttachmentKind.Image, fileSizeBytes = 120_345, width = 2000, height = 1333, uploadedBy = "u1"),
            TaskAttachment(id = "a2", name = "budget.xlsx", fileSizeBytes = 23 * 1024, uploadedBy = "u1"),
        )
        val open = tasks[0].copy(attachments = attachments, comments = emptyList(), history = emptyList())
        val panel = TaskPanel(key = 1, creating = false, taskId = "a", task = open, orig = open.toDraft(), draft = open.toDraft())
        val current = mutableStateOf(
            state().copy(panel = panel, features = TasksFeatures(attachments = true)),
        )
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(current.value, onEvent = {}, mentionable = { crew }) } }

        onNodeWithText("Add image").assertExists()
        onNodeWithText("Attach file").assertExists()
        onNodeWithText("Paste or drop files · links are clickable").assertExists()
        // The file row: its badge, name and size.
        onNodeWithText("XLS").assertExists()
        onNodeWithText("budget.xlsx").assertExists()
        onAllNodesWithText("23 KB · Una One").onFirst().assertExists()

        // A service that cannot keep them: the controls are gone, the files stay.
        current.value = state().copy(panel = panel, features = TasksFeatures())
        waitForIdle()
        onNodeWithText("Add image").assertDoesNotExist()
        onNodeWithText("Attach file").assertDoesNotExist()
        onNodeWithText("budget.xlsx").assertExists()

        // One waiting for Create says so, and the viewer opens over the panel.
        current.value = state().copy(
            panel = panel.copy(
                pending = listOf(
                    PreparedAttachment(key = "p1", kind = AttachmentKind.File, name = "Notes.txt", bytes = ByteArray(10), mimeType = "text/plain"),
                ),
                uploading = 1,
                viewing = "a1",
            ),
            features = TasksFeatures(attachments = true),
        )
        waitForIdle()
        onNodeWithText("Notes.txt").assertExists()
        // The row reads "10 B · Saved when you create the task".
        onAllNodesWithText("Saved when you create the task", substring = true).onFirst().assertExists()
        onAllNodesWithText("Uploading…").onFirst().assertExists()
        // The viewer's own header: the name, the size and which of them it is.
        onAllNodesWithText("1 / 1").onFirst().assertExists()
    }
}
