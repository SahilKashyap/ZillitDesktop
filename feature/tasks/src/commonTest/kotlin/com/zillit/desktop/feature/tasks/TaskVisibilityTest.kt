@file:Suppress("MaxLineLength") // Fixtures read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.domain.DueProblem
import com.zillit.desktop.feature.tasks.domain.DueWhy
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.TasksFeatures
import com.zillit.desktop.feature.tasks.domain.applyFeatures
import com.zillit.desktop.feature.tasks.domain.diffDraft
import com.zillit.desktop.feature.tasks.domain.dueLimits
import com.zillit.desktop.feature.tasks.domain.dueProblem
import com.zillit.desktop.feature.tasks.domain.isDirty
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.toDraft
import com.zillit.desktop.feature.tasks.domain.visibilityOf
import com.zillit.desktop.feature.tasks.domain.whoCanSee
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who can see a task, what the deployment can keep, and the due-date rules —
 * the three things the 2026-10-12 web change added.
 */
class TaskVisibilityTest {

    private val all = TasksFeatures(visibility = true, attachments = true, drafts = true)

    private fun task(
        id: String = "t1",
        isSelf: Boolean = false,
        visibility: TaskVisibility? = null,
        creator: String? = "me",
        assignee: String? = null,
        assigner: String? = null,
    ) = Task(
        id = id, title = "x", isSelf = isSelf, visibility = visibility,
        createdBy = creator, assigneeId = assignee, assignedBy = assigner,
    )

    // -- what the task carries -------------------------------------------------------

    @Test
    fun `a task without a visibility is what it always was`() {
        assertEquals(TaskVisibility.Public, visibilityOf(task()))
        assertEquals(TaskVisibility.Private, visibilityOf(task(isSelf = true)))
    }

    @Test
    fun `a task with a visibility is that, whatever list it is in`() {
        assertEquals(TaskVisibility.Private, visibilityOf(task(visibility = TaskVisibility.Private)))
        assertEquals(TaskVisibility.Public, visibilityOf(task(isSelf = true, visibility = TaskVisibility.Public)))
    }

    @Test
    fun `a public task is seen by everyone`() {
        assertNull(whoCanSee(task()))
    }

    @Test
    fun `a private task is seen by its creator, assigner and assignee, each once`() {
        val people = whoCanSee(task(visibility = TaskVisibility.Private, creator = "me", assigner = "boss", assignee = "you"))
        assertEquals(listOf("me", "boss", "you"), people)
        // The same person twice is listed once.
        assertEquals(listOf("me"), whoCanSee(task(visibility = TaskVisibility.Private, creator = "me", assignee = "me")))
    }

    @Test
    fun `only you means nobody else is on it`() {
        assertTrue(isPrivate(task(visibility = TaskVisibility.Private, creator = "me")))
        assertTrue(isPrivate(task(visibility = TaskVisibility.Private, creator = "me", assignee = "me")))
        assertFalse(isPrivate(task(visibility = TaskVisibility.Private, creator = "me", assignee = "you")))
        assertFalse(isPrivate(task(visibility = TaskVisibility.Public)))
    }

    @Test
    fun `an assigner counts, so a task handed back is not only you`() {
        // The service's rule, and unlike the standalone app: someone else put
        // it back on its creator, and that someone can still see it.
        val handed = task(visibility = TaskVisibility.Private, creator = "me", assigner = "boss", assignee = "me")
        assertFalse(isPrivate(handed))
    }

    @Test
    fun `without a visibility, only you falls back on the self task rule`() {
        assertTrue(isPrivate(task(isSelf = true, creator = "me")))
        assertFalse(isPrivate(task(isSelf = true, creator = "me", assignee = "you")))
        assertFalse(isPrivate(task(creator = "me")))
    }

    // -- what the deployment can keep ------------------------------------------------

    @Test
    fun `visibility off strips the field from the task`() {
        val full = task(visibility = TaskVisibility.Private)
        val none = applyFeatures(full, TasksFeatures())
        assertNull(none.visibility)
        // And the fallback then applies by itself.
        assertEquals(TaskVisibility.Public, visibilityOf(none))
        // On, the task is left exactly as it came.
        assertEquals(TaskVisibility.Private, applyFeatures(full, all).visibility)
    }

    @Test
    fun `a diff never mentions visibility the service cannot keep`() {
        val orig = task().toDraft()
        val changed = orig.copy(visibility = TaskVisibility.Private)
        // Called without features — the default — it is never sent.
        assertTrue(diffDraft(orig, changed).isEmpty())
        assertFalse(isDirty(orig, changed))
        // With it, it is.
        assertEquals(mapOf("visibility" to "private"), diffDraft(orig, changed, all))
        assertTrue(isDirty(orig, changed, all))
    }

    @Test
    fun `a new task starts public, and a new self task private`() {
        assertEquals(TaskVisibility.Public, Task(id = "", title = "").toDraft().visibility)
        assertEquals(TaskVisibility.Private, Task(id = "", title = "", isSelf = true).toDraft().visibility)
    }

    // -- due dates --------------------------------------------------------------------

    @Test
    fun `a date in the past is refused`() {
        val limits = dueLimits(null, null, emptyMap(), emptyList(), today = "2026-10-14")
        assertEquals("2026-10-14", limits.min)
        assertNull(limits.why)
        assertEquals(DueProblem.Past, dueProblem("2026-10-13", limits))
        assertNull(dueProblem("2026-10-14", limits))
        assertNull(dueProblem(null, limits))
    }

    @Test
    fun `a subtask is never due after its main task`() {
        val main = task(id = "m").copy(dueDate = "2026-10-24")
        val limits = dueLimits("m", null, mapOf("m" to main), listOf(main), today = "2026-10-14")
        assertEquals("2026-10-24", limits.max)
        assertEquals(DueWhy.Main, limits.why)
        assertEquals(DueProblem.AfterMain, dueProblem("2026-10-25", limits))
    }

    @Test
    fun `a main task is never due before its latest subtask`() {
        val sub = task(id = "s").copy(parentId = "m", dueDate = "2026-10-20")
        val limits = dueLimits(null, "m", emptyMap(), listOf(sub), today = "2026-10-14")
        assertEquals("2026-10-20", limits.min)
        assertEquals(DueWhy.Subtasks, limits.why)
        assertEquals(DueProblem.BeforeSubtasks, dueProblem("2026-10-19", limits))
    }

    @Test
    fun `a cancelled subtask does not hold its main task back`() {
        val cancelled = task(id = "s").copy(parentId = "m", dueDate = "2026-10-20", status = TaskStatus.Cancelled)
        val limits = dueLimits(null, "m", emptyMap(), listOf(cancelled), today = "2026-10-14")
        // Only "today" is left: the cancelled one sets nothing.
        assertEquals("2026-10-14", limits.min)
        assertNull(limits.why)
    }

    @Test
    fun `a subtask due today sets nothing today does not already`() {
        val sub = task(id = "s").copy(parentId = "m", dueDate = "2026-10-14")
        val limits = dueLimits(null, "m", emptyMap(), listOf(sub), today = "2026-10-14")
        assertEquals("2026-10-14", limits.min)
        assertNull(limits.why)
    }

    @Test
    fun `a main task the list does not hold sets no limit`() {
        val limits = dueLimits("gone", null, emptyMap(), emptyList(), today = "2026-10-14")
        assertNull(limits.max)
        assertNull(limits.why)
        // The server still checks; nothing is refused here.
        assertNull(dueProblem("2026-12-01", limits))
    }
}
