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

// -- what the service can keep: Public / Private -------------------------------

/**
 * A task as this deployment really keeps it: without its `visibility` where
 * the service cannot keep one. Everything below then falls back by itself
 * ([visibilityOf] reads a self task as private).
 *
 * The list is held this way, so cards, rows and the search need no check of
 * their own.
 */
fun applyFeatures(task: Task, features: TasksFeatures): Task =
    if (features.visibility) task else task.copy(visibility = null)

/**
 * Who can see a task: [TaskVisibility.Public], or the task's own word. A task
 * that carries none is what it always was — a self task private, a project
 * task public.
 */
fun visibilityOf(task: Task): TaskVisibility =
    task.visibility ?: if (task.isSelf) TaskVisibility.Private else TaskVisibility.Public

/**
 * Who can see a task: null for everyone (public), or the people on a private
 * one — its creator, whoever assigned it and its assignee, each once, in that
 * order. (A private subtask is also seen by the people on its main task; the
 * caller adds those.)
 *
 * The server filters the list; this only says it on screen.
 */
fun whoCanSee(task: Task): List<String>? {
    if (visibilityOf(task) == TaskVisibility.Public) return null
    return listOfNotNull(task.createdBy, task.assignedBy, task.assigneeId).distinct()
}

// -- the draft the panel edits ---------------------------------------------------

/**
 * The editable copy of a task, or of a new task's seed. [parentId] rides along
 * so the panel knows it is editing (or creating) a subtask; it is sent once,
 * on create, and is never part of a diff.
 *
 * [visibility] is always one of the two words: a new project task starts
 * public and a new self task private, as the server does it. It reaches the
 * wire only where the service keeps it.
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
    val visibility: TaskVisibility = TaskVisibility.Public,
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
    visibility = visibilityOf(this),
    parentId = parentId,
)

/**
 * The wire names of the fields `PUT /v2/tasks/:id` accepts, kept to the ones
 * this deployment can store. `parent_id` is not one: it is sent on create and
 * can never change.
 */
private fun TaskDraft.fields(features: TasksFeatures): Map<String, String?> = buildMap {
    put("title", title)
    put("description", description)
    put("department_id", departmentId)
    put("scenes", scenes)
    put("due_date", dueDate)
    put("priority", priority.wire)
    put("status", status.wire)
    put("assignee_id", assigneeId)
    if (features.visibility) put("visibility", visibility.wire)
}

/** The web's `norm`: an empty value is no value; text is compared trimmed (so "   " still differs from ""). */
private fun String?.norm(): String? = if (isNullOrEmpty()) null else trim()

/**
 * Only the fields that changed, keyed by wire name, ready to PUT. A cleared
 * description or scenes goes as `""`, as the web sends it; a cleared date,
 * person or department as null (the draft never holds `""` for those).
 *
 * [features] is what the service can keep: called without it — the default —
 * `visibility` is never part of a diff.
 */
fun diffDraft(orig: TaskDraft, draft: TaskDraft, features: TasksFeatures = TasksFeatures()): Map<String, String?> {
    val before = orig.fields(features)
    return draft.fields(features)
        .filter { (key, value) -> before[key].norm() != value.norm() }
        .mapValues { (key, value) -> if (key == "title") value?.trim().orEmpty() else value }
}

fun isDirty(orig: TaskDraft?, draft: TaskDraft?, features: TasksFeatures = TasksFeatures()): Boolean =
    orig != null && draft != null && diffDraft(orig, draft, features).isNotEmpty()

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
    visibility = if (current.visibility != sent.visibility) current.visibility else server.visibility,
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

/** Which rule set the limit, and so what the note under the calendar explains. */
enum class DueWhy { Main, Subtasks }

/**
 * The days a due date may take, each side `"YYYY-MM-DD"` or null for no limit.
 * [today] is the viewer's own day, kept so [dueProblem] can tell a date in the
 * past from one a relative holds back.
 */
data class DueLimits(
    val min: String? = null,
    val max: String? = null,
    val today: String? = null,
    val why: DueWhy? = null,
)

/**
 * The range a task's due date may take, and why:
 *
 *  - **never before today** ([today], the viewer's own day). A task that is
 *    already overdue keeps its date until someone changes it, so only a date
 *    that is new or changed is checked;
 *  - a subtask is never due after its main task ([DueLimits.max]);
 *  - a main task is never due before its latest subtask ([DueLimits.min]).
 *    **Cancelled subtasks are ignored.**
 *
 * The server enforces all three (422 `task_due_date_past`, with a day of
 * leeway for timezones; `task_subtask_due_after_main`; `task_due_before_subtasks`
 * — it still counts cancelled subtasks there, which is a backend difference).
 * The date picker uses this to grey out the days it would refuse. A main task
 * the list does not hold (someone's private one) gives no limit here, and the
 * server still checks.
 */
fun dueLimits(
    parentId: String?,
    taskId: String?,
    byId: Map<String, Task>,
    tasks: List<Task>,
    today: String? = null,
): DueLimits {
    if (parentId != null) {
        val max = byId[parentId]?.dueDate
        return DueLimits(min = today, max = max, today = today, why = max?.let { DueWhy.Main })
    }
    val latest = taskId?.let {
        subtasksOf(tasks, it).filter { sub -> sub.status != TaskStatus.Cancelled }.mapNotNull { sub -> sub.dueDate }.maxOrNull()
    }
    // A subtask due today or earlier sets nothing "today" does not already.
    if (latest != null && (today == null || latest > today)) {
        return DueLimits(min = latest, today = today, why = DueWhy.Subtasks)
    }
    return DueLimits(min = today, today = today)
}

