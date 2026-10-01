@file:Suppress("LongMethod","CyclomaticComplexMethod","MaxLineLength") // Each view is one page laid out top to bottom.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import com.zillit.desktop.feature.tasks.domain.dropStatus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.DueBucket
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskFilter
import com.zillit.desktop.feature.tasks.domain.TaskKind
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.boardColumn
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.compareBoard
import com.zillit.desktop.feature.tasks.domain.daysUntil
import com.zillit.desktop.feature.tasks.domain.dueBucket
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.listRows
import com.zillit.desktop.feature.tasks.domain.matchesFilter
import com.zillit.desktop.feature.tasks.domain.parentTitle
import kotlinx.datetime.LocalDate

private val PAGE_PAD = 20.dp
private val NARROW = 880.dp
private val COLUMN_GAP = 16.dp

internal fun TasksUiState.actions(today: LocalDate, onEvent: (TasksEvent) -> Unit) = TaskActions(
    canPost = canPost,
    me = me,
    crewById = crewById,
    today = today,
    onOpen = { onEvent(TasksEvent.OpenTask(it.id)) },
    onStatus = { task, status -> onEvent(TasksEvent.SetStatus(task, status)) },
    onDelete = { onEvent(TasksEvent.Delete(it)) },
    blockerOf = { closeBlocker(tasks, it) },
    isBusy = { it.id in busy },
)

