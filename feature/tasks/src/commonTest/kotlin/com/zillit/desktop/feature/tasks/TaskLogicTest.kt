@file:Suppress("MaxLineLength","LongParameterList") // Fixtures and wire bodies read best on one line.

package com.zillit.desktop.feature.tasks

import com.zillit.desktop.feature.tasks.domain.DueBucket
import com.zillit.desktop.feature.tasks.domain.DueLabel
import com.zillit.desktop.feature.tasks.domain.DueLimits
import com.zillit.desktop.feature.tasks.domain.DueProblem
import com.zillit.desktop.feature.tasks.domain.DueTone
import com.zillit.desktop.feature.tasks.domain.HistoryLine
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskChange
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskFilter
import com.zillit.desktop.feature.tasks.domain.TaskHistoryEntry
import com.zillit.desktop.feature.tasks.domain.TaskKind
import com.zillit.desktop.feature.tasks.domain.TaskLookup
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.applyMention
import com.zillit.desktop.feature.tasks.domain.boardColumn
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.compareBoard
import com.zillit.desktop.feature.tasks.domain.compareTasks
import com.zillit.desktop.feature.tasks.domain.daysUntil
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.diffDraft
import com.zillit.desktop.feature.tasks.domain.dropStatus
import com.zillit.desktop.feature.tasks.domain.dropTask
import com.zillit.desktop.feature.tasks.domain.dueBucket
import com.zillit.desktop.feature.tasks.domain.dueLabel
import com.zillit.desktop.feature.tasks.domain.dueLimits
import com.zillit.desktop.feature.tasks.domain.dueProblem
import com.zillit.desktop.feature.tasks.domain.dueTone
import com.zillit.desktop.feature.tasks.domain.groupCrew
import com.zillit.desktop.feature.tasks.domain.historyLines
import com.zillit.desktop.feature.tasks.domain.isDirty
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.listRows
import com.zillit.desktop.feature.tasks.domain.matchesFilter
import com.zillit.desktop.feature.tasks.domain.matchesTask
import com.zillit.desktop.feature.tasks.domain.mentionAt
import com.zillit.desktop.feature.tasks.domain.mentionIds
import com.zillit.desktop.feature.tasks.domain.mentionMatches
import com.zillit.desktop.feature.tasks.domain.mergeTask
import com.zillit.desktop.feature.tasks.domain.parentTitle
import com.zillit.desktop.feature.tasks.domain.rebaseDraft
import com.zillit.desktop.feature.tasks.domain.subtaskStats
import com.zillit.desktop.feature.tasks.domain.subtasksOf
import com.zillit.desktop.feature.tasks.domain.toDraft
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules ported from the web's `lib/task.js`, held to the web's own answers. */
class TaskLogicTest {

