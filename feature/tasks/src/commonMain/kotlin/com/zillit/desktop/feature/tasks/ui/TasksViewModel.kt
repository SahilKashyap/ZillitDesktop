@file:Suppress("CyclomaticComplexMethod","TooManyFunctions","ReturnCount","MaxLineLength") // The board list and the panel share one write path, so they share one view model (as the web provider and drawer do).

package com.zillit.desktop.feature.tasks.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.data.httpStatus
import com.zillit.desktop.feature.tasks.data.tasksSyncEvents
import com.zillit.desktop.feature.tasks.domain.DueProblem
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskWrite
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.diffDraft
import com.zillit.desktop.feature.tasks.domain.dropTask
import com.zillit.desktop.feature.tasks.domain.dueLimits
import com.zillit.desktop.feature.tasks.domain.dueProblem
import com.zillit.desktop.feature.tasks.domain.mentionIds
import com.zillit.desktop.feature.tasks.domain.mergeTask
import com.zillit.desktop.feature.tasks.domain.rebaseDraft
import com.zillit.desktop.feature.tasks.domain.toDraft
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/**
 * The Tasks tool — the web's `TasksProvider` and `TaskDrawer` rolled into one
 * view model: the task list, who is assignable, and the panel's whole life.
 *
 * One GET feeds the Board, My Tasks and Self Tasks; the views filter that list.
 * Every list load and every local write takes a sequence number, and a load
 * only applies if nothing newer happened while it was in flight — so an older
 * GET never overwrites a newer save, delete or load.
 */