enum class DueProblem { Past, AfterMain, BeforeSubtasks }

/** Why a due date is out of range, or null when it is fine (or empty). */
fun dueProblem(due: String?, limits: DueLimits): DueProblem? = when {
    due == null -> null
    limits.today != null && due < limits.today -> DueProblem.Past
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

/**
 * A task **only its creator can see** ("Only you"): private, with nobody else
 * on it — no assignee but the creator, and nobody else who assigned it.
 *
 * It follows [Task.visibility] where the task has one. A task without one is
 * what it was before the field existed: a self task nobody is assigned yet.
 *
 * Unlike the standalone app this also counts the assigner: a private task
 * handed back to its creator by someone else is still seen by that someone
 * (the service's rule), so it is not "only you".
 */
fun isPrivate(task: Task): Boolean {
    if (task.visibility == null) return task.isSelf && (task.assigneeId == null || task.assigneeId == task.createdBy)
    val people = whoCanSee(task) ?: return false
    return people.all { it == task.createdBy }
}

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

/**
 * The Board's filter. [assignee] is `""` (anyone), [NO_ASSIGNEE], or a user id.
 *
 * There is no project / self switch: the tabs above the Board are the views.
 */
data class TaskFilter(
    val department: String = "",
    val assignee: String = "",
    val query: String = "",
) {
    companion object {
        const val NO_ASSIGNEE = "none"
    }
}

fun matchesFilter(task: Task, filter: TaskFilter, lookup: TaskLookup): Boolean {
    val assigneeOk = when (filter.assignee) {
        "" -> true
        TaskFilter.NO_ASSIGNEE -> task.assigneeId == null
        else -> task.assigneeId == filter.assignee
    }
    val departmentOk = filter.department.isEmpty() || task.departmentId == filter.department
    return assigneeOk && departmentOk && matchesTask(task, filter.query, lookup)
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

    /**
     * "Added image “recce.jpg”", "Removed file “budget.xlsx”".
     *
     * An attachment is its own kind of entry, not a change to a field: the
     * server records it as `attachment_added` / `attachment_removed` with the
     * name and which of the two it was.
     */
    data class AttachmentChanged(val name: String, val isImage: Boolean, val added: Boolean) : HistoryLine

    data object DescriptionChanged : HistoryLine

    /**
     * "Made this task Private" — a sentence rather than "Field: from → to",
     * as the standalone app writes it.
     */
    data class VisibilityChanged(val visibility: TaskVisibility, val isSubtask: Boolean) : HistoryLine

    /** [from] is null where there was nothing to show before (the line then reads only [to]). */
    data class Field(val field: String, val from: String?, val to: String?, val isSubtask: Boolean) : HistoryLine

    /** An action this build does not know yet still says that something happened. */
    data object Changed : HistoryLine

    data class Note(val reason: String) : HistoryLine
}

/**
 * The entries that are an event rather than a change to a field: null for
 * everything else, which is then read off `changes`.
 */
private fun eventLines(entry: TaskHistoryEntry, isSubtask: Boolean): List<HistoryLine>? = when (entry.action) {
    "created" -> buildList {
        add(HistoryLine.Created(isSubtask))
        entry.assigneeId?.let { add(HistoryLine.CreatedAssignee(it)) }
    }

    "subtask_added" -> listOf(HistoryLine.SubtaskAdded(entry.subtaskTitle))
    "subtask_deleted" -> listOf(HistoryLine.SubtaskDeleted(entry.subtaskTitle))
    "attachment_added", "attachment_removed" -> listOf(
        HistoryLine.AttachmentChanged(
            name = entry.attachmentName,
            isImage = entry.attachmentKind == AttachmentKind.Image,
            added = entry.action == "attachment_added",
        ),
    )

    else -> null
}

/** The lines of one entry, in order. */
fun historyLines(entry: TaskHistoryEntry, isSubtask: Boolean): List<HistoryLine> {
    eventLines(entry, isSubtask)?.let { return it }
    val lines = entry.changes.map { change ->
        when (change.field) {
            "description" -> HistoryLine.DescriptionChanged
            "visibility" -> HistoryLine.VisibilityChanged(
                TaskVisibility.fromWire(change.to) ?: TaskVisibility.Public,
                isSubtask,
            )
            else -> HistoryLine.Field(change.field, change.from, change.to, isSubtask)
        }
    }.toMutableList()
    if (lines.isEmpty()) lines += HistoryLine.Changed
    entry.reason?.takeIf { it in HISTORY_REASONS }?.let { lines += HistoryLine.Note(it) }
    return lines
}

/** Fields that are no longer shown anywhere, so not in the history either. */
private val GONE_FIELDS = setOf("dpr")

/**
 * The entries the History tab lists: a change to a field that is gone (the
 * DPR) is left out of its entry, and an entry that changed nothing else is
 * left out altogether.
 */
fun visibleHistory(history: List<TaskHistoryEntry>?): List<TaskHistoryEntry> =
    history.orEmpty().mapNotNull { entry ->
        if (entry.action != "updated") return@mapNotNull entry
        val changes = entry.changes.filterNot { it.field in GONE_FIELDS }
        when {
            changes.size == entry.changes.size -> entry
            changes.isEmpty() -> null
            else -> entry.copy(changes = changes)
        }
    }

/** The reasons the server marks a change of its own with; anything else is not worded. */
private val HISTORY_REASONS = setOf("main_cancelled", "subtask_open", "main_visibility")

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
