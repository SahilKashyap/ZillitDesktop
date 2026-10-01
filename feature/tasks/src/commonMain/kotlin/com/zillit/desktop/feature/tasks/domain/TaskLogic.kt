@file:Suppress("TooManyFunctions","CyclomaticComplexMethod","MaxLineLength") // A port of the web's lib/task.js: one small rule per function.

package com.zillit.desktop.feature.tasks.domain

/**
 * The pure rules of the Tasks tool: the draft a panel edits and the diff it
 * saves, subtasks, sorting, searching, filtering and @mentions.
 *
 * A line-for-line port of the web's `src/tasks/lib/task.js`. No state, no
 * I/O — which is why the cascade rules the server applies (a cancelled main
 * task takes its open subtasks along; an open subtask reopens its main task)
 * are repeated here in [mergeTask]: the list shows the outcome at once and a
 * silent refresh then confirms it.
 */

// -- the draft the panel edits ---------------------------------------------------

/**
 * The editable copy of a task, or of a new task's seed. [parentId] rides along
 * so the panel knows it is editing (or creating) a subtask; it is sent once,
 * on create, and is never part of a diff.
 */
data class TaskDraft(
    val title: String = "",
    val description: String = "",
    val departmentId: String? = null,
    val scenes: String = "",
    val dueDate: String? = null,
    val priority: TaskPriority = TaskPriority.Med,
    val status: TaskStatus = TaskStatus.Todo,
    val assigneeId: String? = null,
    val isSelf: Boolean = false,
    val parentId: String? = null,
)

fun Task.toDraft() = TaskDraft(
    title = title,
    description = description,
    departmentId = departmentId,
    scenes = scenes,
    dueDate = dueDate,
    priority = priority,
    status = status,
    assigneeId = assigneeId,
    isSelf = isSelf,
    parentId = parentId,
)

/** The wire names of the fields `PUT /v2/tasks/:id` accepts. `parent_id` is not one. */
private fun TaskDraft.fields(): Map<String, String?> = mapOf(
    "title" to title,
    "description" to description,
    "department_id" to departmentId,
    "scenes" to scenes,
    "due_date" to dueDate,
    "priority" to priority.wire,
    "status" to status.wire,
    "assignee_id" to assigneeId,
)