@Composable
private fun ViewHeader(title: String, description: String, state: TasksUiState, onEvent: (TasksEvent) -> Unit, addLabel: String?, onAdd: (() -> Unit)?) {
    ZillitPageHeader(
        title = title,
        description = description,
        modifier = Modifier.padding(horizontal = PAGE_PAD, vertical = ZillitTheme.spacing.md),
        actions = {
            if (state.canPost) {
                if (addLabel != null && onAdd != null) ZillitButton(addLabel, onClick = onAdd, leadingIcon = ZillitIcons.Add)
            } else {
                ZillitButton(str(S.desktop_request_access), onClick = { onEvent(TasksEvent.AskForRights) }, variant = ButtonVariant.Secondary)
            }
        },
    )
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxWidth().padding(PAGE_PAD), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun NoMatch(query: String, onClear: () -> Unit) {
    ZillitEmptyState(
        title = str(S.desktop_tasks_no_match, query.trim()),
        icon = ZillitIcons.Search,
        action = { ZillitButton(str(S.ah_cd_clear_search), onClick = onClear, variant = ButtonVariant.Secondary, size = ButtonSize.Small) },
    )
}

/** A card being carried: where the pointer is, in window coordinates, and where it grabbed the card. */
private class DragState {
    var id by mutableStateOf<String?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var grab = Offset.Zero
    var size = IntSize.Zero
    var origin = Offset.Zero
    val columns: SnapshotStateMap<TaskStatus, Rect> = mutableStateMapOf()

    /**
     * The column the pointer is over — by its horizontal span only: an empty
     * column is only as tall as its heading, and a card dropped low in the
     * gap under it was meant for it all the same.
     */
    fun overColumn(): TaskStatus? =
        columns.entries.firstOrNull { pointer.x >= it.value.left && pointer.x <= it.value.right }?.key
}

/**
 * The Board: every MAIN task the user can see (project tasks plus their own or
 * shared self tasks) in To do / Progress/Update / Done. Subtasks live inside
 * their main task, whose card counts them. Cancelled tasks sit at the bottom of
 * Done, greyed out and struck through. A card moves between columns from its ⋯
 * menu; a main task with open subtasks cannot go to Done, and says why.
 */
@Composable
internal fun BoardView(state: TasksUiState, today: LocalDate, onEvent: (TasksEvent) -> Unit) {
    val actions = state.actions(today, onEvent)
    val filter = state.boardFilter
    val drag = remember { DragState() }
    // A dropped card waits in its new column while the write is in flight, and comes back if it is refused.
    val moving = remember { mutableStateMapOf<String, TaskStatus>() }
    LaunchedEffect(moving.keys.toList()) {
        if (moving.isNotEmpty()) {
            delay(MOVE_WAIT_MS)
            moving.clear()
        }
    }
    val visible = state.mainTasks.filter { matchesFilter(it, filter, state.lookup) }
        .map { task -> moving[task.id]?.let { task.copy(status = it) } ?: task }
        .sortedWith(compareBoard)
    val shown = visible.size
    val drop = { task: Task ->
        val target = dropStatus(task, drag.overColumn())
        if (target != null) {
            if (target != TaskStatus.Done || actions.blockerOf(task) == 0) moving[task.id] = target
            onEvent(TasksEvent.SetStatus(state.byId[task.id] ?: task, target))
        }
    }
    val open = state.mainTasks.filter { it.status.isOpen }
    val dueToday = open.count { daysUntil(it.dueDate, today) == 0 }
    val late = open.count { (daysUntil(it.dueDate, today) ?: 0) < 0 }
    val set = { next: TaskFilter -> onEvent(TasksEvent.BoardFilterChanged(next)) }

    Box(Modifier.fillMaxSize().onGloballyPositioned { drag.origin = it.positionInRoot() }) {
    Column(Modifier.fillMaxSize()) {
        ViewHeader(
            title = str(S.desktop_tasks_nav_board),
            description = str(S.desktop_tasks_top_board_sub),
            state = state,
            onEvent = onEvent,
            addLabel = str(S.desktop_tasks_add_task),
            onAdd = { onEvent(TasksEvent.NewTask()) },
        )
        ZillitScrollColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(PAGE_PAD), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (state.loading && !state.loaded) {
                Loading()
                return@ZillitScrollColumn
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                if (dueToday > 0) ZillitStatusPill(str(S.desktop_tasks_due_today_count, dueToday), tone = StatusTone.Pending)
                if (late > 0) ZillitStatusPill(str(S.desktop_tasks_overdue_count, late), tone = StatusTone.Rejected)
                ZillitStatusPill(str(S.desktop_tasks_open_count, open.size))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitSegmented(
                    options = listOf(
                        ZillitTab("all", str(S.desktop_tasks_filter_all)),
                        ZillitTab("project", str(S.desktop_tasks_filter_project)),
                        ZillitTab("self", str(S.desktop_tasks_nav_self)),
                    ),
                    activeId = filter.kind.name.lowercase(),
                    onSelect = { id -> set(filter.copy(kind = TaskKind.entries.first { it.name.lowercase() == id })) },
                )
                ChoicePicker(
                    value = filter.department.ifEmpty { null },
                    choices = state.departments.map { Choice(it.id, it.name) },
                    emptyLabel = str(S.desktop_inv_all_departments),
                    onChange = { set(filter.copy(department = it.orEmpty())) },
                    modifier = Modifier.width(PICKER),
                )
                ChoicePicker(
                    value = filter.assignee.ifEmpty { null },
                    choices = listOf(Choice(TaskFilter.NO_ASSIGNEE, str(S.desktop_tasks_filter_unassigned_only))) + crewChoices(state.crew, state.hodIds),
                    emptyLabel = str(S.desktop_tasks_filter_assignee_all),
                    onChange = { set(filter.copy(assignee = it.orEmpty())) },
                    modifier = Modifier.width(PICKER),
                )
                ZillitSearchField(
                    value = filter.query,
                    onValueChange = { set(filter.copy(query = it)) },
                    placeholder = str(S.desktop_tasks_search_placeholder),
                    modifier = Modifier.weight(1f),
                )
            }
            if (filter.query.isNotBlank() && shown == 0) {
                NoMatch(filter.query, onClear = { set(filter.copy(query = "")) })
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP), verticalAlignment = Alignment.Top) {
                    TaskStatus.board.forEach { status ->
                        val cards = visible.filter { boardColumn(it) == status }
                        BoardColumn(status, cards, state, actions, onEvent, drag, drop, Modifier.weight(1f))
                    }
                }
            }
        }
    }
    val carried = drag.id?.let { id -> visible.firstOrNull { it.id == id } }
    if (carried != null) {
        val density = LocalDensity.current
        val at = drag.pointer - drag.grab - drag.origin
        Box(Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }.width(with(density) { drag.size.width.toDp() }).alpha(CARRIED_ALPHA)) {
            TaskCard(carried, state.subtasksOf(carried.id), state.departmentName(carried.departmentId), actions)
        }
    }
    }
}