class TasksViewModel(
    private val repository: TasksRepository,
    /** Resolved on every [TasksEvent.Load]: view models are built before any production is open. */
    private val viewer: () -> TasksViewer = { TasksViewer() },
    /** Who is looking and the crew; the host feeds it from the project context. */
    private val people: Flow<TaskPeople> = emptyFlow(),
    private val rights: RightsRequestBus? = null,
    private val events: SocketEventBus? = null,
    private val newId: () -> String = ::randomId,
) : ZillitViewModel<TasksUiState, TasksEvent, Nothing>(TasksUiState()) {

    /** Every load and every local write takes a new number; see the class note. */
    private var seq = 0
    private var panelKeys = 0L
    private var quickId = newId()
    private var refreshJob: Job? = null
    private var rightsPoll: Job? = null

    init {
        launch { people.collect { setState { copy(me = it.me, crew = it.crew) } } }
        events?.let { bus ->
            launch { bus.onAny(tasksSyncEvents).collect { message -> onSocket(message.payload) } }
        }
    }

    override fun onEvent(event: TasksEvent) {
        when (event) {
            TasksEvent.Load -> load()
            TasksEvent.Refresh -> loadList(silent = true)
            is TasksEvent.ViewChanged -> setState { copy(view = event.view) }
            is TasksEvent.BoardFilterChanged -> setState { copy(boardFilter = event.filter) }
            is TasksEvent.MineQueryChanged -> setState { copy(mineQuery = event.query) }
            is TasksEvent.SelfQueryChanged -> setState { copy(selfQuery = event.query) }
            TasksEvent.DismissMessage -> setState { copy(error = null, notice = null) }
            is TasksEvent.SetStatus -> setStatus(event.task, event.status)
            is TasksEvent.Delete -> deleteTask(event.task)
            is TasksEvent.Share -> share(event.taskId, event.assigneeId)
            is TasksEvent.QuickAdd -> quickAdd(event)
            TasksEvent.AskForRights -> askForRights()
            is TasksEvent.OpenTask -> leave { openTask(event.taskId) }
            is TasksEvent.NewTask -> newTask(event.seed)
            is TasksEvent.NewSubtask -> leave { newSubtask(event.main) }
            TasksEvent.BackToParent -> leave { backToParent() }
            TasksEvent.ClosePanel -> closePanel()
            TasksEvent.CancelCreate -> cancelCreate()
            is TasksEvent.DraftChanged -> editPanel { copy(draft = event.draft, nag = false) }
            is TasksEvent.PickStatus -> pickStatus(event.status)
            is TasksEvent.ChooseStatus -> chooseStatus(event.status)
            is TasksEvent.Save -> save(event.overwrite)
            TasksEvent.Create -> create()
            TasksEvent.Discard -> editPanel { copy(draft = orig, nag = false) }
            TasksEvent.LoadTheirs -> currentState.panel?.conflict?.let(::applyServer)
            TasksEvent.DeleteOpenTask -> currentState.panel?.task?.let(::deleteTask)
            is TasksEvent.CommentChanged -> editPanel { copy(comment = event.text) }
            TasksEvent.SendComment -> sendComment()
        }
    }

    // -- loading -----------------------------------------------------------------------

    /**
     * Rights first: nothing may call the service until the tool's row has
     * confirmed it, because a 403 from it would read as "no longer a member".
     * The rights list can still be in flight when the tool opens, so an
     * unanswered list is asked again for a few seconds.
     */
    private fun load() {
        val now = viewer()
        setState { copy(viewer = now) }
        if (!now.resolved) {
            rightsPoll?.cancel()
            rightsPoll = launch {
                repeat(RIGHTS_POLLS) {
                    delay(RIGHTS_POLL_MS)
                    val again = viewer()
                    if (again.resolved) {
                        setState { copy(viewer = again) }
                        if (again.canCall) loadAll()
                        return@launch
                    }
                }
            }
            return
        }
        if (now.canCall) loadAll()
    }

    private fun loadAll() {
        loadList(silent = false)
        launch {
            (repository.assignees() as? ZillitResult.Success)?.data?.let { answer ->
                setState { copy(assignableIds = answer.userIds, hodIds = answer.hodIds) }
            }
        }
        launch {
            (repository.departments() as? ZillitResult.Success)?.data?.let { list ->
                setState { copy(departments = list) }
            }
        }
    }

    /** The whole visible list. [silent] skips the loader, as a socket refresh must. */
    private fun loadList(silent: Boolean) {
        if (!currentState.viewer.canCall) return
        seq += 1
        val mine = seq
        if (!silent) setState { copy(loading = true) }
        launch {
            val answer = repository.tasks()
            setState { copy(loaded = true, loading = false) }
            when (answer) {
                is ZillitResult.Success -> if (mine == seq) setTasks { answer.data }
                is ZillitResult.Failure -> if (!silent) fail(answer.error)
            }
        }
    }

    private fun onSocket(payload: kotlinx.serialization.json.JsonElement?) {
        if (!currentState.viewer.canCall) return
        val taskId = ((payload as? JsonObject)?.get("task_id") as? JsonPrimitive)?.contentOrNull
        // A burst of frames is one reload.
        refreshJob?.cancel()
        refreshJob = launch {
            delay(SOCKET_DEBOUNCE_MS)
            loadList(silent = true)
            val open = currentState.panel
            if (open?.task != null && taskId != null && open.task.id == taskId) fetchTask(open.key, taskId, again = true)
        }
    }

    // -- the list ----------------------------------------------------------------------

    /** Every change to the list goes through here, so the open panel can follow it. */
    private fun setTasks(change: (List<Task>) -> List<Task>) {
        setState { copy(tasks = change(tasks)) }
        followList()
    }

    private fun upsert(task: Task) {
        seq += 1
        setTasks { mergeTask(it, task) }
    }

    private fun remove(taskId: String) {
        seq += 1
        setTasks { dropTask(it, taskId) }
    }

    private fun fail(error: ZillitError) = setState { copy(error = error.localised()) }

    private fun notice(text: String) = setState { copy(notice = text) }

    private fun blockerText(open: Int) =
        if (open == 1) str(S.desktop_tasks_open_subtasks_one) else str(S.desktop_tasks_open_subtasks, open)

    // -- one-click writes --------------------------------------------------------------

    private fun busy(taskId: String, on: Boolean) =
        setState { copy(busy = if (on) busy + taskId else busy - taskId) }

    /**
     * Change a task's status in one click (tick box, ⋯ menu). A main task with
     * open subtasks cannot be completed: that is refused here, with the reason,
     * before anything is sent (the server refuses it too, 422 `task_open_subtasks`).
     * No `expected_updated`: a one-click change never conflicts.
     */
    private fun setStatus(task: Task, status: TaskStatus) {
        val state = currentState
        if (!state.canPost || status == task.status || task.id in state.busy) return
        if (status == TaskStatus.Done) {
            val open = closeBlocker(state.tasks, task)
            if (open > 0) {
                notice(blockerText(open))
                return
            }
        }
        launch {
            busy(task.id, true)
            writeOutcome(task.id, repository.update(task.id, mapOf("status" to status.wire)))
            busy(task.id, false)
            // The write may have moved other tasks, or been refused because of
            // subtasks this list had not heard of yet: either way, catch up.
            loadList(silent = true)
        }
    }

    private fun share(taskId: String, assigneeId: String?) {
        if (!currentState.canPost || taskId in currentState.busy) return
        launch {
            busy(taskId, true)
            writeOutcome(taskId, repository.update(taskId, mapOf("assignee_id" to assigneeId)))
            busy(taskId, false)
        }
    }

    /** Takes the answer of a write into the list; a refusal is shown, and a task that is gone leaves. */
    private fun writeOutcome(taskId: String, write: TaskWrite): Boolean = when (write) {
        is TaskWrite.Saved -> {
            upsert(write.task)
            true
        }
        is TaskWrite.Conflict -> {
            upsert(write.latest)
            false
        }
        is TaskWrite.Refused -> {
            fail(write.error)
            if (write.isGone) remove(taskId)
            false
        }
    }

    /**
     * Delete a task (its subtasks go with it). Checked here first — posting
     * rights, and the task's own `can_delete` — then by the server. If the
     * panel shows what was deleted it moves on: from a deleted subtask back to
     * its main task, otherwise it closes.
     */
    private fun deleteTask(task: Task) {
        if (!currentState.canPost || !task.canDelete) return
        launch {
            busy(task.id, true)
            val answer = repository.delete(task.id)
            busy(task.id, false)
            val gone = answer is ZillitResult.Success || (answer as? ZillitResult.Failure)?.error?.httpStatus == NOT_FOUND
            if (answer is ZillitResult.Failure && !gone) fail(answer.error)
            if (gone) {
                // Read the list as it is now, before the task and its subtasks leave it.
                val before = currentState.byId
                val mainTaskId = task.parentId?.takeIf { it in before }
                remove(task.id)
                setState {
                    val open = panel
                    val showsDeleted = open != null && !open.creating &&
                        (open.taskId == task.id || before[open.taskId]?.parentId == task.id)
                    val inNewSubtask = open != null && open.creating && open.parentId == task.id
                    when {
                        showsDeleted && open.taskId == task.id && mainTaskId != null ->
                            copy(panel = pointedAt(mainTaskId))
                        showsDeleted || inNewSubtask -> copy(panel = null)
                        else -> this
                    }
                }
                currentState.panel?.takeIf { !it.creating && it.task == null }?.let { fetchTask(it.key, it.taskId.orEmpty()) }
            }
            loadList(silent = true)
        }
    }

    /** The quick-add row of Self Tasks. One id per task being added, kept if the create is retried. */
    private fun quickAdd(event: TasksEvent.QuickAdd) {
        val title = event.title.trim()
        if (title.isEmpty() || currentState.quickAdding || !currentState.canPost) return
        setState { copy(quickAdding = true) }
        launch {
            val draft = TaskDraft(title = title, dueDate = event.dueDate, assigneeId = event.assigneeId, isSelf = true)
            val write = repository.create(draft, quickId)
            setState { copy(quickAdding = false) }
            if (write is TaskWrite.Saved) {
                upsert(write.task)
                quickId = newId()
            } else if (write is TaskWrite.Refused) {
                fail(write.error)
            }
        }
    }

    private fun askForRights() {
        rights?.ask(str(S.desktop_tasks_tool_label), RightsKind.Post)
            ?: setState { copy(error = str(S.desktop_tasks_read_only_hint)) }
    }

    // -- the panel: opening and closing -------------------------------------------------

    private fun editPanel(change: TaskPanel.() -> TaskPanel) {
        setState { copy(panel = panel?.change()) }
    }

    /** A new panel instance pointed at an existing task. */
    private fun pointedAt(taskId: String) = TaskPanel(key = ++panelKeys, creating = false, taskId = taskId)

    /** Leaving this task for another replaces what the panel holds: never with unsaved edits. */
    private fun leave(move: () -> Unit) {
        val open = currentState.panel
        if (open != null && open.dirty) {
            editPanel { copy(nag = true) }
            return
        }
        move()
    }

    private fun openTask(taskId: String) {
        val next = pointedAt(taskId)
        setState { copy(panel = next) }
        fetchTask(next.key, taskId)
    }

    private fun newTask(seed: TaskDraft) {
        val draft = seed
        setState {
            copy(panel = TaskPanel(key = ++panelKeys, creating = true, orig = draft, draft = draft, uniqueId = newId()))
        }
    }

    /** The create form for a subtask of [main], starting in the main task's department. */
    private fun newSubtask(main: Task) {
        if (main.isSubtask) return
        val seed = TaskDraft(departmentId = main.departmentId, isSelf = main.isSelf, parentId = main.id)
        setState {
            copy(
                panel = TaskPanel(
                    key = ++panelKeys, creating = true, orig = seed, draft = seed,
                    parentId = main.id, parentTitle = main.title, uniqueId = newId(),
                ),
            )
        }
    }

    /** From a subtask (open, or being created) back to its main task; with none to go to, the panel closes. */
    private fun backToParent() {
        val open = currentState.panel ?: return
        val target = open.parentId ?: open.draft?.parentId ?: open.task?.parentId
        if (target == null) setState { copy(panel = null) } else openTask(target)
    }

    private fun closePanel() {
        val open = currentState.panel ?: return
        if (open.dirty) editPanel { copy(nag = true) } else setState { copy(panel = null) }
    }

    private fun cancelCreate() {
        val open = currentState.panel ?: return
        val parent = open.draft?.parentId
        if (parent != null && parent in currentState.byId) backToParent() else setState { copy(panel = null) }
    }

    // -- the panel: reading ------------------------------------------------------------

    /** Load (or, with [again], re-load after a socket event or a change in the list) the open task. */
    private fun fetchTask(key: Long, taskId: String, again: Boolean = false) {
        launch {
            val answer = repository.task(taskId)
            val open = currentState.panel
            if (open == null || open.key != key) return@launch
            when (answer) {
                is ZillitResult.Success ->
                    if (again && open.dirty) mergeComments(answer.data) else applyServer(answer.data)
                is ZillitResult.Failure -> {
                    // Deleted, or no longer visible (a self task reassigned away): say so and close.
                    if (answer.error.httpStatus == NOT_FOUND) {
                        remove(taskId)
                        setState { copy(panel = null) }
                        fail(answer.error)
                    } else if (!again) {
                        // A background refresh that failed for any other reason keeps the panel and its edits.
                        setState { copy(panel = null) }
                        fail(answer.error)
                    }
                }
            }
        }
    }

    private fun applyServer(fresh: Task) {
        val draft = fresh.toDraft()
        setState {
            copy(panel = panel?.copy(task = fresh, orig = draft, draft = draft, conflict = null, nag = false))
        }
        // Not before the list has loaded (a deep link can get here first): a
        // local change makes the list load still in flight be dropped, and that
        // load brings this task anyway.
        if (currentState.loaded) upsert(fresh)
    }

    /**
     * Comments and history only: keeps `task.updated`, so a later save still
     * detects (409) anyone else's edit that arrived while this panel had
     * unsaved changes.
     */
    private fun mergeComments(fresh: Task) {
        editPanel { copy(task = task?.copy(comments = fresh.comments, history = fresh.history)) }
    }

    /**
     * The open task follows the list. A write somewhere else reaches the list
     * first (a subtask reopened from its row reopens this main task; a
     * cancelled main task takes this subtask along; someone else's change
     * arrives by socket): when the list's copy differs, or the task has left
     * the list, read it again. With unsaved edits that only merges comments.
     */
    private fun followList() {
        val open = currentState.panel ?: return
        val task = open.task ?: return
        if (open.saving) return
        val listed = currentState.byId[task.id]
        if (listed != null) {
            if (!open.seenInList) editPanel { copy(seenInList = true) }
            if (listed.updatedMillis != task.updatedMillis || listed.status != task.status) fetchTask(open.key, task.id, again = true)
        } else if (open.seenInList) {
            editPanel { copy(seenInList = false) }
            fetchTask(open.key, task.id, again = true)
        }
    }

    // -- the panel: writing ------------------------------------------------------------

    private fun pickStatus(status: TaskStatus) {
        val open = currentState.panel ?: return
        val blocker = open.task?.let { closeBlocker(currentState.tasks, it) } ?: 0
        if (status == TaskStatus.Done && blocker > 0) {
            notice(blockerText(blocker))
            return
        }
        editPanel { copy(draft = draft?.copy(status = status), nag = false) }
    }

    private fun chooseStatus(status: TaskStatus) {
        val open = currentState.panel ?: return
        val task = open.task ?: return
        if (!currentState.canPost) return
        val blocker = closeBlocker(currentState.tasks, task)
        if (status == TaskStatus.Done && blocker > 0) {
            notice(blockerText(blocker))
            return
        }
        if (open.dirty || open.conflict != null || open.saving) {
            editPanel { copy(draft = draft?.copy(status = status), nag = false) }
        } else if (status != task.status) {
            put(open, mapOf("status" to status.wire), expected = null)
        }
    }

    private fun dueNotice(due: String?, parentId: String?, taskId: String?): Boolean {
        val state = currentState
        val limits = dueLimits(parentId, taskId, state.byId, state.tasks)
        val problem = dueProblem(due, limits) ?: return false
        notice(
            when (problem) {
                DueProblem.AfterMain -> str(S.desktop_tasks_due_after_main, limits.max.orEmpty())
                DueProblem.BeforeSubtasks -> str(S.desktop_tasks_due_before_subtasks, limits.min.orEmpty())
            },
        )
        return true
    }

    private fun save(overwrite: Boolean) {
        val open = currentState.panel ?: return
        val task = open.task ?: return
        val orig = open.orig ?: return
        val draft = open.draft ?: return
        // Never write without posting rights: the 403 would bounce the user out of the project.
        if (open.saving || !currentState.canPost || draft.title.isBlank()) return
        val changes = diffDraft(orig, draft)
        if (changes.isEmpty()) return
        val blocker = closeBlocker(currentState.tasks, task)
        if (changes["status"] == TaskStatus.Done.wire && blocker > 0) {
            notice(blockerText(blocker))
            return
        }
        // Likewise a due date out of range (the main task's date may have moved since it was picked).
        if ("due_date" in changes && dueNotice(changes["due_date"], draft.parentId, task.id)) return
        put(open, changes, expected = if (overwrite) null else task.updatedMillis)
    }

    /** PUT [changes] and take the server's reply, keeping anything typed while it was in flight. */
    private fun put(open: TaskPanel, changes: Map<String, String?>, expected: Long?) {
        val task = open.task ?: return
        val sent = open.draft ?: return
        // A status change can move other tasks (a cancelled main task takes its
        // open subtasks along; a reopened subtask reopens its main task).
        val cascades = "status" in changes
        editPanel { copy(saving = true) }
        launch {
            val answer = repository.update(task.id, changes, expected)
            val now = currentState.panel
            val stillOpen = now != null && now.key == open.key
            if (stillOpen) editPanel { copy(saving = false) }
            if (!stillOpen) {
                if (answer is TaskWrite.Saved) upsert(answer.task)
                if (cascades) loadList(silent = true)
                return@launch
            }
            when (answer) {
                is TaskWrite.Saved -> {
                    val serverDraft = answer.task.toDraft()
                    editPanel {
                        copy(
                            task = answer.task,
                            orig = serverDraft,
                            draft = rebaseDraft(draft ?: serverDraft, sent, serverDraft),
                            conflict = null,
                            nag = false,
                        )
                    }
                    upsert(answer.task)
                    if (cascades) loadList(silent = true)
                }
                is TaskWrite.Conflict -> editPanel { copy(conflict = answer.latest, nag = false) }
                is TaskWrite.Refused -> {
                    fail(answer.error)
                    if (answer.isGone) {
                        remove(task.id)
                        setState { copy(panel = null) }
                    } else if (cascades) {
                        // Refused, perhaps because of subtasks this list had not heard of yet: catch the list up.
                        loadList(silent = true)
                    }
                }
            }
        }
    }

    private fun create() {
        val open = currentState.panel ?: return
        val draft = open.draft ?: return
        if (open.saving || !currentState.canPost || draft.title.isBlank()) return
        if (dueNotice(draft.dueDate, draft.parentId, null)) return
        editPanel { copy(saving = true) }
        launch {
            val answer = repository.create(draft, open.uniqueId)
            val stillOpen = currentState.panel?.key == open.key
            if (stillOpen) editPanel { copy(saving = false) }
            when (answer) {
                is TaskWrite.Saved -> {
                    upsert(answer.task)
                    if (draft.parentId == null) {
                        if (stillOpen) setState { copy(panel = null) }
                    } else {
                        // A new subtask can reopen its main task: catch the list up, and go back to the main task.
                        loadList(silent = true)
                        if (stillOpen) openTask(draft.parentId)
                    }
                }
                is TaskWrite.Refused -> {
                    fail(answer.error)
                    // A subtask refused because its main task is gone, or no longer a main task:
                    // the list is behind. The form stays, with what was typed.
                    if (draft.parentId != null) loadList(silent = true)
                }
                is TaskWrite.Conflict -> Unit
            }
        }
    }

    private fun sendComment() {
        val open = currentState.panel ?: return
        val task = open.task ?: return
        val text = open.comment.trim()
        if (text.isEmpty() || open.sending || !currentState.canPost) return
        val mentionable = mentionablePeople(task)
        editPanel { copy(sending = true) }
        launch {
            val answer = repository.comment(task.id, text, mentionIds(text, mentionable))
            val stillOpen = currentState.panel?.key == open.key
            if (stillOpen) editPanel { copy(sending = false) }
            when (answer) {
                is ZillitResult.Success -> {
                    // The server's fresh copy, never this panel's possibly stale one.
                    upsert(answer.data)
                    if (!stillOpen) return@launch
                    if (currentState.panel?.dirty == true) mergeComments(answer.data) else applyServer(answer.data)
                    editPanel { copy(comment = "") }
                }
                is ZillitResult.Failure -> fail(answer.error)
            }
        }
    }

    /**
     * Who can be @mentioned: people who can view Tasks; for a self task only the
     * people who can see it — its creator and assignee, and for a subtask its
     * main task's too (the server drops anyone else).
     */
    fun mentionablePeople(task: Task): List<com.zillit.desktop.feature.tasks.domain.TaskPerson> {
        val state = currentState
        val main = task.parentId?.let { state.byId[it] }
        val seen = listOfNotNull(task.createdBy, task.assigneeId, main?.createdBy, main?.assigneeId)
        val pool = if (task.isSelf) state.assignableCrew.filter { it.id in seen } else state.assignableCrew
        return pool.filter { it.id != state.me }
    }

    private companion object {
        const val SOCKET_DEBOUNCE_MS = 300L
        const val RIGHTS_POLLS = 20
        const val RIGHTS_POLL_MS = 400L
        const val NOT_FOUND = 404

        fun randomId(): String =
            "t-${currentMillis().toString(RADIX)}-${Random.nextLong().toULong().toString(RADIX)}"

        fun currentMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
        const val RADIX = 36
    }
}