private fun String?.norm(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Only the fields that changed, keyed by wire name, ready to PUT. */
fun diffDraft(orig: TaskDraft, draft: TaskDraft): Map<String, String?> {
    val before = orig.fields()
    return draft.fields()
        .filter { (key, value) -> before[key].norm() != value.norm() }
        .mapValues { (key, value) -> if (key == "title") value?.trim().orEmpty() else value?.takeIf { it.isNotEmpty() } }
}

fun isDirty(orig: TaskDraft?, draft: TaskDraft?): Boolean =
    orig != null && draft != null && diffDraft(orig, draft).isNotEmpty()

/**
 * After a save returns: take the server's copy, but keep any field the user
 * changed while the request was in flight (it differs from what was sent), so
 * typing through a Cmd/Ctrl+S is never silently thrown away.
 */
fun rebaseDraft(current: TaskDraft, sent: TaskDraft, server: TaskDraft): TaskDraft = server.copy(
    title = if (current.title.norm() != sent.title.norm()) current.title else server.title,
    description = if (current.description.norm() != sent.description.norm()) current.description else server.description,
    departmentId = if (current.departmentId.norm() != sent.departmentId.norm()) current.departmentId else server.departmentId,
    scenes = if (current.scenes.norm() != sent.scenes.norm()) current.scenes else server.scenes,
    dueDate = if (current.dueDate.norm() != sent.dueDate.norm()) current.dueDate else server.dueDate,
    priority = if (current.priority != sent.priority) current.priority else server.priority,
    status = if (current.status != sent.status) current.status else server.status,
    assigneeId = if (current.assigneeId.norm() != sent.assigneeId.norm()) current.assigneeId else server.assigneeId,
    isSelf = if (current.isSelf != sent.isSelf) current.isSelf else server.isSelf,
)

// -- sorting -----------------------------------------------------------------------

/** Due date first (undated last), then priority (high first), then oldest first. */
val compareTasks: Comparator<Task> = Comparator { a, b ->
    if (a.dueDate != b.dueDate) {
        return@Comparator when {
            a.dueDate == null -> 1
            b.dueDate == null -> -1
            else -> a.dueDate.compareTo(b.dueDate)
        }
    }
    if (a.priority != b.priority) return@Comparator a.priority.ordinal - b.priority.ordinal
    a.createdMillis.compareTo(b.createdMillis)
}

/** The Board column a task sits in: a cancelled task lives in Done. */
fun boardColumn(task: Task): TaskStatus = if (task.status == TaskStatus.Cancelled) TaskStatus.Done else task.status

/** [compareTasks], with cancelled tasks after everything else (the bottom of the Done column). */
val compareBoard: Comparator<Task> = Comparator { a, b ->
    val byCancelled = (if (a.status == TaskStatus.Cancelled) 1 else 0) - (if (b.status == TaskStatus.Cancelled) 1 else 0)
    if (byCancelled != 0) byCancelled else compareTasks.compare(a, b)
}

/**
 * The status a card dropped on a Board column takes, or null when the drop
 * changes nothing (outside every column, or back in the column it sits in).
 * A cancelled card lives in Done: dropping it there changes nothing, dropping
 * it on another column reopens it to that status.
 */
fun dropStatus(task: Task, column: TaskStatus?): TaskStatus? =
    column?.takeIf { it in TaskStatus.board && it != boardColumn(task) }

// -- subtasks ----------------------------------------------------------------------

private fun subtaskRank(status: TaskStatus) = when (status) {
    TaskStatus.Todo, TaskStatus.Progress -> 0
    TaskStatus.Done -> 1
    TaskStatus.Cancelled -> 2
}

/** Open first, then done, then cancelled; inside each, the order they were added. */
val compareSubtasks: Comparator<Task> = Comparator { a, b ->
    val byRank = subtaskRank(a.status) - subtaskRank(b.status)
    if (byRank != 0) byRank else a.createdMillis.compareTo(b.createdMillis)
}

/** A main task's subtasks out of the whole list, in display order. */
fun subtasksOf(tasks: List<Task>, parentId: String?): List<Task> =
    if (parentId == null) emptyList() else tasks.filter { it.parentId == parentId }.sortedWith(compareSubtasks)

/** The numbers behind "3 done · 1 in progress", and the progress bar. [total] leaves cancelled out. */
data class SubtaskStats(val done: Int, val progress: Int, val todo: Int, val cancelled: Int) {
    val open: Int get() = todo + progress
    val total: Int get() = open + done
}

fun subtaskStats(subtasks: List<Task>) = SubtaskStats(
    done = subtasks.count { it.status == TaskStatus.Done },
    progress = subtasks.count { it.status == TaskStatus.Progress },
    todo = subtasks.count { it.status == TaskStatus.Todo },
    cancelled = subtasks.count { it.status == TaskStatus.Cancelled },
)

/**
 * The title of a subtask's main task ('' for a main task): the live one when
 * the caller can see the main task, otherwise the `parent_title` the server
 * sent (a subtask of someone else's private self task).
 */
fun parentTitle(task: Task, byId: Map<String, Task>): String {
    val parent = task.parentId ?: return ""
    return byId[parent]?.title?.takeIf { it.isNotBlank() } ?: task.parentTitle
}

/** The days a due date may take, each side `"YYYY-MM-DD"` or null for no limit. */
data class DueLimits(val min: String? = null, val max: String? = null)

/**
 * A subtask is never due after its main task ([DueLimits.max]), and a main
 * task never before one of its subtasks ([DueLimits.min]). The server enforces
 * both (422 `task_subtask_due_after_main` / `task_due_before_subtasks`); the
 * date picker uses this to grey out the days it would refuse. A main task the
 * list does not hold (someone's private one) gives no limit here.
 */
fun dueLimits(parentId: String?, taskId: String?, byId: Map<String, Task>, tasks: List<Task>): DueLimits = when {
    parentId != null -> DueLimits(max = byId[parentId]?.dueDate)
    taskId == null -> DueLimits()
    else -> DueLimits(min = subtasksOf(tasks, taskId).mapNotNull { it.dueDate }.maxOrNull())
}

enum class DueProblem { AfterMain, BeforeSubtasks }

fun dueProblem(due: String?, limits: DueLimits): DueProblem? = when {
    due == null -> null
    limits.max != null && due > limits.max -> DueProblem.AfterMain
    limits.min != null && due < limits.min -> DueProblem.BeforeSubtasks
    else -> null
}

/**
 * How many open subtasks stop a MAIN task being completed (0 = nothing does).
 * Cancelled subtasks do not count, and a subtask is never blocked. The server
 * enforces the same rule (422 `task_open_subtasks`).
 */
fun closeBlocker(tasks: List<Task>, task: Task?): Int {
    if (task == null || task.id.isEmpty() || task.isSubtask) return 0
    return tasks.count { it.parentId == task.id && it.status.isOpen }
}

/**
 * Put a task returned by a write into the list (keeping the list's comment
 * count current), with what that write did to its relatives, as the server
 * does it: cancelling a main task cancels its open subtasks, and an open
 * subtask means its main task cannot be closed, so it moves to Progress.
 */
fun mergeTask(list: List<Task>, task: Task): List<Task> {
    val prev = list.firstOrNull { it.id == task.id }
    val next = task.copy(
        commentCount = task.comments?.size ?: task.commentCount.takeIf { it > 0 } ?: prev?.commentCount ?: 0,
        comments = null,
        history = null,
    )
    val merged = if (prev != null) list.map { if (it.id == next.id) next else it } else list + next
    return when {
        !next.isSubtask && next.status == TaskStatus.Cancelled ->
            merged.map { if (it.parentId == next.id && it.status.isOpen) it.copy(status = TaskStatus.Cancelled) else it }
        next.isSubtask && next.status.isOpen ->
            merged.map { if (it.id == next.parentId && it.status.isClosed) it.copy(status = TaskStatus.Progress) else it }
        else -> merged
    }
}

/** The list without a task and (deleting a main task deletes them too) its subtasks. */
fun dropTask(list: List<Task>, taskId: String): List<Task> = list.filter { it.id != taskId && it.parentId != taskId }

// -- people ------------------------------------------------------------------------

/**
 * Who assigned the task. The server records it whenever the assignee changes;
 * rows from before that have an assignee but no `assigned_by`, and were
 * assigned by whoever created them.
 */
fun assignerOf(task: Task): String? = task.assignedBy ?: if (task.assigneeId != null) task.createdBy else null

/** A self task nobody else can see yet. */
fun isPrivate(task: Task): Boolean = task.isSelf && (task.assigneeId == null || task.assigneeId == task.createdBy)

private fun words(query: String): List<String> = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

private fun hasAll(haystack: String, list: List<String>): Boolean {
    val text = haystack.lowercase()
    return list.all { text.contains(it) }
}

private fun TaskPerson?.text(): String = if (this == null) "" else "$fullName $designation $department"

/** Crew search: every word typed must be in the name, designation or department. */
fun matchesMember(person: TaskPerson, query: String): Boolean {
    val list = words(query)
    return list.isEmpty() || hasAll(person.text(), list)
}

/** What the searches look names up in. */
class TaskLookup(
    val crewById: Map<String, TaskPerson> = emptyMap(),
    val departmentName: (String?) -> String = { "" },
)

/**
 * Task search: every word typed must be in the title, scenes, description,
 * department name, or the name / designation / department of the assignee or
 * of whoever assigned it. A subtask is also found by its main task's title.
 */
fun matchesTask(task: Task, query: String, lookup: TaskLookup): Boolean {
    val list = words(query)
    if (list.isEmpty()) return true
    val haystack = listOf(
        task.title,
        task.scenes,
        task.description,
        task.parentTitle,
        lookup.departmentName(task.departmentId),
        lookup.crewById[task.assigneeId].text(),
        lookup.crewById[assignerOf(task)].text(),
    ).filter { it.isNotBlank() }.joinToString(" ")
    return hasAll(haystack, list)
}

/** A list screen's rows: open in due order, then the closed ones (done first, then cancelled). */
data class ListRows(val open: List<Task>, val closed: List<Task>)

/** The tasks matching the search, split open / closed. A search looks through closed tasks too. */
fun listRows(tasks: List<Task>, query: String, lookup: TaskLookup): ListRows {
    val hits = tasks.filter { matchesTask(it, query, lookup) }
    return ListRows(
        open = hits.filter { it.status.isOpen }.sortedWith(compareTasks),
        closed = hits.filter { it.status.isClosed }.sortedWith(compareBoard),
    )
}

enum class TaskKind { All, Project, Self }

/** The Board's filter. [assignee] is `""` (anyone), [NO_ASSIGNEE], or a user id. */
data class TaskFilter(
    val kind: TaskKind = TaskKind.All,
    val department: String = "",
    val assignee: String = "",
    val query: String = "",
) {
    companion object {
        const val NO_ASSIGNEE = "none"
    }
}

fun matchesFilter(task: Task, filter: TaskFilter, lookup: TaskLookup): Boolean {
    val kindOk = when (filter.kind) {
        TaskKind.Project -> !task.isSelf
        TaskKind.Self -> task.isSelf
        TaskKind.All -> true
    }
    val assigneeOk = when (filter.assignee) {
        "" -> true
        TaskFilter.NO_ASSIGNEE -> task.assigneeId == null
        else -> task.assigneeId == filter.assignee
    }
    val departmentOk = filter.department.isEmpty() || task.departmentId == filter.department
    return kindOk && assigneeOk && departmentOk && matchesTask(task, filter.query, lookup)
}

/** One department's people in the crew picker. */
data class CrewGroup(val key: String, val name: String, val people: List<TaskPerson>)

/**
 * The crew picker's list: people matching the search, grouped by department
 * (departments A–Z, people without one last), heads of department first inside
 * each group, then by name.
 */
fun groupCrew(
    crew: List<TaskPerson>,
    query: String = "",
    hodIds: Set<String> = emptySet(),
    exclude: String? = null,
    noDepartment: String = "",
): List<CrewGroup> {
    val groups = LinkedHashMap<String, MutableList<TaskPerson>>()
    crew.filter { it.id != exclude && matchesMember(it, query) }
        .forEach { groups.getOrPut(it.department) { mutableListOf() }.add(it) }
    return groups.entries
        .sortedWith { a, b ->
            when {
                a.key.isEmpty() && b.key.isEmpty() -> 0
                a.key.isEmpty() -> 1
                b.key.isEmpty() -> -1
                else -> a.key.compareTo(b.key, ignoreCase = true)
            }
        }
        .map { (name, people) ->
            CrewGroup(
                key = name.ifEmpty { "-" },
                name = name.ifEmpty { noDepartment },
                people = people.sortedWith(
                    compareBy<TaskPerson>({ if (it.id in hodIds) 0 else 1 }, { it.fullName.lowercase() }),
                ),
            )
        }
}

// -- @mentions ---------------------------------------------------------------------

/** The @mention being typed at the caret: [start] is the index of the "@". */
data class MentionAt(val start: Int, val query: String)

/**
 * An "@" counts only at the start of the text or after white space, so an
 * e-mail address is not a mention. A name can hold spaces, so the query runs
 * up to the caret; it stops being one once it is a full name already written
 * ("@Priya Das " and on), unless a longer name still starts that way.
 */
fun mentionAt(text: String, caret: Int, people: List<TaskPerson> = emptyList()): MentionAt? {
    val before = text.take(caret.coerceIn(0, text.length))
    val start = before.lastIndexOf('@')
    if (start < 0 || (start > 0 && !before[start - 1].isWhitespace())) return null
    val query = before.substring(start + 1)
    if (query.length > MENTION_MAX_QUERY || query.firstOrNull()?.isWhitespace() == true || Regex("\\s{2}").containsMatchIn(query)) {
        return null
    }
    val lower = query.lowercase()
    val names = people.map { it.fullName.lowercase() }.filter { it.isNotEmpty() }
    val written = names.any { lower.startsWith("$it ") }
    if (written && names.none { it.startsWith(lower) }) return null
    return MentionAt(start, query)
}

/**
 * Who a mention query offers, names that start with it first. One word matches
 * a name, designation or department; once there is a space it has to be the
 * start of a full name, so "@Priya please look" stops offering people.
 */
fun mentionMatches(people: List<TaskPerson>, query: String): List<TaskPerson> {
    val text = query.lowercase()
    val named = { p: TaskPerson -> p.fullName.lowercase().startsWith(text) }
    val list = if (text.any { it.isWhitespace() }) people.filter(named) else people.filter { it.fullName.isNotEmpty() && matchesMember(it, text) }
    return list.sortedWith(compareBy<TaskPerson>({ if (named(it)) 0 else 1 }, { it.fullName.lowercase() }))
}

/** The text with the mention being typed replaced by "@Full Name ", and where the caret goes next. */
fun applyMention(text: String, mention: MentionAt, caret: Int, person: TaskPerson): Pair<String, Int> {
    val token = "@${person.fullName} "
    val after = text.substring(caret.coerceIn(0, text.length)).removePrefix(" ")
    return (text.substring(0, mention.start) + token + after) to (mention.start + token.length)
}

/**
 * The people a comment @mentions, from the text the comment box produced
 * ("@Full Name ..."). Longest names are matched first so "@Ravi Kapoor" is not
 * also read as a mention of a "Ravi".
 */
fun mentionIds(text: String, people: List<TaskPerson>): List<String> {
    var rest = " $text "
    val ids = mutableListOf<String>()
    people.filter { it.fullName.isNotEmpty() }.sortedByDescending { it.fullName.length }.forEach { person ->
        val token = "@${person.fullName}"
        if (rest.contains(token)) {
            ids += person.id
            rest = rest.replace(token, " ")
        }
    }
    return ids
}

private const val MENTION_MAX_QUERY = 40

// -- history -----------------------------------------------------------------------

/** What one history entry says, before it is worded in the reader's language. */
sealed interface HistoryLine {
    data class Created(val isSubtask: Boolean) : HistoryLine

    /** The assignee a task was created with. */
    data class CreatedAssignee(val assigneeId: String) : HistoryLine

    data class SubtaskAdded(val title: String) : HistoryLine

    data class SubtaskDeleted(val title: String) : HistoryLine

    data object DescriptionChanged : HistoryLine

    /** [from] is null where there was nothing to show before (the line then reads only [to]). */
    data class Field(val field: String, val from: String?, val to: String?, val isSubtask: Boolean) : HistoryLine

    /** An action this build does not know yet still says that something happened. */
    data object Changed : HistoryLine

    data class Note(val reason: String) : HistoryLine
}

/** The lines of one entry, in order. */
fun historyLines(entry: TaskHistoryEntry, isSubtask: Boolean): List<HistoryLine> {
    when (entry.action) {
        "created" -> return buildList {
            add(HistoryLine.Created(isSubtask))
            entry.assigneeId?.let { add(HistoryLine.CreatedAssignee(it)) }
        }
        "subtask_added" -> return listOf(HistoryLine.SubtaskAdded(entry.subtaskTitle))
        "subtask_deleted" -> return listOf(HistoryLine.SubtaskDeleted(entry.subtaskTitle))
    }
    val lines = entry.changes.map { change ->
        if (change.field == "description") HistoryLine.DescriptionChanged
        else HistoryLine.Field(change.field, change.from, change.to, isSubtask)
    }.toMutableList()
    if (lines.isEmpty()) lines += HistoryLine.Changed
    entry.reason?.takeIf { it == "main_cancelled" || it == "subtask_open" }?.let { lines += HistoryLine.Note(it) }
    return lines
}

/** How many entries the panel shows before "Show all". */
const val HISTORY_PREVIEW = 5

// -- department colour -------------------------------------------------------------

private val DEPT_COLOURS = longArrayOf(
    0xFFB25E0C, 0xFF6A4FC4, 0xFF2D5BE3, 0xFF1B6F8F, 0xFFA8407F,
    0xFF0F7D60, 0xFF9C3B3B, 0xFF5B6B1F, 0xFF7A4A2B, 0xFF3D5A80,
)
private const val DEPT_NONE = 0xFF8A91A5
private const val HASH_MULTIPLIER = 31
private const val HASH_MASK = 0xFFFFFFFFL

/** ARGB colour derived from a department's id, so every department gets a stable one. */
fun deptColour(id: String?): Long {
    if (id.isNullOrEmpty()) return DEPT_NONE
    var hash = 0L
    for (ch in id) hash = (hash * HASH_MULTIPLIER + ch.code) and HASH_MASK
    return DEPT_COLOURS[(hash % DEPT_COLOURS.size).toInt()]
}
