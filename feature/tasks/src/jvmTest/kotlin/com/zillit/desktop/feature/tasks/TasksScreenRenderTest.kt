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
import com.zillit.desktop.feature.tasks.domain.SavedDraftData
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.PreparedAttachment
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TaskComment
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.domain.toDraft
import com.zillit.desktop.feature.tasks.ui.LeaveAsk
import com.zillit.desktop.feature.tasks.ui.TaskPanel
import com.zillit.desktop.feature.tasks.ui.TaskScope
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

    // -- the 2026-10-23 web change: four tabs, drafts, no default visibility -------

    private fun saved(
        id: String = "dr1",
        title: String = "Book rain machine",
        isSelf: Boolean = true,
        assignee: String? = null,
        parent: String? = null,
        visibility: TaskVisibility? = null,
    ) = SavedTaskDraft(
        id = id, isSelf = isSelf, parentId = parent, parentTitle = "Dress the hero set", updatedMillis = 1,
        data = SavedDraftData(title = title, assigneeId = assignee, visibility = visibility),
    )

    @Test
    fun `the tab bar reads Board, Assigned Tasks, Self Tasks and Drafts`() = runComposeUiTest {
        val withDrafts = state().copy(features = TasksFeatures(drafts = true), drafts = listOf(saved()), draftsLoaded = true)
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(withDrafts, onEvent = {}, mentionable = { crew }) } }
        onNodeWithText("Board").assertExists()
        onNodeWithText("Assigned Tasks").assertExists()
        onNodeWithText("Self Tasks").assertExists()
        onNodeWithText("Drafts").assertExists()
        // The old project / self switch is gone from the Board.
        onNodeWithText("Project tasks").assertDoesNotExist()
    }

    @Test
    fun `Drafts has no tab where the service does not keep them`() = runComposeUiTest {
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(state(), onEvent = {}, mentionable = { crew }) } }
        onNodeWithText("Drafts").assertDoesNotExist()
    }

    @Test
    fun `the Drafts tab lists my drafts under To Me and To Other`() = runComposeUiTest {
        val drafts = listOf(saved(), saved(id = "dr2", title = "Hire a crane", isSelf = false, assignee = "u1"))
        val current = mutableStateOf(
            state(TasksView.Drafts).copy(features = TasksFeatures(drafts = true), drafts = drafts, draftsLoaded = true, draftsScopeDecided = true),
        )
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(current.value, onEvent = {}, mentionable = { crew }) } }

        onNodeWithText("To Me").assertExists()
        onNodeWithText("To Other").assertExists()
        onNodeWithText("Book rain machine").assertExists()
        onAllNodesWithText("DRAFT").onFirst().assertExists()
        onNodeWithText("Self task").assertExists()
        // The other side's draft is not on this one.
        onNodeWithText("Hire a crane").assertDoesNotExist()

        current.value = current.value.copy(draftsScope = TaskScope.Other)
        waitForIdle()
        onNodeWithText("Hire a crane").assertExists()
        // To Other says who each draft is for.
        onAllNodesWithText("Una One").onFirst().assertExists()
    }

    @Test
    fun `no drafts at all says what Save draft is for`() = runComposeUiTest {
        val empty = state(TasksView.Drafts).copy(features = TasksFeatures(drafts = true), draftsLoaded = true)
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(empty, onEvent = {}, mentionable = { crew }) } }
        onNodeWithText("No drafts").assertExists()
        onAllNodesWithText("Save draft", substring = true).onFirst().assertExists()
    }

    /**
     * A new main task has no Public / Private until its creator picks one: a
     * Create pressed without a choice says "Please choose one".
     */
    @Test
    fun `a new task offers Public and Private with neither chosen, and nags on Create`() = runComposeUiTest {
        val form = TaskDraft()
        val panel = TaskPanel(key = 1, creating = true, orig = form, draft = form, visPending = true)
        val current = mutableStateOf(state().copy(panel = panel, features = TasksFeatures(visibility = true, drafts = true)))
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(current.value, onEvent = {}, mentionable = { crew }) } }

        onAllNodesWithText("Choose one:", substring = true).onFirst().assertExists()
        // Create is offered, and so is Save draft beside it.
        onNodeWithText("Create task").assertExists()
        onAllNodesWithText("Save draft").onFirst().assertExists()

        current.value = current.value.copy(panel = panel.copy(visNag = 1))
        waitForIdle()
        onAllNodesWithText("Please choose one:", substring = true).onFirst().assertExists()

        // Once one is picked the line says who can see it instead.
        current.value = current.value.copy(panel = panel.copy(visPending = false, draft = form.copy(visibility = TaskVisibility.Public)))
        waitForIdle()
        onAllNodesWithText("Choose one:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a draft form says DRAFT and offers Delete draft`() = runComposeUiTest {
        val form = TaskDraft(title = "Book rain machine", isSelf = true, visibility = TaskVisibility.Private)
        val panel = TaskPanel(key = 1, creating = true, orig = form, draft = form, draftId = "dr1")
        val withPanel = state().copy(panel = panel, features = TasksFeatures(visibility = true, drafts = true))
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(withPanel, onEvent = {}, mentionable = { crew }) } }
        onAllNodesWithText("DRAFT").onFirst().assertExists()
        onNodeWithText("Delete draft").assertExists()
        onAllNodesWithText("Save draft").onFirst().assertExists()
    }

    @Test
    fun `the question before a started task is let go names its three answers`() = runComposeUiTest {
        val form = TaskDraft(title = "Book rain machine", isSelf = true)
        val panel = TaskPanel(key = 1, creating = true, orig = form, draft = form)
        val current = mutableStateOf(
            state().copy(panel = panel, features = TasksFeatures(drafts = true), leaveAsk = LeaveAsk(fromDraft = false)),
        )
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(current.value, onEvent = {}, mentionable = { crew }) } }
        onNodeWithText("Save this task as a draft?").assertExists()
        onNodeWithText("Keep editing").assertExists()
        onNodeWithText("Discard").assertExists()
        onNodeWithText("Save as draft").assertExists()

        // On a saved draft it is worded as its changes.
        current.value = current.value.copy(leaveAsk = LeaveAsk(fromDraft = true))
        waitForIdle()
        onNodeWithText("Save changes to your draft?").assertExists()
        onNodeWithText("Don’t save").assertExists()
    }

    @Test
    fun `Assigned Tasks to other lists what I assigned to someone else`() = runComposeUiTest {
        val mine = state(TasksView.Mine).copy(
            tasks = listOf(task("a", "Dress the hero set", assignee = "u1").copy(assignedBy = "me")),
            mineScope = TaskScope.Other,
        )
        setContent { ZillitTheme(darkTheme = false) { TasksScreen(mine, onEvent = {}, mentionable = { crew }) } }
        onNodeWithText("Tasks Me assigned to other people").assertExists()
        onNodeWithText("Dress the hero set").assertExists()
        // The row starts with who it is for.
        onAllNodesWithText("Una One").onFirst().assertExists()
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