@Composable
private fun BoardColumn(
    status: TaskStatus,
    cards: List<Task>,
    state: TasksUiState,
    actions: TaskActions,
    onEvent: (TasksEvent) -> Unit,
    drag: DragState,
    onDrop: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val over = drag.id != null && drag.overColumn() == status
    Column(
        modifier
            .onGloballyPositioned { drag.columns[status] = it.boundsInRoot() }
            .clip(ZillitTheme.shapes.large)
            .background(if (over) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Box(Modifier.size(COLUMN_DOT).clip(CircleShape).background(statusColour(status)))
            ZillitText(statusText(status), style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
            ZillitText(cards.size.toString(), style = ZillitTheme.typography.label, color = colors.textMuted, modifier = Modifier.weight(1f))
            if (state.canPost) {
                com.zillit.desktop.core.designsystem.component.ZillitIconButton(
                    icon = ZillitIcons.Add,
                    contentDescription = "${str(S.desktop_tasks_add_task)}: ${statusText(status)}",
                    onClick = { onEvent(TasksEvent.NewTask(TaskDraft(status = status))) },
                )
            }
        }
        if (cards.isEmpty()) {
            ZillitText(
                text = str(if (state.canPost) S.desktop_nothing_here_yet else S.desktop_nothing_here),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(ZillitTheme.spacing.md),
            )
        }
        cards.forEach { task ->
            DraggableCard(task, drag, enabled = state.canPost, onDrop = onDrop) { modifier ->
                TaskCard(task, state.subtasksOf(task.id), state.departmentName(task.departmentId), actions, modifier)
            }
        }
    }
}

/**
 * A card that can be picked up. A press that moves less than the system's drag
 * slop is still a click, so opening the card keeps working.
 */
@Composable
private fun DraggableCard(
    task: Task,
    drag: DragState,
    enabled: Boolean,
    onDrop: (Task) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val carried = drag.id == task.id
    val gesture = if (!enabled) Modifier else Modifier.pointerInput(task.id) {
        detectDragGestures(
            onDragStart = { start ->
                drag.id = task.id
                drag.grab = start
                drag.size = size
                drag.pointer = topLeft + start
            },
            onDragEnd = {
                onDrop(task)
                drag.id = null
            },
            onDragCancel = { drag.id = null },
        ) { change, amount ->
            change.consume()
            drag.pointer += amount
        }
    }
    content(
        Modifier
            .onGloballyPositioned {
                topLeft = it.positionInRoot()
                size = it.size
            }
            .alpha(if (carried) GHOST_ALPHA else 1f)
            .then(gesture),
    )
}

/**
 * Everything assigned to me — main tasks AND subtasks — grouped by when it is
 * due, with the closed ones folded away under "Closed". A subtask carries a
 * "Subtask of …" line. The search looks through closed tasks too.
 */
@Composable
internal fun MineView(state: TasksUiState, today: LocalDate, onEvent: (TasksEvent) -> Unit) {
    val actions = state.actions(today, onEvent)
    val query = state.mineQuery
    val rows = remember(state.tasks, state.me, query, state.crew, state.departments) {
        listRows(state.tasks.filter { it.assigneeId == state.me }, query, state.lookup)
    }
    val searching = query.isNotBlank()
    val first = state.crewById[state.me]?.fullName?.substringBefore(' ')
    Column(Modifier.fillMaxSize()) {
        ViewHeader(
            title = str(S.desktop_tasks_nav_mine),
            description = if (first != null) str(S.desktop_tasks_top_mine_sub, first) else str(S.desktop_tasks_nav_mine_sub),
            state = state,
            onEvent = onEvent,
            addLabel = str(S.desktop_tasks_add_task),
            onAdd = { onEvent(TasksEvent.NewTask(TaskDraft(assigneeId = state.me))) },
        )
        ZillitScrollColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(PAGE_PAD), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (state.loading && !state.loaded) {
                Loading()
                return@ZillitScrollColumn
            }
            Column(Modifier.widthIn(max = NARROW), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitSearchField(query, { onEvent(TasksEvent.MineQueryChanged(it)) }, Modifier.fillMaxWidth(), str(S.desktop_tasks_search_mine_placeholder))
                DueBucket.entries.forEach { bucket ->
                    val group = rows.open.filter { dueBucket(it.dueDate, today) == bucket }
                    if (group.isEmpty()) return@forEach
                    GroupBox(
                        title = bucketTitle(bucket),
                        sub = if (bucket == DueBucket.Week) str(S.desktop_tasks_mine_next7) else null,
                        count = group.size,
                        late = bucket == DueBucket.Overdue,
                    ) {
                        group.forEach { task -> MineRow(task, state, actions) }
                    }
                }
                if (searching && rows.open.isEmpty() && rows.closed.isEmpty()) NoMatch(query, onClear = { onEvent(TasksEvent.MineQueryChanged("")) })
                if (!searching && rows.open.isEmpty()) {
                    ZillitText(str(S.desktop_tasks_mine_empty), style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
                }
                ClosedGroup(rows.closed.size, forced = searching) { rows.closed.forEach { MineRow(it, state, actions) } }
            }
        }
    }
}

@Composable
private fun MineRow(task: Task, state: TasksUiState, actions: TaskActions) {
    TaskRow(
        task = task,
        actions = actions,
        parentTitle = parentTitle(task, state.byId),
        tags = {
            if (task.isSelf) {
                SelfTag(task, state.me, state.crewById)
            } else if (task.departmentId != null) {
                DeptTag(task.departmentId, state.departmentName(task.departmentId))
            }
            if (task.scenes.isNotBlank()) ZillitText(task.scenes, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textSecondary, maxLines = 1)
            SubtaskCount(state.subtasksOf(task.id))
            AssignLine(task, state.me, state.crewById)
        },
        right = {
            DueChip(task, actions.today)
            PriorityChip(task.priority)
            StatusChip(task.status)
        },
    )
}

/**
 * Self tasks: my private to-dos — main tasks only; their subtasks live inside
 * them. A self task stays hidden until I assign it to someone; then it shows in
 * their My Tasks and on their Board, and only I can change who it is assigned
 * to. Changing the person on a row shows Save / Cancel on that row.
 */
@Composable
internal fun SelfView(state: TasksUiState, today: LocalDate, onEvent: (TasksEvent) -> Unit) {
    val actions = state.actions(today, onEvent)
    val query = state.selfQuery
    val rows = remember(state.tasks, state.me, query, state.crew, state.departments) {
        listRows(state.mainTasks.filter { it.isSelf && it.createdBy == state.me }, query, state.lookup)
    }
    val private = rows.open.filter(::isPrivate)
    val shared = rows.open.filterNot(::isPrivate)
    val searching = query.isNotBlank()
    // The person each row is being moved to, before Save ("" = only me).
    val pending = remember { mutableStateMapOf<String, String>() }
    var title by remember { mutableStateOf("") }
    var due by remember(today) { mutableStateOf<String?>(today.toString()) }
    var who by remember { mutableStateOf<String?>(null) }
    val sharable = state.assignableCrew

    Column(Modifier.fillMaxSize()) {
        ViewHeader(
            title = str(S.desktop_tasks_nav_self),
            description = str(S.desktop_tasks_top_self_sub),
            state = state,
            onEvent = onEvent,
            addLabel = null,
            onAdd = null,
        )
        ZillitScrollColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(PAGE_PAD), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (state.loading && !state.loaded) {
                Loading()
                return@ZillitScrollColumn
            }
            Column(Modifier.widthIn(max = NARROW), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                if (state.canPost) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        ZillitTextField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = str(S.desktop_tasks_self_add_placeholder),
                            maxLength = TITLE_MAX,
                            modifier = Modifier.weight(1f),
                            onImeAction = {
                                onEvent(TasksEvent.QuickAdd(title, due, who))
                                if (title.isNotBlank()) title = ""
                            },
                        )
                        DueField(value = due, onChange = { due = it }, modifier = Modifier.width(DATE_WIDTH))
                        ChoicePicker(
                            value = who,
                            choices = crewChoices(sharable, state.hodIds, exclude = state.me),
                            emptyLabel = str(S.desktop_tasks_self_just_me),
                            onChange = { who = it },
                            modifier = Modifier.width(PICKER),
                        )
                        ZillitButton(
                            text = str(if (state.quickAdding) S.desktop_tasks_adding else S.desktop_tasks_self_add),
                            onClick = {
                                onEvent(TasksEvent.QuickAdd(title, due, who))
                                if (title.isNotBlank()) title = ""
                            },
                            enabled = title.isNotBlank() && !state.quickAdding,
                        )
                    }
                } else {
                    ZillitText(str(S.desktop_tasks_read_only_hint), style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
                }
                ZillitText(str(S.desktop_tasks_self_note), style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
                ZillitSearchField(query, { onEvent(TasksEvent.SelfQueryChanged(it)) }, Modifier.fillMaxWidth(), str(S.desktop_tasks_search_self_placeholder))
                DueBucket.entries.forEach { bucket ->
                    val group = private.filter { dueBucket(it.dueDate, today) == bucket }
                    if (group.isEmpty()) return@forEach
                    GroupBox(
                        title = bucketTitle(bucket),
                        sub = if (bucket == DueBucket.Week) str(S.desktop_tasks_mine_next7) else null,
                        count = group.size,
                        late = bucket == DueBucket.Overdue,
                    ) {
                        group.forEach { SelfRow(it, state, actions, sharable, pending, onEvent) }
                    }
                }
                if (searching && rows.open.isEmpty() && rows.closed.isEmpty()) NoMatch(query, onClear = { onEvent(TasksEvent.SelfQueryChanged("")) })
                if (!searching && private.isEmpty()) {
                    ZillitText(str(S.desktop_tasks_self_empty), style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
                }
                if (shared.isNotEmpty()) {
                    GroupBox(title = str(S.desktop_tasks_self_assigned_by_you), sub = str(S.desktop_tasks_self_assigned_by_you_sub), count = shared.size) {
                        shared.forEach { SelfRow(it, state, actions, sharable, pending, onEvent) }
                    }
                }
                ClosedGroup(rows.closed.size, forced = searching) { rows.closed.forEach { SelfRow(it, state, actions, sharable, pending, onEvent) } }
            }
        }
    }
}

