package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.domain.SavedDraftData
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskAttachment
import com.zillit.desktop.feature.tasks.domain.TaskChange
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskHistoryEntry
import com.zillit.desktop.feature.tasks.domain.TaskLookup
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.assignedByMeToOther
import com.zillit.desktop.feature.tasks.domain.compareDrafts
import com.zillit.desktop.feature.tasks.domain.draftData
import com.zillit.desktop.feature.tasks.domain.draftIsToMe
import com.zillit.desktop.feature.tasks.domain.formHasContent
import com.zillit.desktop.feature.tasks.domain.formOfDraft
import com.zillit.desktop.feature.tasks.domain.formSig
import com.zillit.desktop.feature.tasks.domain.matchesTask
import com.zillit.desktop.feature.tasks.domain.visibilityChosen
import com.zillit.desktop.feature.tasks.domain.visibleHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drafts, "To Me | To Other", and the DPR field being gone — the rules of the
 * web's 2026-10-23 Tasks change (its `lib.test.js`, in the same order).
 */
class TaskDraftsTest {

    private val form = TaskDraft(
        title = " Book rain machine ",
        departmentId = "D1",
        assigneeId = "U2",
        visibility = TaskVisibility.Private,
        dueDate = "2026-10-05",
    )

    private fun saved(
        id: String = "DR1",
        isSelf: Boolean = false,
        parentId: String? = null,
        data: SavedDraftData = SavedDraftData(),
        updated: Long = 0,
        attachments: List<TaskAttachment> = emptyList(),
    ) = SavedTaskDraft(
        id = id, isSelf = isSelf, parentId = parentId, data = data,
        updatedMillis = updated, attachments = attachments,
    )

    // -- what a draft keeps ----------------------------------------------------------

    @Test
    fun `a draft keeps the form as it is, with no Public or Private until one is chosen`() {
        assertEquals(
            SavedDraftData(
                title = " Book rain machine ", description = "", assigneeId = "U2",
                visibility = TaskVisibility.Private, dueDate = "2026-10-05",
                status = TaskStatus.Todo, priority = TaskPriority.Med, departmentId = "D1", scenes = "",
            ),
            draftData(form),
        )
        // Nothing chosen yet: the form's own value is only a placeholder.
        assertNull(draftData(form, chosen = false).visibility)
        // A shared task takes its main task's, so it sends none.
        assertNull(draftData(form.copy(parentId = "M")).visibility)
        val blank = draftData(TaskDraft())
        assertEquals("", blank.title)
        assertNull(blank.assigneeId)
        assertNull(blank.dueDate)
        assertNull(blank.departmentId)
    }

    @Test
    fun `a draft opens as the form it was, under its main task while the list has it`() {
        val data = SavedDraftData(
            title = "Hose", visibility = null, dueDate = "2026-09-01",
            status = TaskStatus.Cancelled, priority = TaskPriority.High, assigneeId = "U1",
        )
        val shared = saved(isSelf = true, parentId = "M", data = data)

        val under = formOfDraft(shared, "M")
        assertEquals("Hose", under.title)
        assertTrue(under.isSelf)
        assertEquals("M", under.parentId)
        assertEquals("2026-09-01", under.dueDate)
        // A task is never created cancelled: a draft saved so opens as To do.
        assertEquals(TaskStatus.Todo, under.status)
        assertEquals(TaskPriority.High, under.priority)
        // No choice made: the placeholder a self task would have.
        assertEquals(TaskVisibility.Private, under.visibility)
        assertFalse(shared.visibilityChosen)

        // Its main task is no longer in the list: it opens as a task of its own.
        assertNull(formOfDraft(shared, null).parentId)

        val chosen = saved(data = SavedDraftData(visibility = TaskVisibility.Private))
        assertEquals(TaskVisibility.Private, formOfDraft(chosen).visibility)
        assertTrue(chosen.visibilityChosen)
        // A project task with nothing in it: the placeholder is Public.
        assertEquals(TaskVisibility.Public, formOfDraft(saved()).visibility)
        assertEquals(TaskPriority.Med, formOfDraft(saved()).priority)
    }

