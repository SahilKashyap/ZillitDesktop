@file:Suppress("MaxLineLength") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.domain.SavedDraftData
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
import com.zillit.desktop.feature.tasks.ui.TaskScope
import com.zillit.desktop.feature.tasks.ui.TasksEvent
import com.zillit.desktop.feature.tasks.ui.TasksView
import com.zillit.desktop.feature.tasks.ui.TasksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drafts — a new task saved to finish later: the rules of the web's 2026-10-23
 * Tasks change (its `drawer`, `provider` and `screens` tests), plus Public /
 * Private having no default on a new task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksDraftsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- drafts: a new task saved to finish later ----------------------------------

    private val draftsOn = TasksFeatures(visibility = true, attachments = true, drafts = true)

    /** The tool, loaded, with a new-task form open and something typed into it. */
    private suspend fun TestScope.started(
        repository: FakeTasksRepository,
        features: TasksFeatures = draftsOn,
        seed: TaskDraft = TaskDraft(isSelf = true),
        title: String = "Book rain machine",
    ): TasksViewModel {
        repository.features = features
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.NewTask(seed))
        val draft = vm.state.value.panel!!.draft!!
        vm.onEvent(TasksEvent.DraftChanged(draft.copy(title = title)))
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `drafts are read only where the service keeps them and the viewer may post`() = runTest(dispatcher) {
        // The list is what says so, so nothing is asked before it has answered.
        val off = FakeTasksRepository()
        val plain = model(off)
        plain.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        assertEquals(0, off.draftReads)
        assertTrue(!plain.state.value.draftsOn)

        val on = FakeTasksRepository().also { it.features = draftsOn; it.savedDrafts = listOf(SavedTaskDraft(id = "dr1")) }
        val vm = model(on)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        assertEquals(1, on.draftReads)
        assertTrue(vm.state.value.draftsOn)
        assertEquals(listOf("dr1"), vm.state.value.drafts.map { it.id })
        assertEquals(1, vm.state.value.counts.drafts)

        // Without posting rights there is nothing to create, so no drafts either.
        val reader = FakeTasksRepository().also { it.features = draftsOn }
        val readOnly = model(reader, viewer = granted.copy(canPost = false))
        readOnly.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        assertTrue(!readOnly.state.value.draftsOn)
        assertEquals(0, reader.draftReads)
    }

    @Test
    fun `Save draft keeps the form, closes it, and picks the tab the draft landed in`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        val vm = started(repository)
        vm.onEvent(TasksEvent.SaveDraft)
        advanceUntilIdle()

        val (id, data) = repository.draftWrites.single()
        // A new draft, not an update.
        assertNull(id)
        assertEquals("Book rain machine", data.title)
        // A self task for me: it lands under To Me.
        assertEquals(TaskScope.Me, vm.state.value.draftsScope)
        assertEquals(listOf("dr-1"), vm.state.value.drafts.map { it.id })
        // Nothing was created, and the form is gone.
        assertTrue(repository.created.isEmpty())
        assertNull(vm.state.value.panel)
    }

    @Test
    fun `Save draft on a form with nothing in it says so and saves nothing`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.NewTask(TaskDraft(isSelf = true)))
        vm.onEvent(TasksEvent.SaveDraft)
        advanceUntilIdle()

        assertTrue(repository.draftWrites.isEmpty())
        assertNotNull(vm.state.value.notice)
        assertNotNull(vm.state.value.panel)
    }

    @Test
    fun `Create without a choice of Public or Private is refused, and says so again on the next press`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        val vm = started(repository, seed = TaskDraft())
        assertTrue(vm.state.value.panel!!.visPending)

        vm.onEvent(TasksEvent.Create)
        advanceUntilIdle()
        assertTrue(repository.created.isEmpty())
        assertEquals(1, vm.state.value.panel!!.visNag)
        assertNotNull(vm.state.value.notice)

        vm.onEvent(TasksEvent.Create)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.panel!!.visNag)

        // Choosing one makes the choice, and Create goes through with it.
        vm.onEvent(TasksEvent.PickVisibility(TaskVisibility.Private))
        assertTrue(!vm.state.value.panel!!.visPending)
        assertEquals(0, vm.state.value.panel!!.visNag)
        vm.onEvent(TasksEvent.Create)
        advanceUntilIdle()
        assertEquals(TaskVisibility.Private, repository.created.single().first.visibility)
    }

    @Test
    fun `a new shared task has nothing to choose, and a service without the field is never asked`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.held = listOf(task("m1"))
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.NewSubtask(task("m1")))
        val sub = vm.state.value.panel!!
        // It follows its main task, so there is no choice to make.
        assertTrue(!sub.visPending)
        assertTrue(!sub.visUnchosen(vm.state.value.features))

        // And where the service cannot keep the field there is nothing to ask either.
        val plain = FakeTasksRepository()
        val noField = started(plain, features = TasksFeatures(drafts = true), seed = TaskDraft())
        assertTrue(noField.state.value.panel!!.visPending)
        assertTrue(!noField.state.value.panel!!.visUnchosen(noField.state.value.features))
        noField.onEvent(TasksEvent.Create)
        advanceUntilIdle()
        assertEquals(1, plain.created.size)
    }

    @Test
    fun `a draft opens as its form, and creating from it names the draft`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.savedDrafts = listOf(
            SavedTaskDraft(
                id = "dr1",
                data = SavedDraftData(title = "Hose", visibility = TaskVisibility.Public, assigneeId = "u1"),
                attachments = listOf(TaskAttachment(id = "a1", name = "recce.jpg")),
            ),
        )
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        vm.onEvent(TasksEvent.OpenDraft(repository.savedDrafts.single()))
        advanceUntilIdle()
        val panel = vm.state.value.panel!!
        assertEquals("dr1", panel.draftId)
        assertTrue(panel.draftReady)
        assertEquals("Hose", panel.draft!!.title)
        // Its own choice was made, so nothing is pending.
        assertTrue(!panel.visPending)
        // Its images and files come with it, and count as content.
        assertEquals(listOf("a1"), panel.keptDraftFiles.map { it.id })

        vm.onEvent(TasksEvent.Create)
        advanceUntilIdle()
        // The create carries `draft_id`; the service moved the files and deleted the draft.
        assertEquals(listOf("dr1"), repository.createDraftIds.filterNotNull())
        assertTrue(vm.state.value.drafts.isEmpty())
        assertNull(vm.state.value.panel)
    }

    @Test
    fun `a draft deleted elsewhere leaves the form as a new task, and nothing is created`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.savedDrafts = listOf(SavedTaskDraft(id = "dr1", data = SavedDraftData(title = "Hose", visibility = TaskVisibility.Public)))
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenDraft(repository.savedDrafts.single()))
        advanceUntilIdle()

        repository.refuseCreateAsDraftGone = true
        vm.onEvent(TasksEvent.Create)
        advanceUntilIdle()

        val panel = vm.state.value.panel!!
        // The form stays, with what was typed, and is no longer a draft.
        assertNull(panel.draftId)
        assertEquals("Hose", panel.draft!!.title)
        assertTrue(panel.keptDraftFiles.isEmpty())
        assertTrue(vm.state.value.drafts.isEmpty())
    }

    @Test
    fun `a shared-task draft whose main task is gone opens as a task of its own`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.savedDrafts = listOf(SavedTaskDraft(id = "dr1", parentId = "gone", parentTitle = "Dress the set"))
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenDraft(repository.savedDrafts.single()))
        advanceUntilIdle()

        assertNull(vm.state.value.panel!!.draft!!.parentId)
        // And it says why.
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun `closing a started new task asks first, and Save as draft then carries on`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.held = listOf(task("t1"))
        val vm = started(repository)

        // Opening another task asks about this form rather than replacing it.
        vm.onEvent(TasksEvent.OpenTask("t1"))
        advanceUntilIdle()
        val ask = vm.state.value.leaveAsk!!
        assertTrue(!ask.fromDraft)
        assertTrue(vm.state.value.panel!!.creating)

        vm.onEvent(TasksEvent.LeaveSave)
        advanceUntilIdle()
        assertEquals(1, repository.draftWrites.size)
        // …and then the task the user had asked for is open.
        assertEquals("t1", vm.state.value.panel!!.taskId)
        assertNull(vm.state.value.leaveAsk)
    }

    @Test
    fun `Keep editing changes nothing, and Discard throws the form away`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        val vm = started(repository)

        vm.onEvent(TasksEvent.ClosePanel)
        assertNotNull(vm.state.value.leaveAsk)
        vm.onEvent(TasksEvent.LeaveStay)
        assertNull(vm.state.value.leaveAsk)
        assertNotNull(vm.state.value.panel)

        vm.onEvent(TasksEvent.CancelCreate)
        vm.onEvent(TasksEvent.LeaveDiscard)
        advanceUntilIdle()
        assertTrue(repository.draftWrites.isEmpty())
        assertNull(vm.state.value.panel)
    }

    @Test
    fun `a form with nothing in it, or a service without drafts, closes without a word`() = runTest(dispatcher) {
        // Nothing typed: there is nothing to keep.
        val blank = FakeTasksRepository().also { it.features = draftsOn }
        val vm = model(blank)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.NewTask(TaskDraft(isSelf = true)))
        vm.onEvent(TasksEvent.CancelCreate)
        assertNull(vm.state.value.leaveAsk)
        assertNull(vm.state.value.panel)

        // Drafts off: a started task closes as it always did.
        val repository = FakeTasksRepository()
        val plain = started(repository, features = TasksFeatures(visibility = true))
        plain.onEvent(TasksEvent.CancelCreate)
        assertNull(plain.state.value.leaveAsk)
        assertNull(plain.state.value.panel)
    }

    @Test
    fun `an open draft with changes is asked about as a draft, and Don't save leaves it as it was`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.savedDrafts = listOf(SavedTaskDraft(id = "dr1", isSelf = true, data = SavedDraftData(title = "Hose", visibility = TaskVisibility.Private)))
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenDraft(repository.savedDrafts.single()))
        advanceUntilIdle()
        // Unchanged, it is let go without a word.
        vm.onEvent(TasksEvent.CancelCreate)
        assertNull(vm.state.value.leaveAsk)

        vm.onEvent(TasksEvent.OpenDraft(repository.savedDrafts.single()))
        advanceUntilIdle()
        val form = vm.state.value.panel!!.draft!!
        vm.onEvent(TasksEvent.DraftChanged(form.copy(title = "Hose and rig")))
        vm.onEvent(TasksEvent.CancelCreate)
        assertTrue(vm.state.value.leaveAsk!!.fromDraft)

        vm.onEvent(TasksEvent.LeaveDiscard)
        advanceUntilIdle()
        // Nothing was written, and the draft is still in the list as it was.
        assertTrue(repository.draftWrites.isEmpty())
        assertEquals("Hose", vm.state.value.drafts.single().data.title)
    }

    @Test
    fun `Save draft on an open draft updates it and moves its images and files`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.savedDrafts = listOf(
            SavedTaskDraft(
                id = "dr1", isSelf = true,
                data = SavedDraftData(title = "Hose", visibility = TaskVisibility.Private),
                attachments = listOf(TaskAttachment(id = "a1", name = "recce.jpg"), TaskAttachment(id = "a2", name = "plan.pdf")),
            ),
        )
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.OpenDraft(repository.savedDrafts.single()))
        advanceUntilIdle()

        // One taken out of the form leaves the draft only on Save draft.
        vm.onEvent(TasksEvent.RemovePendingAttachment("draft:a1"))
        assertEquals(listOf("a1"), vm.state.value.panel!!.droppedFiles)
        assertTrue(repository.draftAttachmentsRemoved.isEmpty())

        vm.onEvent(TasksEvent.SaveDraft)
        advanceUntilIdle()
        assertEquals(listOf("dr1" to "a1"), repository.draftAttachmentsRemoved)
        assertEquals("dr1", repository.draftWrites.single().first)
        assertEquals(listOf("a2"), vm.state.value.drafts.single().attachments.map { it.id })
    }

    @Test
    fun `a draft's 🗑 deletes it, and the panel's Delete draft needs two presses`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.savedDrafts = listOf(SavedTaskDraft(id = "dr1"), SavedTaskDraft(id = "dr2"))
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        vm.onEvent(TasksEvent.DeleteDraft(repository.savedDrafts.first { it.id == "dr2" }))
        advanceUntilIdle()
        assertEquals(listOf("dr2"), repository.deletedDrafts)
        assertEquals(listOf("dr1"), vm.state.value.drafts.map { it.id })

        vm.onEvent(TasksEvent.OpenDraft(vm.state.value.drafts.single()))
        advanceUntilIdle()
        // The first press only arms it.
        vm.onEvent(TasksEvent.DeleteOpenDraft)
        assertTrue(vm.state.value.panel!!.deleteArmed)
        assertEquals(listOf("dr2"), repository.deletedDrafts)
        vm.onEvent(TasksEvent.DeleteOpenDraft)
        advanceUntilIdle()
        assertEquals(listOf("dr2", "dr1"), repository.deletedDrafts)
        assertNull(vm.state.value.panel)
    }

    @Test
    fun `a deleted main task takes its shared-task drafts with it`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = draftsOn
        repository.held = listOf(task("m1"))
        repository.savedDrafts = listOf(SavedTaskDraft(id = "dr1", parentId = "m1"), SavedTaskDraft(id = "dr2"))
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()

        vm.onEvent(TasksEvent.Delete(task("m1")))
        advanceUntilIdle()
        assertEquals(listOf("dr2"), vm.state.value.drafts.map { it.id })
    }

    @Test
    fun `the Drafts tab is only reachable where drafts are offered`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.ViewChanged(TasksView.Drafts))
        assertEquals(TasksView.Board, vm.state.value.view)

        val on = FakeTasksRepository().also { it.features = draftsOn }
        val open = model(on)
        open.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        open.onEvent(TasksEvent.ViewChanged(TasksView.Drafts))
        advanceUntilIdle()
        assertEquals(TasksView.Drafts, open.state.value.view)
        // Opening the tab catches up with drafts saved somewhere else.
        assertEquals(2, on.draftReads)
    }

    @Test
    fun `the quick-add row sends its Public or Private only where the service keeps it`() = runTest(dispatcher) {
        val repository = FakeTasksRepository()
        repository.features = TasksFeatures(visibility = true)
        val vm = model(repository)
        vm.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        vm.onEvent(TasksEvent.QuickAdd("Call rental house", "2026-10-06", null, TaskVisibility.Public))
        advanceUntilIdle()
        assertEquals(listOf<TaskVisibility?>(TaskVisibility.Public), repository.quickVisibility)

        val plain = FakeTasksRepository()
        val off = model(plain)
        off.onEvent(TasksEvent.Load)
        advanceUntilIdle()
        off.onEvent(TasksEvent.QuickAdd("Call rental house", "2026-10-06", null, null))
        advanceUntilIdle()
        assertEquals(listOf<TaskVisibility?>(null), plain.quickVisibility)
    }
}
