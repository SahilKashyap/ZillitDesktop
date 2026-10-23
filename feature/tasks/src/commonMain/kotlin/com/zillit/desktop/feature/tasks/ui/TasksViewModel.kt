@file:Suppress("CyclomaticComplexMethod","TooManyFunctions","ReturnCount","MaxLineLength","LargeClass") // The board list and the panel share one write path, so they share one view model (as the web provider and drawer do).

package com.zillit.desktop.feature.tasks.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.data.httpStatus
import com.zillit.desktop.feature.tasks.data.tasksSyncEvents
import com.zillit.desktop.feature.tasks.domain.DueProblem
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.PickedTaskFile
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.TaskWrite
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.domain.applyFeatures
import com.zillit.desktop.feature.tasks.domain.attachmentIds
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.diffDraft
import com.zillit.desktop.feature.tasks.domain.dropTask
import com.zillit.desktop.feature.tasks.domain.dueLimits
import com.zillit.desktop.feature.tasks.domain.dueProblem
import com.zillit.desktop.feature.tasks.domain.formSig
import com.zillit.desktop.feature.tasks.domain.mentionIds
import com.zillit.desktop.feature.tasks.domain.mergeTask
import com.zillit.desktop.feature.tasks.domain.rebaseDraft
import com.zillit.desktop.feature.tasks.domain.toDraft
import com.zillit.desktop.feature.tasks.domain.visibilityOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
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
    /**
     * The host's file dialog, for "Add image" and "Attach file".
     *
     * A seam rather than an effect: this view model has no effect type, and
     * the dialog is the host's either way. The default picks nothing, so a
     * test and the render harness build this without one.
     */
    private val pickFiles: suspend (imagesOnly: Boolean) -> List<PickedTaskFile> = { emptyList() },
) : ZillitViewModel<TasksUiState, TasksEvent, Nothing>(TasksUiState()) {

    /** Every load and every local write takes a new number; see the class note. */
    private var seq = 0
    private var panelKeys = 0L
    private var quickId = newId()
    private var refreshJob: Job? = null
    private var panelRefreshJob: Job? = null
    private var rightsPoll: Job? = null
    private var projectId: String? = null

    /** Images and files in the open panel's description. */
    private val attachments = TaskAttachmentsWork(this, repository) { imagesOnly -> pickFiles(imagesOnly) }

    /** Drafts — a new task saved to finish later. */
    private val drafts = TaskDraftsWork(this, repository)

    /** The tool has been opened at least once: rights changes from then on reload it. */
    private var started = false

    init {
        launch {
            people.collect {
                projectId = it.projectId
                setState { copy(me = it.me, crew = it.crew) }
            }
        }
        events?.let { bus ->
            launch { bus.onAny(tasksSyncEvents).collect { message -> onSocket(message.payload) } }
        }
    }

    override fun onEvent(event: TasksEvent) {
        when (event) {
            TasksEvent.Load -> load()
            TasksEvent.Refresh -> loadList(silent = true)
            is TasksEvent.ViewChanged -> viewChanged(event.view)
            is TasksEvent.ScopeChanged -> scopeChanged(event)
            is TasksEvent.BoardFilterChanged -> setState { copy(boardFilter = event.filter) }
            is TasksEvent.MineQueryChanged -> setState { copy(mineQuery = event.query) }
            is TasksEvent.SelfQueryChanged -> setState { copy(selfQuery = event.query) }
            TasksEvent.DismissMessage -> setState { copy(error = null, notice = null, success = null) }
            is TasksEvent.SetStatus -> setStatus(event.task, event.status)
            is TasksEvent.SetVisibility -> setVisibility(event.task, event.visibility)
            is TasksEvent.Move -> move(event.task, event.status)
            is TasksEvent.Delete -> deleteTask(event.task)
            is TasksEvent.Share -> share(event.taskId, event.assigneeId)
            is TasksEvent.QuickAdd -> quickAdd(event)
            TasksEvent.AskForRights -> askForRights()
            is TasksEvent.OpenChooser -> openChooser(event.status)
            TasksEvent.CloseChooser -> setState { copy(chooser = null) }
            is TasksEvent.OpenTask -> leave(event) { openTask(event.taskId) }
            is TasksEvent.NewTask -> leave(event) { newTask(event.seed, event.pickAssignee) }
            is TasksEvent.NewSubtask -> leave(event) { newSubtask(event.main) }
            TasksEvent.BackToParent -> leave(TasksEvent.BackToParent) { backToParent() }
            TasksEvent.ClosePanel -> closePanel()
            TasksEvent.CancelCreate -> cancelCreate()
            is TasksEvent.DraftChanged -> editPanel { copy(draft = event.draft, nag = false) }
            is TasksEvent.PickStatus -> pickStatus(event.status)
            // Picking one on a new task *makes* the choice; on a task that exists it waits for Save changes.
            is TasksEvent.PickVisibility -> editPanel {
                copy(draft = draft?.copy(visibility = event.visibility), nag = false, visPending = false, visNag = 0)
            }
            is TasksEvent.ChooseStatus -> chooseStatus(event.status)
            is TasksEvent.ChooseVisibility -> chooseVisibility(event.visibility)
            is TasksEvent.TabChanged -> editPanel { copy(tab = event.tab) }
            is TasksEvent.Save -> save(event.overwrite)
            TasksEvent.Create -> create()
            TasksEvent.Discard -> editPanel { copy(draft = orig, nag = false) }
            TasksEvent.LoadTheirs -> currentState.panel?.conflict?.let(::applyServer)
            TasksEvent.DeleteOpenTask -> currentState.panel?.task?.let(::deleteTask)
            is TasksEvent.CommentChanged -> editPanel { copy(comment = event.text) }
            TasksEvent.SendComment -> sendComment()
            is TasksEvent.PickAttachments -> attachments.pick(event.imagesOnly)
            is TasksEvent.AddAttachments -> attachments.add(event.files)
            is TasksEvent.RemoveAttachment -> attachments.remove(event.attachment)
            // One waiting in the browser, or one a saved draft keeps: that one
            // leaves the draft on Save draft or just before Create, so "Don't
            // save" keeps it.
            is TasksEvent.RemovePendingAttachment -> removeWaiting(event.key)
            is TasksEvent.OpenAttachment -> editPanel { copy(viewing = event.attachmentId) }
            TasksEvent.CloseAttachment -> editPanel { copy(viewing = null) }
            is TasksEvent.LoadAttachment -> attachments.load(event.attachment)
            is TasksEvent.ReloadAttachment -> attachments.reload(event.attachment)
            is TasksEvent.DownloadAttachment -> attachments.download(event.attachment)
            TasksEvent.OpenedUrl -> editPanel { copy(openUrl = null) }
            else -> onDraftEvent(event)
        }
    }

    /** The drafts of a new task, and the question asked before one is let go. */
    private fun onDraftEvent(event: TasksEvent) {
        when (event) {
            TasksEvent.LoadDrafts -> drafts.load()
            is TasksEvent.DraftsScopeDecided -> setState { copy(draftsScope = event.scope, draftsScopeDecided = true) }
            is TasksEvent.OpenDraft -> leave(event) { openDraft(event.draft) }
            TasksEvent.SaveDraft -> saveDraft()
            is TasksEvent.DeleteDraft -> deleteDraft(event.draft)
            TasksEvent.DeleteOpenDraft -> deleteOpenDraft()
            TasksEvent.DisarmDeleteDraft -> editPanel { copy(deleteArmed = false) }
            TasksEvent.LeaveSave -> leaveSave()
            TasksEvent.LeaveDiscard -> leaveDiscard()
            TasksEvent.LeaveStay -> setState { copy(leaveAsk = null) }
            else -> Unit
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
        started = true
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

    /**
     * The production's rights list answered again (a right was granted or
     * taken away, here or on another device). As the web's provider does, the
     * tool follows at once: it loads when it may call the service now, and
     * stops showing anything — the panel included — when it may no longer.
     */
    fun onRightsChanged() {
        if (!started) return
        val before = currentState.viewer
        val now = viewer()
        if (now == before) return
        setState { copy(viewer = now, panel = if (now.canCall) panel else null) }
        leaveDraftsIfGone()
        if (now.resolved) rightsPoll?.cancel()
        if (now.canCall && !before.canCall) loadAll()
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
                is ZillitResult.Success -> if (mine == seq) {
                    // What the deployment can keep comes with the list, and the
                    // list is held that way: a task carries no `visibility` /
                    // no `dpr` where the service cannot store one, so cards,
                    // rows and the search need no check of their own.
                    val said = answer.data.features
                    val draftsWereOff = !currentState.draftsOn
                    setState { copy(features = said) }
                    setTasks { answer.data.tasks.map { task -> applyFeatures(task, said) } }
                    // The list is what says whether this deployment keeps
                    // drafts, so mine can only be read once it has answered.
                    if (draftsWereOff) drafts.load()
                    leaveDraftsIfGone()
                }
                is ZillitResult.Failure -> if (!silent) fail(answer.error)
            }
        }
    }

    /**
     * A `tasks:*` frame. Frames for another production are dropped. As on the
     * web there are two listeners, each debouncing its own burst: the list
     * reloads for any frame, and the open panel reads its task again only for
     * frames that name it — so a frame about another task arriving right after
     * one about this task cannot swallow this one's refresh.
     */
    private fun onSocket(payload: kotlinx.serialization.json.JsonElement?) {
        if (!currentState.viewer.canCall) return
        val frame = payload as? JsonObject
        val project = (frame?.get("project_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (project != null && projectId != null && project != projectId) return
        val taskId = (frame?.get("task_id") as? JsonPrimitive)?.contentOrNull
        refreshJob?.cancel()
        refreshJob = launch {
            delay(SOCKET_DEBOUNCE_MS)
            loadList(silent = true)
        }
        val open = currentState.panel
        if (open?.task != null && taskId != null && open.task.id == taskId) {
            panelRefreshJob?.cancel()
            panelRefreshJob = launch {
                delay(SOCKET_DEBOUNCE_MS)
                currentState.panel?.takeIf { it.key == open.key }?.let { fetchTask(it.key, taskId, again = true) }
            }
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
        val kept = applyFeatures(task, currentState.features)
        setTasks { mergeTask(it, kept) }
    }

    private fun remove(taskId: String) {
        seq += 1
        setTasks { dropTask(it, taskId) }
    }

    // One toast at a time: the latest outcome replaces whatever was showing.
    private fun fail(error: ZillitError) = setState { copy(error = error.localised(), success = null) }

    private fun notice(text: String) = setState { copy(notice = text, success = null) }

    /** The server's announcement for a write it saved (a message key), when it sent one. */
    private fun announce(message: String?) {
        message?.takeIf { it.isNotBlank() }?.let { key -> setState { copy(success = key.localisedMessage(), error = null, notice = null) } }
    }

    // -- seams for the attachments collaborator ---------------------------------------
    //
    // Images and files are their own little flow (pick, prepare, three calls
    // each, the queue that waits for Create), so they live in
    // [TaskAttachmentsWork] rather than in this class — which is already the
    // list, the panel and every write. These are what it needs of this one.

    internal fun panelEdit(change: TaskPanel.() -> TaskPanel) = editPanel(change)

    /**
     * The drafts list, and whether a read has answered. [loaded] null leaves
     * that alone: dropping a row says nothing about whether a read has landed.
     */
    internal fun setDrafts(loaded: Boolean?, change: (List<SavedTaskDraft>) -> List<SavedTaskDraft>) =
        setState { copy(drafts = change(drafts), draftsLoaded = loaded ?: draftsLoaded) }

    internal fun setDraftsScope(scope: TaskScope) = setState { copy(draftsScope = scope) }

    /** Closes the panel, but only if it is still the one [panelKey] names. */
    internal fun closePanelAt(panelKey: Long) = setState { copy(panel = panel?.takeIf { it.key != panelKey }) }

    internal fun work(block: suspend () -> Unit): Job = launch { block() }

    internal fun warn(text: String) = notice(text)

    internal fun failWith(error: ZillitError) = fail(error)

    internal fun announceMessage(message: String?) = announce(message)

    /**
     * The task an attachment write answered.
     *
     * It leaves `updated` alone, so the panel keeps its unsaved edits and a
     * later save is still not a conflict: only the attachments, the history
     * and the comments are taken. The list gets the whole task, for the
     * card's 📎 count.
     */
    internal fun absorbAttachments(fresh: Task) {
        val open = currentState.panel
        if (open?.task?.id == fresh.id) {
            editPanel {
                copy(
                    task = task?.copy(
                        attachments = fresh.attachments,
                        history = fresh.history ?: task.history,
                        comments = fresh.comments ?: task.comments,
                    ),
                )
            }
        }
        if (currentState.loaded) upsert(fresh)
    }

    /** Re-reads the open task: an attachment somebody else has already removed. */
    internal fun rereadOpenTask() {
        val open = currentState.panel ?: return
        val task = open.task ?: return
        fetchTask(open.key, task.id, again = true)
    }

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
            setState { copy(moving = moving - task.id) }
            // The write may have moved other tasks, or been refused because of
            // subtasks this list had not heard of yet: either way, catch up.
            loadList(silent = true)
        }
    }

    /**
     * A card dropped on another Board column. It waits in that column until
     * the server answers, saved or refused (then the list has the answer).
     * Completing a main task with open subtasks moves nothing: [setStatus]
     * refuses it and says why.
     */
    private fun move(task: Task, status: TaskStatus) {
        val state = currentState
        val latest = state.byId[task.id] ?: task
        if (!state.canPost || status == latest.status || latest.id in state.busy) return
        if (status != TaskStatus.Done || closeBlocker(state.tasks, latest) == 0) {
            setState { copy(moving = moving + (latest.id to status)) }
        }
        setStatus(latest, status)
    }

    /**
     * Make a task public or private in one click (the ⋯ menu). Only where the
     * service keeps visibility, only for a **main** task (a subtask follows
     * its main task) and only for the people the server allows
     * (`can_change_visibility`: its creator or whoever assigned it). The
     * server checks all three again, and its answer is what is shown.
     *
     * Its subtasks follow, so the list is read again either way: saved, they
     * changed with it; refused, the list — or what the service can keep — is
     * not what this page thought.
     */
    private fun setVisibility(task: Task, visibility: TaskVisibility) {
        val state = currentState
        if (!state.canPost || !state.features.visibility) return
        if (task.isSubtask || !task.canChangeVisibility || task.id in state.busy) return
        if (visibility == visibilityOf(task)) return
        launch {
            busy(task.id, true)
            writeOutcome(task.id, repository.update(task.id, mapOf("visibility" to visibility.wire)))
            busy(task.id, false)
            loadList(silent = true)
        }
    }

    /** The Create Task popup. It never opens without posting rights, or over the panel's unsaved edits. */
    private fun openChooser(status: TaskStatus?) {
        val state = currentState
        if (!state.canPost || state.panel != null) return
        setState { copy(chooser = TaskChooser(status)) }
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
            announce(write.message)
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
            // The server says what happened either way, a 404 included.
            when (answer) {
                is ZillitResult.Success -> announce(answer.data)
                is ZillitResult.Failure -> fail(answer.error)
            }
            if (gone) {
                // Read the list as it is now, before the task and its subtasks leave it.
                val before = currentState.byId
                val mainTaskId = task.parentId?.takeIf { it in before }
                remove(task.id)
                // The service deletes the shared-task drafts under a deleted main task.
                if (!task.isSubtask) drafts.dropUnder(task.id)
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
            val draft = TaskDraft(
                title = title,
                dueDate = event.dueDate,
                assigneeId = event.assigneeId,
                isSelf = true,
                visibility = event.visibility ?: TaskVisibility.Private,
            )
            // `features` decides whether the row's Public | Private is in the
            // body at all: the row only offers it where the service keeps it.
            val write = repository.create(draft, quickId, quick = true, features = currentState.features)
            setState { copy(quickAdding = false) }
            if (write is TaskWrite.Saved) {
                upsert(write.task)
                quickId = newId()
                // Only now does the row let go of what was typed; a refused add keeps it.
                setState { copy(quickAdded = quickAdded + 1) }
                announce(write.message)
            } else if (write is TaskWrite.Refused) {
                fail(write.error)
            }
        }
    }

    private fun askForRights() {
        // The request opens over the app: the panel closes first so it is not left in front of it.
        setState { copy(panel = null) }
        rights?.ask(str(S.desktop_tasks_tool_label), RightsKind.Post)
            ?: setState { copy(error = str(S.desktop_tasks_read_only_hint)) }
    }

    // -- the panel: opening and closing -------------------------------------------------

    /**
     * A tab in the bar. Moving between views never asks about a started new
     * task: the panel stays open over the view, as the web's does.
     *
     * Drafts is only reachable where they are offered, and opening it catches
     * up with drafts saved somewhere else.
     */
    private fun viewChanged(view: TasksView) {
        if (view == TasksView.Drafts && !currentState.draftsOn) return
        setState { copy(view = view) }
        if (view == TasksView.Drafts) drafts.load()
    }

    /** Drafts switched off under the Drafts tab (a right taken away): back to the Board. */
    private fun leaveDraftsIfGone() {
        if (currentState.view == TasksView.Drafts && !currentState.draftsOn) {
            setState { copy(view = TasksView.Board) }
        }
    }

    private fun scopeChanged(event: TasksEvent.ScopeChanged) = setState {
        when (event.view) {
            TasksView.Drafts -> copy(draftsScope = event.scope, draftsScopeDecided = true)
            else -> copy(mineScope = event.scope)
        }
    }

    private fun editPanel(change: TaskPanel.() -> TaskPanel) {
        setState { copy(panel = panel?.change()) }
    }

    /** A new panel instance pointed at an existing task. */
    private fun pointedAt(taskId: String) = TaskPanel(key = ++panelKeys, creating = false, taskId = taskId)

    /**
     * Leaving this task for another replaces what the panel holds, so neither
     * unsaved edits nor a started new task is thrown away without a word:
     * edits turn the save bar red, and a started new task is asked about
     * ("Save this task as a draft?"), with [then] run once that is answered.
     */
    private fun leave(then: TasksEvent?, move: () -> Unit) {
        val open = currentState.panel
        when {
            open == null -> move()
            askToLeave(open, then) -> Unit
            open.dirty(currentState.features) -> editPanel { copy(nag = true) }
            else -> move()
        }
    }

    /**
     * Puts "Save this task as a draft?" up when a started new task would be
     * let go. Answers true when it did — nothing else may happen until it is
     * answered. Without drafts (the service, or posting rights) a new task
     * closes as it always did, so nothing is asked.
     */
    private fun askToLeave(open: TaskPanel, then: TasksEvent?): Boolean {
        val worthAsking = open.creating && open.started && !open.released
        if (!worthAsking || !currentState.draftsOn) return false
        setState { copy(leaveAsk = LeaveAsk(fromDraft = open.draftId != null, then = then)) }
        return true
    }

    /**
     * Let go of a new task's form: a new shared task goes back to its main
     * task (when it is still there, and [toMain]), anything else closes.
     * Nothing more is asked about it.
     */
    private fun finishClose(toMain: Boolean) {
        val open = currentState.panel ?: return
        editPanel { copy(released = true) }
        val main = open.draft?.parentId?.takeIf { it in currentState.byId }
        if (toMain && main != null) openTask(main) else setState { copy(panel = null, leaveAsk = null) }
    }

    /** As [finishClose], but only while the panel is still the one [panelKey] names. */
    private fun finishCloseAt(panelKey: Long, toMain: Boolean = true) {
        if (currentState.panel?.key == panelKey) finishClose(toMain)
    }

    private fun openTask(taskId: String) {
        val next = pointedAt(taskId)
        setState { copy(panel = next, leaveAsk = null) }
        fetchTask(next.key, taskId)
    }

    /**
     * The new-task form. A self task starts Private and a project task Public,
     * as the server does it; [pickAssignee] ("Assign to someone") opens it with
     * the crew picker already open.
     */
    private fun newTask(seed: TaskDraft, pickAssignee: Boolean = false) {
        val draft = seed.copy(visibility = if (seed.isSelf) TaskVisibility.Private else TaskVisibility.Public)
        // A new MAIN task has no Public / Private yet: that value is only a
        // placeholder until the creator picks one. A subtask follows its main task.
        val pending = seed.parentId == null
        setState {
            copy(
                chooser = null,
                leaveAsk = null,
                panel = TaskPanel(
                    key = ++panelKeys, creating = true, orig = draft, draft = draft,
                    uniqueId = newId(), pickAssignee = pickAssignee,
                    visPending = pending, savedSig = formSig(draft, pending),
                ),
            )
        }
    }

    /**
     * The create form for a subtask of [main], starting in the main task's
     * department — and as public or private as the main task is, which is what
     * it will follow.
     */
    private fun newSubtask(main: Task) {
        if (main.isSubtask) return
        val seed = TaskDraft(
            departmentId = main.departmentId,
            isSelf = main.isSelf,
            visibility = visibilityOf(main),
            parentId = main.id,
        )
        setState {
            copy(
                leaveAsk = null,
                panel = TaskPanel(
                    key = ++panelKeys, creating = true, orig = seed, draft = seed,
                    parentId = main.id, parentTitle = main.title, uniqueId = newId(),
                    savedSig = formSig(seed),
                ),
            )
        }
    }

    /**
     * The form of a saved draft, to finish it: the draft is read first, so its
     * images and files come with it. A shared-task draft goes under its main
     * task while the list still has it (the read decides that).
     */
    private fun openDraft(saved: SavedTaskDraft) {
        if (!currentState.draftsOn) return
        val seed = TaskDraft(isSelf = saved.isSelf, parentId = saved.parentId)
        val key = ++panelKeys
        setState {
            copy(
                chooser = null,
                leaveAsk = null,
                panel = TaskPanel(
                    key = key, creating = true, orig = seed, draft = seed,
                    parentId = saved.parentId, parentTitle = saved.parentTitle,
                    uniqueId = newId(), draftId = saved.id, draftReady = false,
                ),
            )
        }
        drafts.fetch(key, saved.id)
    }

    /** Save draft, from the panel's footer. The form closes once it is kept. */
    private fun saveDraft() {
        val open = currentState.panel ?: return
        launch { if (drafts.save(open.key)) finishCloseAt(open.key) }
    }

    /** "Save as draft" in the question: keep it, then carry on with what was asked for. */
    private fun leaveSave() {
        val ask = currentState.leaveAsk ?: return
        val open = currentState.panel ?: return
        setState { copy(leaveAsk = null) }
        launch {
            if (drafts.save(open.key)) {
                finishCloseAt(open.key)
                ask.then?.let(::onEvent)
            }
        }
    }

    /** "Discard" / "Don't save": the form goes, and a saved draft stays as it was. */
    private fun leaveDiscard() {
        val ask = currentState.leaveAsk ?: return
        val wasDraft = currentState.panel?.draftId != null
        setState { copy(leaveAsk = null) }
        finishClose(toMain = ask.then == null)
        if (wasDraft) notice(str(S.desktop_tasks_draft_not_saved))
        ask.then?.let(::onEvent)
    }

    /** 🗑 on a draft row: the first press arms it, the second deletes. */
    private fun deleteDraft(saved: SavedTaskDraft) {
        launch { drafts.delete(saved.id) }
    }

    /** Delete draft in the panel's footer: pressed twice, then the form closes. */
    private fun deleteOpenDraft() {
        val open = currentState.panel ?: return
        val draftId = open.draftId ?: return
        if (open.saving) return
        if (!open.deleteArmed) {
            editPanel { copy(deleteArmed = true) }
            return
        }
        editPanel { copy(deleteArmed = false) }
        launch { if (drafts.delete(draftId)) finishCloseAt(open.key, toMain = false) }
    }

    /** From a subtask (open, or being created) back to its main task; with none to go to, the panel closes. */
    private fun backToParent() {
        val open = currentState.panel ?: return
        // Before the task itself has loaded, the list still knows its main task.
        val target = open.parentId ?: open.draft?.parentId ?: open.task?.parentId ?: open.taskId?.let { currentState.byId[it]?.parentId }
        if (target == null) setState { copy(panel = null) } else openTask(target)
    }

    private fun closePanel() {
        val open = currentState.panel ?: return
        when {
            // The ✕ and the scrim on a new task do what Cancel does, as the web's do.
            open.creating -> cancelCreate()
            open.dirty(currentState.features) -> editPanel { copy(nag = true) }
            else -> setState { copy(panel = null) }
        }
    }

    /**
     * Close a new task (Cancel, ✕, Escape, a press outside, the main task's
     * name). With something in it, ask first; otherwise a new shared task goes
     * back to its main task (when it is still there), anything else closes.
     */
    private fun cancelCreate() {
        val open = currentState.panel ?: return
        if (askToLeave(open, then = null)) return
        finishClose(toMain = true)
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
                    if (again && open.dirty(currentState.features)) mergeComments(answer.data) else applyServer(answer.data)
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
        editPanel {
            copy(
                task = task?.copy(
                    comments = fresh.comments,
                    history = fresh.history,
                    // They save on their own, so they are never part of the
                    // unsaved edits a refresh has to protect.
                    attachments = fresh.attachments,
                ),
            )
        }
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
            // Images and files leave `updated` alone, so they are compared by id:
            // that is the only way this panel sees one somebody else added.
            val filesDiffer = attachmentIds(listed) != attachmentIds(task)
            if (listed.updatedMillis != task.updatedMillis || listed.status != task.status || filesDiffer) {
                fetchTask(open.key, task.id, again = true)
            }
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
        if (open.dirty(currentState.features) || open.conflict != null || open.saving) {
            editPanel { copy(draft = draft?.copy(status = status), nag = false) }
        } else if (status != task.status) {
            put(open, mapOf("status" to status.wire), expected = null)
        }
    }

    /**
     * "Make private" / "Make public" in the panel's ⋯ menu: saved at once,
     * like the same menu on a card — unless the panel has unsaved edits, when
     * the visibility joins them and goes with Save.
     */
    private fun chooseVisibility(visibility: TaskVisibility) {
        val open = currentState.panel ?: return
        val task = open.task ?: return
        if (!currentState.canPost || !currentState.features.visibility) return
        if (open.dirty(currentState.features) || open.conflict != null || open.saving) {
            editPanel { copy(draft = draft?.copy(visibility = visibility), nag = false) }
        } else if (visibility != visibilityOf(task)) {
            put(open, mapOf("visibility" to visibility.wire), expected = null)
        }
    }

    /**
     * Says why, and answers true, when this due date is one that cannot be
     * saved. The range can move after a date was picked (someone moves the
     * main task, a subtask is added, or the day simply ends), so save and
     * create check again before sending.
     */
    private fun dueNotice(due: String?, parentId: String?, taskId: String?): Boolean {
        val state = currentState
        val limits = dueLimits(parentId, taskId, state.byId, state.tasks, today())
        val problem = dueProblem(due, limits) ?: return false
        notice(
            when (problem) {
                DueProblem.Past -> str(S.desktop_tasks_due_past)
                DueProblem.AfterMain -> str(S.desktop_tasks_due_after_main, yearDayText(limits.max))
                DueProblem.BeforeSubtasks -> str(S.desktop_tasks_due_before_subtasks, yearDayText(limits.min))
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
        val changes = diffDraft(orig, draft, currentState.features)
        if (changes.isEmpty()) return
        val blocker = closeBlocker(currentState.tasks, task)
        if (changes["status"] == TaskStatus.Done.wire && blocker > 0) {
            notice(blockerText(blocker))
            return
        }
        // Likewise a due date out of range (the main task's date may have moved since it was picked).
        if ("due_date" in changes && dueNotice(changes["due_date"], draft.parentId, task.id)) return
        // Without a known `updated` there is nothing to compare: send none, as the web does.
        put(open, changes, expected = if (overwrite) null else task.updatedMillis.takeIf { it > 0 })
    }

    /** PUT [changes] and take the server's reply, keeping anything typed while it was in flight. */
    private fun put(open: TaskPanel, changes: Map<String, String?>, expected: Long?) {
        val task = open.task ?: return
        val sent = open.draft ?: return
        // A status change can move other tasks (a cancelled main task takes its
        // open subtasks along; a reopened subtask reopens its main task).
        val cascades = "status" in changes || "visibility" in changes
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
                    announce(answer.message)
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
        val state = currentState
        val open = state.panel ?: return
        val draft = open.draft ?: return
        if (open.saving || !state.canPost || draft.title.isBlank()) return
        // No default: the creator picks Public or Private. Each press outlines
        // the control in amber and brings it into view (the panel reads `visNag`).
        if (open.visUnchosen(state.features)) {
            editPanel { copy(visNag = visNag + 1) }
            notice(str(S.desktop_tasks_visibility_choose_notice))
            return
        }
        if (dueNotice(draft.dueDate, draft.parentId, null)) return
        val fromDraft = open.draftId
        editPanel { copy(saving = true) }
        launch {
            // Images and files taken out of the form leave the DRAFT first, or
            // the service would move them onto the new task with the rest.
            if (fromDraft != null && open.droppedFiles.isNotEmpty() &&
                !drafts.dropFilesBeforeCreate(fromDraft, open.droppedFiles)
            ) {
                editPanel { copy(saving = false) }
                return@launch
            }
            val answer = repository.create(draft, open.uniqueId, features = state.features, draftId = fromDraft)
            created(open, draft, fromDraft, answer)
        }
    }

    /** What a create answered, and where the panel goes next. */
    private suspend fun created(open: TaskPanel, draft: TaskDraft, fromDraft: String?, answer: TaskWrite) {
        when (answer) {
                is TaskWrite.Saved -> {
                    editPanel { if (key == open.key) copy(released = true) else this }
                    upsert(answer.task)
                    announce(answer.message)
                    // The service moved the draft's images and files onto the
                    // task and deleted the draft, in that same request.
                    fromDraft?.let(drafts::drop)
                    // The images and files added while creating go up now that
                    // the task exists, one after another — awaited, so the
                    // button still says "Saving…" while they do and the
                    // panel does not close over them.
                    attachments.sendQueued(answer.task.id, open.key)
                }

                is TaskWrite.Refused, is TaskWrite.Conflict -> Unit
            }
            val stillOpen = currentState.panel?.key == open.key
            if (stillOpen) editPanel { copy(saving = false) }
            when (answer) {
                is TaskWrite.Saved -> {
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
                    // The draft is gone — deleted elsewhere — and nothing was
                    // created: the form stays as a new task, without the
                    // draft's images and files.
                    if (fromDraft != null && answer.message == DRAFT_NOT_EXISTS) {
                        drafts.drop(fromDraft)
                        editPanel {
                            if (key == open.key) {
                                copy(draftId = null, draftFiles = emptyList(), droppedFiles = emptyList())
                            } else {
                                this
                            }
                        }
                    }
                    // A subtask refused because its main task is gone, or no longer a main task:
                    // the list is behind. The form stays, with what was typed.
                    if (draft.parentId != null) loadList(silent = true)
                }
                is TaskWrite.Conflict -> Unit
            }
    }

    private fun removeWaiting(key: String) {
        if (key.startsWith(DRAFT_FILE_PREFIX)) {
            val attachmentId = key.removePrefix(DRAFT_FILE_PREFIX)
            editPanel { copy(droppedFiles = droppedFiles + attachmentId) }
        } else {
            attachments.removePending(key)
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
                    if (currentState.panelDirty) mergeComments(answer.data) else applyServer(answer.data)
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
        const val DRAFT_NOT_EXISTS = "task_draft_not_exists"

        fun randomId(): String =
            "t-${currentMillis().toString(RADIX)}-${Random.nextLong().toULong().toString(RADIX)}"

        fun currentMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

        /** The viewer's own day, as `"YYYY-MM-DD"`: a date in the past is refused against this. */
        fun today(): String =
            kotlin.time.Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        const val RADIX = 36
    }
}