    private val today = LocalDate(2026, 10, 1)

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.Todo,
        due: String? = null,
        parent: String? = null,
        priority: TaskPriority = TaskPriority.Med,
        created: Long = 0,
        assignee: String? = null,
        isSelf: Boolean = false,
        title: String = id,
    ) = Task(
        id = id, title = title, status = status, dueDate = due, parentId = parent, priority = priority,
        createdMillis = created, assigneeId = assignee, isSelf = isSelf, createdBy = "me",
    )

    // -- drafts -------------------------------------------------------------------------

    @Test
    fun `a diff names only what changed, by wire name`() {
        val orig = task("a", title = "Dress the set").toDraft()
        val draft = orig.copy(title = "  Dress the set  ", priority = TaskPriority.High, assigneeId = "u1")

        // A title that only differs by trimming is not a change.
        assertEquals(mapOf("priority" to "high", "assignee_id" to "u1"), diffDraft(orig, draft))
        assertFalse(isDirty(orig, orig.copy(scenes = "")))
    }

    @Test
    fun `clearing a field sends null, not an empty string`() {
        val orig = TaskDraft(title = "x", dueDate = "2026-10-06", assigneeId = "u1")
        val changes = diffDraft(orig, orig.copy(dueDate = null, assigneeId = null))

        assertEquals(mapOf<String, String?>("due_date" to null, "assignee_id" to null), changes)
    }

    @Test
    fun `typing during a save is not thrown away`() {
        val sent = TaskDraft(title = "A", description = "one")
        val typedMeanwhile = sent.copy(description = "one two")
        val server = sent.copy(title = "A", description = "one", priority = TaskPriority.High)

        val rebased = rebaseDraft(typedMeanwhile, sent, server)

        assertEquals("one two", rebased.description)
        assertEquals(TaskPriority.High, rebased.priority)
    }

    // -- sorting ------------------------------------------------------------------------

    @Test
    fun `tasks sort by due date with undated last, then priority, then age`() {
        val list = listOf(
            task("undated"),
            task("late-low", due = "2026-10-09", priority = TaskPriority.Low),
            task("early", due = "2026-10-02"),
            task("late-high", due = "2026-10-09", priority = TaskPriority.High),
        )

        assertEquals(listOf("early", "late-high", "late-low", "undated"), list.sortedWith(compareTasks).map { it.id })
    }

    @Test
    fun `a cancelled task lives in Done and sorts last`() {
        val cancelled = task("c", status = TaskStatus.Cancelled, due = "2026-10-01")
        val done = task("d", status = TaskStatus.Done, due = "2026-10-20")

        assertEquals(TaskStatus.Done, boardColumn(cancelled))
        assertEquals(listOf("d", "c"), listOf(cancelled, done).sortedWith(compareBoard).map { it.id })
    }

    @Test
    fun `a drop changes nothing outside a column or back in its own`() {
        val card = task("a", TaskStatus.Progress)

        assertNull(dropStatus(card, null))
        assertNull(dropStatus(card, TaskStatus.Progress))
        assertEquals(TaskStatus.Done, dropStatus(card, TaskStatus.Done))
    }

    @Test
    fun `a cancelled card lives in Done so dropping it there does nothing, elsewhere reopens it`() {
        val cancelled = task("c", TaskStatus.Cancelled)

        assertNull(dropStatus(cancelled, TaskStatus.Done))
        assertEquals(TaskStatus.Todo, dropStatus(cancelled, TaskStatus.Todo))
    }

    // -- subtasks -----------------------------------------------------------------------

    @Test
    fun `subtasks list open first, then done, then cancelled`() {
        val list = listOf(
            task("s1", TaskStatus.Cancelled, parent = "m", created = 1),
            task("s2", TaskStatus.Done, parent = "m", created = 2),
            task("s3", TaskStatus.Progress, parent = "m", created = 3),
            task("other", parent = "x"),
        )

        assertEquals(listOf("s3", "s2", "s1"), subtasksOf(list, "m").map { it.id })
        val stats = subtaskStats(subtasksOf(list, "m"))
        // Cancelled subtasks are left out of the total and of the bar.
        assertEquals(2, stats.total)
        assertEquals(1, stats.done)
    }

    @Test
    fun `a main task cannot complete while a subtask is open`() {
        val main = task("m")
        val list = listOf(
            main,
            task("a", parent = "m"),
            task("b", TaskStatus.Cancelled, parent = "m"),
            task("c", TaskStatus.Done, parent = "m"),
        )

        assertEquals(1, closeBlocker(list, main))
        // A subtask is never blocked.
        assertEquals(0, closeBlocker(list, list[1]))
    }

    @Test
    fun `cancelling a main task cancels its open subtasks in the list`() {
        val list = listOf(task("m"), task("a", parent = "m"), task("b", TaskStatus.Done, parent = "m"))

        val merged = mergeTask(list, list[0].copy(status = TaskStatus.Cancelled))

        assertEquals(TaskStatus.Cancelled, merged.first { it.id == "a" }.status)
        assertEquals(TaskStatus.Done, merged.first { it.id == "b" }.status)
    }

    @Test
    fun `an open subtask reopens its closed main task`() {
        val list = listOf(task("m", TaskStatus.Done), task("a", TaskStatus.Done, parent = "m"))

        val merged = mergeTask(list, list[1].copy(status = TaskStatus.Todo))

        assertEquals(TaskStatus.Progress, merged.first { it.id == "m" }.status)
    }

    @Test
    fun `deleting a main task takes its subtasks with it`() {
        val list = listOf(task("m"), task("a", parent = "m"), task("z"))

        assertEquals(listOf("z"), dropTask(list, "m").map { it.id })
    }

    @Test
    fun `a subtask of a private task is named by the title the server sent`() {
        val sub = task("a", parent = "gone").copy(parentTitle = "Private errand")

        assertEquals("Private errand", parentTitle(sub, emptyMap()))
        assertEquals("Live", parentTitle(sub, mapOf("gone" to task("gone", title = "Live"))))
    }

    @Test
    fun `due dates keep a subtask inside its main task`() {
        val byId = mapOf("m" to task("m", due = "2026-10-10"))
        val limits = dueLimits(parentId = "m", taskId = null, byId = byId, tasks = byId.values.toList())

        assertEquals(DueLimits(max = "2026-10-10"), limits)
        assertEquals(DueProblem.AfterMain, dueProblem("2026-10-11", limits))
        assertNull(dueProblem("2026-10-10", limits))
        assertNull(dueProblem(null, limits))
    }

    @Test
    fun `a main task is never due before one of its subtasks`() {
        val list = listOf(task("m"), task("a", parent = "m", due = "2026-10-12"), task("b", parent = "m", due = "2026-10-05"))
        val limits = dueLimits(parentId = null, taskId = "m", byId = list.associateBy { it.id }, tasks = list)

        assertEquals("2026-10-12", limits.min)
        assertEquals(DueProblem.BeforeSubtasks, dueProblem("2026-10-11", limits))
    }

    // -- people, search and filters -------------------------------------------------------

    private val priya = TaskPerson("p", "Priya Das", "Art Director", "Art")
    private val ravi = TaskPerson("r", "Ravi Kapoor", "Gaffer", "Electrical")
    private val lookup = TaskLookup(mapOf("p" to priya, "r" to ravi)) { if (it == "d1") "Art" else "" }

    @Test
    fun `search needs every word, across title, people and department`() {
        val t = task("a", assignee = "p", title = "Paint the hero door").copy(departmentId = "d1")

        assertTrue(matchesTask(t, "paint door", lookup))
        assertTrue(matchesTask(t, "priya art", lookup))
        assertFalse(matchesTask(t, "paint gaffer", lookup))
        assertTrue(matchesTask(t, "   ", lookup))
    }

    @Test
    fun `search covers closed tasks and splits the rows`() {
        val list = listOf(
            task("open", due = "2026-10-03", title = "Recce"),
            task("done", TaskStatus.Done, title = "Recce notes"),
            task("gone", TaskStatus.Cancelled, title = "Recce van"),
        )

        val rows = listRows(list, "recce", lookup)

        assertEquals(listOf("open"), rows.open.map { it.id })
        assertEquals(listOf("done", "gone"), rows.closed.map { it.id })
    }

    @Test
    fun `the board filter narrows by kind, department and assignee`() {
        val project = task("p1", assignee = "p").copy(departmentId = "d1")
        val self = task("s1", isSelf = true)

        assertFalse(matchesFilter(self, TaskFilter(kind = TaskKind.Project), lookup))
        assertTrue(matchesFilter(self, TaskFilter(kind = TaskKind.Self), lookup))
        assertTrue(matchesFilter(project, TaskFilter(department = "d1", assignee = "p"), lookup))
        assertFalse(matchesFilter(project, TaskFilter(assignee = TaskFilter.NO_ASSIGNEE), lookup))
        assertTrue(matchesFilter(self, TaskFilter(assignee = TaskFilter.NO_ASSIGNEE), lookup))
    }

    @Test
    fun `the crew picker groups by department with heads first`() {
        val crew = listOf(
            TaskPerson("a", "Zed", "Runner", "Art"),
            TaskPerson("b", "Amy", "Painter", "Art"),
            TaskPerson("c", "Bo", "Gaffer", "Electrical"),
            TaskPerson("d", "Cy", "", ""),
        )

        val groups = groupCrew(crew, hodIds = setOf("a"), noDepartment = "No department")

        assertEquals(listOf("Art", "Electrical", "No department"), groups.map { it.name })
        // The head of department leads, though Amy sorts before Zed by name.
        assertEquals(listOf("Zed", "Amy"), groups[0].people.map { it.fullName })
        assertEquals(listOf("Zed", "Amy"), groupCrew(crew, hodIds = setOf("a"), exclude = "c")[0].people.map { it.fullName })
    }

    @Test
    fun `a private self task is one nobody else can see yet`() {
        assertTrue(isPrivate(task("a", isSelf = true)))
        assertTrue(isPrivate(task("a", isSelf = true, assignee = "me")))
        assertFalse(isPrivate(task("a", isSelf = true, assignee = "p")))
        assertFalse(isPrivate(task("a", assignee = null)))
    }

    // -- mentions -------------------------------------------------------------------------

    @Test
    fun `an at sign starts a mention only after white space`() {
        assertEquals(0, mentionAt("@Pri", 4)?.start)
        assertEquals(6, mentionAt("hello @Pri", 10)?.start)
        assertNull(mentionAt("mail me@example.com", 12))
    }

    @Test
    fun `a mention ends once a full name is written`() {
        val people = listOf(priya, ravi)

        assertEquals("Priya D", mentionAt("@Priya D", 8, people)?.query)
        assertNull(mentionAt("@Priya Das please look", 22, people))
        // ...unless a longer name still starts that way.
        val longer = people + TaskPerson("x", "Priya Das Gupta")
        assertEquals("Priya Das ", mentionAt("@Priya Das ", 11, longer)?.query)
    }

    @Test
    fun `picking a person writes their name and moves the caret`() {
        val mention = mentionAt("hi @Pri", 7)!!

        val (text, caret) = applyMention("hi @Pri", mention, 7, priya)

        assertEquals("hi @Priya Das ", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun `mentions are read from the text, longest name first`() {
        val people = listOf(TaskPerson("a", "Ravi"), ravi)

        assertEquals(listOf("r"), mentionIds("@Ravi Kapoor can you check", people))
        assertEquals(listOf("r", "a"), mentionIds("@Ravi Kapoor and @Ravi", people))
    }

    @Test
    fun `a word offers the people it matches, names that start with it first`() {
        val people = listOf(TaskPerson("1", "Dara Patel", "Priya's assistant"), priya)

        assertEquals(listOf("p", "1"), mentionMatches(people, "pri").map { it.id })
        // Once there is a space it has to be the start of a full name.
        assertTrue(mentionMatches(people, "priya please").isEmpty())
    }

    // -- dates ----------------------------------------------------------------------------

    @Test
    fun `due dates fall into the lists' groups`() {
        assertEquals(DueBucket.Overdue, dueBucket("2026-09-30", today))
        assertEquals(DueBucket.Today, dueBucket("2026-10-01", today))
        assertEquals(DueBucket.Week, dueBucket("2026-10-08", today))
        assertEquals(DueBucket.Later, dueBucket("2026-10-09", today))
        assertEquals(DueBucket.Later, dueBucket(null, today))
        assertEquals(-1, daysUntil("2026-09-30", today))
    }

    @Test
    fun `a due chip reads as the web's does`() {
        assertEquals(DueLabel.Today, dueLabel("2026-10-01", today))
        assertEquals(DueLabel.Tomorrow, dueLabel("2026-10-02", today))
        assertEquals(DueLabel.Yesterday, dueLabel("2026-09-30", today))
        assertEquals(DueLabel.Soon(LocalDate(2026, 10, 6)), dueLabel("2026-10-06", today))
        assertEquals(DueLabel.On(LocalDate(2026, 10, 20)), dueLabel("2026-10-20", today))
        assertEquals(DueLabel.None, dueLabel(null, today))
    }

    @Test
    fun `a closed task is never late`() {
        assertEquals(DueTone.Late, dueTone(task("a", due = "2026-09-01"), today))
        assertEquals(DueTone.None, dueTone(task("a", TaskStatus.Done, due = "2026-09-01"), today))
        assertEquals(DueTone.Today, dueTone(task("a", due = "2026-10-01"), today))
    }

    // -- history --------------------------------------------------------------------------

    @Test
    fun `an entry the server wrote by itself says why`() {
        val entry = TaskHistoryEntry(
            id = "h", userId = "u", userName = "Sam", action = "updated",
            changes = listOf(TaskChange("status", "todo", "cancelled")), reason = "main_cancelled",
        )

        val lines = historyLines(entry, isSubtask = true)

        assertEquals(HistoryLine.Field("status", "todo", "cancelled", true), lines[0])
        assertEquals(HistoryLine.Note("main_cancelled"), lines[1])
    }

    @Test
    fun `an action this build does not know still says that something happened`() {
        val entry = TaskHistoryEntry("h", "u", "Sam", action = "mystery")

        assertEquals(listOf(HistoryLine.Changed), historyLines(entry, isSubtask = false))
    }

    @Test
    fun `a creation lists who it was assigned to`() {
        val entry = TaskHistoryEntry("h", "u", "Sam", action = "created", assigneeId = "p")

        assertEquals(listOf(HistoryLine.Created(false), HistoryLine.CreatedAssignee("p")), historyLines(entry, false))
    }

    @Test
    fun `a department always gets the same colour`() {
        assertEquals(deptColour("abc123"), deptColour("abc123"))
        assertEquals(0xFF8A91A5, deptColour(null))
    }
}