    @Test
    fun `a form is only worth asking about when it holds something`() {
        val blank = TaskDraft(isSelf = true)
        assertFalse(formHasContent(blank))
        // Status, priority, department and Public / Private alone are not enough.
        assertFalse(
            formHasContent(
                blank.copy(
                    status = TaskStatus.Progress, priority = TaskPriority.High,
                    departmentId = "D1", visibility = TaskVisibility.Public,
                ),
            ),
        )
        assertFalse(formHasContent(blank.copy(title = "  ")))
        assertTrue(formHasContent(blank.copy(title = "x")))
        assertTrue(formHasContent(blank.copy(description = "x")))
        assertTrue(formHasContent(blank.copy(scenes = "Sc 4")))
        assertTrue(formHasContent(blank.copy(dueDate = "2026-10-05")))
        assertTrue(formHasContent(blank.copy(assigneeId = "U1")))
        assertTrue(formHasContent(blank, files = 1))
        assertTrue(formHasContent(blank, draftId = "DR1"))
        assertFalse(formHasContent(null))
    }

    @Test
    fun `a changed form is told from the one that opened`() {
        val blank = TaskDraft(isSelf = true)
        val sig = formSig(blank, visPending = true)
        assertEquals(sig, formSig(blank.copy(), visPending = true))
        assertTrue(formSig(blank.copy(title = "x"), visPending = true) != sig)
        // Choosing Public / Private is itself a change.
        assertTrue(formSig(blank, visPending = false) != sig)
        assertTrue(formSig(blank, visPending = true, files = listOf("k1")) != sig)
        // An empty text and nothing at all are the same form.
        assertEquals(
            formSig(blank.copy(scenes = ""), visPending = true),
            formSig(blank.copy(scenes = "   "), visPending = true),
        )
        assertEquals("", formSig(null))
    }

    // -- To Me | To Other -----------------------------------------------------------

    @Test
    fun `a draft goes under To Me or To Other`() {
        assertTrue(draftIsToMe(saved(data = SavedDraftData(assigneeId = "ME")), "ME"))
        assertTrue(draftIsToMe(saved(isSelf = true), "ME"))
        assertFalse(draftIsToMe(saved(isSelf = true, data = SavedDraftData(assigneeId = "U2")), "ME"))
        // A project task nobody is on yet is for somebody else to do.
        assertFalse(draftIsToMe(saved(), "ME"))
        assertFalse(draftIsToMe(saved(data = SavedDraftData(assigneeId = "U2")), "ME"))
    }

    @Test
    fun `drafts list newest first`() {
        val rows = listOf(saved("a", updated = 1), saved("b", updated = 3), saved("c", updated = 2))
        assertEquals(listOf("b", "c", "a"), rows.sortedWith(compareDrafts).map { it.id })
    }

    @Test
    fun `Assigned Tasks to other is a task I assigned to someone else`() {
        fun task(assignee: String?, assignedBy: String? = null, creator: String? = null) =
            Task(id = "t", title = "x", assigneeId = assignee, assignedBy = assignedBy, createdBy = creator)

        assertTrue(assignedByMeToOther(task("U2", assignedBy = "ME"), "ME"))
        assertFalse(assignedByMeToOther(task("ME", assignedBy = "ME"), "ME"))
        assertFalse(assignedByMeToOther(task("U2", assignedBy = "U3"), "ME"))
        assertFalse(assignedByMeToOther(task(null, assignedBy = "ME", creator = "ME"), "ME"))
        // A row from before `assigned_by`: whoever created it assigned it.
        assertTrue(assignedByMeToOther(task("U2", creator = "ME"), "ME"))
    }

    // -- the DPR field is gone ------------------------------------------------------

    @Test
    fun `the search no longer looks at a DPR`() {
        val task = Task(id = "t", title = "Confirm generator", scenes = "Sc 41")
        val lookup = TaskLookup()
        assertTrue(matchesTask(task, "generator sc 41", lookup))
        assertFalse(matchesTask(task, "#14", lookup))
    }

    @Test
    fun `the history leaves out a DPR change an older service recorded`() {
        fun entry(id: String, action: String, vararg changes: TaskChange) =
            TaskHistoryEntry(id = id, userId = "u", userName = "U", action = action, changes = changes.toList())

        val onlyDpr = entry("h1", "updated", TaskChange("dpr", null, "DPR 13 Oct"))
        val mixed = entry("h2", "updated", TaskChange("dpr", "a", "b"), TaskChange("scenes", "", "Sc 4"))
        val created = entry("h3", "created")
        val odd = entry("h4", "updated")

        val shown = visibleHistory(listOf(onlyDpr, mixed, created, odd))
        // The DPR-only entry is gone; the mixed one keeps everything else.
        assertEquals(listOf("h2", "h3", "h4"), shown.map { it.id })
        assertEquals(listOf(TaskChange("scenes", "", "Sc 4")), shown.first().changes)
        assertEquals(emptyList(), visibleHistory(null))
    }
}