@Composable
private fun SelfRow(
    task: Task,
    state: TasksUiState,
    actions: TaskActions,
    sharable: List<com.zillit.desktop.feature.tasks.domain.TaskPerson>,
    pending: MutableMap<String, String>,
    onEvent: (TasksEvent) -> Unit,
) {
    val private = isPrivate(task)
    val current = if (private) "" else task.assigneeId.orEmpty()
    val editing = task.id in pending
    val value = if (editing) pending.getValue(task.id) else current
    val subtasks = state.subtasksOf(task.id)
    val busy = task.id in state.busy
    TaskRow(
        task = task,
        actions = actions,
        tags = {
            if (task.description.isNotBlank()) {
                ZillitText(task.description, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted, maxLines = 1)
            }
            SubtaskCount(subtasks)
        },
        right = {
            DueChip(task, actions.today)
            if (!editing && private) {
                ZillitText(str(S.desktop_tasks_self_only_you), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
            }
            if (!editing && !private) {
                Column {
                    PersonName(state.crewById[task.assigneeId], state.me)
                    ZillitText(str(S.desktop_tasks_self_now_in_theirs), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
                }
            }
            if (state.canPost) {
                ChoicePicker(
                    value = value.ifEmpty { null },
                    choices = crewChoices(sharable, state.hodIds, exclude = state.me),
                    emptyLabel = str(if (private) S.desktop_assign_to else S.desktop_tasks_self_only_me_private),
                    onChange = { picked ->
                        if ((picked ?: "") == current) pending.remove(task.id) else pending[task.id] = picked.orEmpty()
                    },
                    modifier = Modifier.width(PICKER),
                    enabled = !busy,
                )
            }
            if (editing) {
                ZillitButton(str(S.cancel), onClick = { pending.remove(task.id) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                ZillitButton(
                    text = str(S.save),
                    onClick = {
                        onEvent(TasksEvent.Share(task.id, pending[task.id]?.ifEmpty { null }))
                        pending.remove(task.id)
                    },
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
        },
    )
}

private val PICKER = 200.dp
private val DATE_WIDTH = 170.dp
private val COLUMN_DOT = 10.dp
private const val SKELETON_ROWS = 6
private const val MOVE_WAIT_MS = 4000L
private const val GHOST_ALPHA = 0.35f
private const val CARRIED_ALPHA = 0.92f
private const val TITLE_MAX = 300
