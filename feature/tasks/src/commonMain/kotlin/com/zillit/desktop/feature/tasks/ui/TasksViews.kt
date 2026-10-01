@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MaxLineLength") // Each view is one page laid out top to bottom, to the web's Tasks.css.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.DueBucket
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskFilter
import com.zillit.desktop.feature.tasks.domain.TaskKind
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.boardColumn
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.compareBoard
import com.zillit.desktop.feature.tasks.domain.daysUntil
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.dropStatus
import com.zillit.desktop.feature.tasks.domain.dueBucket
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.listRows
import com.zillit.desktop.feature.tasks.domain.matchesFilter
import com.zillit.desktop.feature.tasks.domain.parentTitle
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate

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

/** The page heading (`.zt-top`): the view's name and what it shows, with its main action at the right. */
@Composable
private fun TopBar(title: String, sub: String, state: TasksUiState, onEvent: (TasksEvent) -> Unit, addLabel: String?, onAdd: (() -> Unit)?) {
    val k = TasksTheme.c
    Column(Modifier.fillMaxWidth().background(k.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TText(title, 24, FontWeight.Bold, maxLines = 1)
            TText(sub, 13, color = k.muted, maxLines = 1, modifier = Modifier.weight(1f))
            if (state.canPost) {
                if (addLabel != null && onAdd != null) TBtn(addLabel, onAdd, icon = ZillitIcons.Add)
            } else {
                TBtn(str(S.desktop_request_access), { onEvent(TasksEvent.AskForRights) }, kind = BtnKind.Ghost)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
    }
}

private val PageScroll = PaddingValues(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 40.dp)

@Composable
private fun Page(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ZillitScrollColumn(Modifier.fillMaxSize(), contentPadding = PageScroll, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun Loading() {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        com.zillit.desktop.core.designsystem.component.ZillitSpinner(size = 16.dp)
        TText(str(S.desktop_tasks_loading), 14, color = TasksTheme.c.muted)
    }
}

@Composable
private fun NoMatch(query: String, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TText(str(S.desktop_tasks_no_match, query.trim()), 14, color = TasksTheme.c.fg)
        TText(str(S.ah_cd_clear_search), 13, SemiBold, TasksTheme.c.accent, Modifier.clickable(onClick = onClear).padding(4.dp))
    }
}

/** A dashed rounded edge, for an empty column and a private card. */
private fun Modifier.dashed(color: Color, radius: Float = 10f): Modifier = drawBehind {
    drawRoundRect(
        color = color, cornerRadius = CornerRadius(radius.dp.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

private fun dateHeading(today: LocalDate) =
    "${fullDayText(today).substringBefore(',')}, ${dayText(today, today)}"

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
 * Done, greyed out and struck through. A card moves between columns by being
 * dragged, or from its ⋯ menu; a main task with open subtasks cannot go to Done.
 */
@Composable
internal fun BoardView(state: TasksUiState, today: LocalDate, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
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
    val open = state.mainTasks.filter { it.status.isOpen }
    val dueToday = open.count { daysUntil(it.dueDate, today) == 0 }
    val late = open.count { (daysUntil(it.dueDate, today) ?: 0) < 0 }
    val set = { next: TaskFilter -> onEvent(TasksEvent.BoardFilterChanged(next)) }
    val drop = { task: Task ->
        val target = dropStatus(task, drag.overColumn())
        if (target != null) {
            if (target != TaskStatus.Done || actions.blockerOf(task) == 0) moving[task.id] = target
            onEvent(TasksEvent.SetStatus(state.byId[task.id] ?: task, target))
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { drag.origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            TopBar(str(S.desktop_tasks_nav_board), str(S.desktop_tasks_board_top), state, onEvent, str(S.desktop_tasks_add_task)) { onEvent(TasksEvent.NewTask()) }
            Box(Modifier.weight(1f)) {
                Page {
                    if (state.loading && !state.loaded) {
                        Loading()
                        return@Page
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TText(dateHeading(today), 14, FontWeight.Bold)
                        if (dueToday > 0) Pill(str(S.desktop_tasks_due_today_count, dueToday), k.warn, k.warnBg)
                        if (late > 0) Pill(str(S.desktop_tasks_overdue_count, late), k.late, k.lateBg)
                        Pill(str(S.desktop_tasks_open_count, open.size), k.chipFg, k.chip)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.clip(RoundedCornerShape(9.dp)).background(k.chip).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf(TaskKind.All to str(S.desktop_tasks_filter_all), TaskKind.Project to str(S.desktop_tasks_filter_project), TaskKind.Self to str(S.desktop_tasks_nav_self)).forEach { (kind, label) ->
                                val on = filter.kind == kind
                                TText(
                                    label, 13, if (on) SemiBold else FontWeight.Normal, if (on) k.fg else k.muted,
                                    Modifier.height(30.dp).clip(RoundedCornerShape(7.dp)).background(if (on) k.surface else Color.Transparent)
                                        .clickable { set(filter.copy(kind = kind)) }.padding(horizontal = 12.dp, vertical = 5.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                        TPicker(
                            value = filter.department.ifEmpty { null },
                            picks = state.departments.map { Pick(it.id, it.name, colour = Color(deptColour(it.id))) },
                            emptyLabel = str(S.desktop_inv_all_departments), onChange = { set(filter.copy(department = it.orEmpty())) },
                            compact = true, searchHint = str(S.desktop_tasks_pick_dept),
                        )
                        TPicker(
                            value = filter.assignee.ifEmpty { null },
                            picks = listOf(Pick(TaskFilter.NO_ASSIGNEE, str(S.desktop_tasks_filter_unassigned_only))) + crewPicks(state.crew, state.hodIds),
                            emptyLabel = str(S.desktop_tasks_all_assignees), onChange = { set(filter.copy(assignee = it.orEmpty())) }, compact = true,
                        )
                        TSearch(filter.query, { set(filter.copy(query = it)) }, str(S.desktop_tasks_search_ph), Modifier.widthIn(min = 220.dp, max = 320.dp).weight(1f, fill = false))
                    }
                    if (filter.query.isNotBlank() && visible.isEmpty()) {
                        NoMatch(filter.query, onClear = { set(filter.copy(query = "")) })
                    } else {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            TaskStatus.board.forEach { status ->
                                BoardColumn(status, visible.filter { boardColumn(it) == status }, state, actions, onEvent, drag, drop, Modifier.weight(1f).fillMaxHeight())
                            }
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
    val k = TasksTheme.c
    val look = statusLook(status)
    val over = drag.id != null && drag.overColumn() == status
    Column(modifier.onGloballyPositioned { drag.columns[status] = it.boundsInRoot() }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(look.bg).padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dot(look.dot, 10.dp)
            TText(statusText(status), 14, SemiBold, look.fg)
            TText(cards.size.toString(), 13, SemiBold, look.fg.copy(alpha = 0.8f), Modifier.weight(1f))
            if (state.canPost) TIconBtn(ZillitIcons.Add, "${str(S.desktop_tasks_add_task)}: ${statusText(status)}", { onEvent(TasksEvent.NewTask(TaskDraft(status = status))) }, tint = look.fg)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp).clip(RoundedCornerShape(12.dp)).background(if (over) look.bg else Color.Transparent).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (cards.isEmpty()) {
                TText(
                    str(if (state.canPost) S.desktop_tasks_col_empty else S.desktop_tasks_col_empty_ro), 13, color = k.faint,
                    modifier = Modifier.fillMaxWidth().dashed(k.line2).padding(18.dp), maxLines = 2,
                )
            }
            cards.forEach { task ->
                DraggableCard(task, drag, enabled = state.canPost, onDrop = onDrop) { modifier ->
                    TaskCard(task, state.subtasksOf(task.id), state.departmentName(task.departmentId), actions, modifier)
                }
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
    val k = TasksTheme.c
    val actions = state.actions(today, onEvent)
    val query = state.mineQuery
    val rows = remember(state.tasks, state.me, query, state.crew, state.departments) {
        listRows(state.tasks.filter { it.assigneeId == state.me }, query, state.lookup)
    }
    val searching = query.isNotBlank()
    val first = state.crewById[state.me]?.fullName?.substringBefore(' ')
    Column(Modifier.fillMaxSize()) {
        TopBar(
            str(S.desktop_tasks_nav_mine), if (first != null) str(S.desktop_tasks_top_mine_sub, first) else str(S.desktop_tasks_nav_mine_sub), state, onEvent,
            str(S.desktop_tasks_add_task),
        ) { onEvent(TasksEvent.NewTask(TaskDraft(assigneeId = state.me))) }
        Box(Modifier.weight(1f)) {
            Page {
                if (state.loading && !state.loaded) {
                    Loading()
                    return@Page
                }
                Column(Modifier.widthIn(max = NARROW), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TSearch(query, { onEvent(TasksEvent.MineQueryChanged(it)) }, str(S.desktop_tasks_search_mine_placeholder), Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    DueBucket.entries.forEach { bucket ->
                        val group = rows.open.filter { dueBucket(it.dueDate, today) == bucket }
                        if (group.isEmpty()) return@forEach
                        GroupBox(
                            title = bucketTitle(bucket),
                            sub = when (bucket) {
                                DueBucket.Today -> dateHeading(today)
                                DueBucket.Week -> str(S.desktop_tasks_mine_next7)
                                else -> null
                            },
                            count = group.size, late = bucket == DueBucket.Overdue,
                        ) { group.forEach { task -> MineRow(task, state, actions) } }
                    }
                    if (searching && rows.open.isEmpty() && rows.closed.isEmpty()) NoMatch(query, onClear = { onEvent(TasksEvent.MineQueryChanged("")) })
                    if (!searching && rows.open.isEmpty()) TText(str(S.desktop_tasks_mine_empty), 14, color = k.faint)
                    ClosedGroup(rows.closed.size, forced = searching) { rows.closed.forEach { MineRow(it, state, actions) } }
                }
            }
        }
    }
}

@Composable
private fun MineRow(task: Task, state: TasksUiState, actions: TaskActions) {
    TaskRow(
        task = task, actions = actions, parentTitle = parentTitle(task, state.byId),
        tags = {
            if (task.isSelf) {
                SelfTag(task, state.me, state.crewById)
            } else if (task.departmentId != null) {
                DeptTag(task.departmentId, state.departmentName(task.departmentId))
            }
            SceneChip(task.scenes)
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
    val k = TasksTheme.c
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
    val add = {
        onEvent(TasksEvent.QuickAdd(title, due, who))
        if (title.isNotBlank()) title = ""
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(str(S.desktop_tasks_nav_self), str(S.desktop_tasks_top_self_sub), state, onEvent, null, null)
        Box(Modifier.weight(1f)) {
            Page {
                if (state.loading && !state.loaded) {
                    Loading()
                    return@Page
                }
                Column(Modifier.widthIn(max = NARROW), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.canPost) {
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.fillMaxWidth().clip(shape).background(k.surface).border(BorderStroke(1.dp, k.line2), shape).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TInput(title, { title = it }, Modifier.weight(1f).padding(horizontal = 6.dp), placeholder = str(S.desktop_tasks_self_add_placeholder), style = txt(15), maxLength = TITLE_MAX, onEnter = add)
                            TDate(
                                due, due?.let { runCatching { dayText(LocalDate.parse(it), today) }.getOrNull() } ?: str(S.ah_template_no_date),
                                { due = it }, today, compact = true,
                            )
                            TPicker(who, crewPicks(sharable, state.hodIds, exclude = state.me), str(S.desktop_tasks_self_just_me), { who = it }, compact = true, avatars = true)
                            TBtn(str(if (state.quickAdding) S.desktop_tasks_adding else S.desktop_tasks_self_add), add, enabled = title.isNotBlank() && !state.quickAdding)
                        }
                    } else {
                        TText(str(S.desktop_tasks_read_only_hint), 14, color = k.muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ZillitIcon(ZillitIcons.Lock, tint = k.muted, size = 12.dp)
                        TText(str(S.desktop_tasks_self_note), 13, color = k.muted)
                    }
                    TSearch(query, { onEvent(TasksEvent.SelfQueryChanged(it)) }, str(S.desktop_tasks_search_self_placeholder), Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    DueBucket.entries.forEach { bucket ->
                        val group = private.filter { dueBucket(it.dueDate, today) == bucket }
                        if (group.isEmpty()) return@forEach
                        GroupBox(
                            title = bucketTitle(bucket), sub = if (bucket == DueBucket.Week) str(S.desktop_tasks_mine_next7) else null,
                            count = group.size, late = bucket == DueBucket.Overdue,
                        ) { group.forEach { SelfRow(it, state, actions, sharable, pending, onEvent) } }
                    }
                    if (searching && rows.open.isEmpty() && rows.closed.isEmpty()) NoMatch(query, onClear = { onEvent(TasksEvent.SelfQueryChanged("")) })
                    if (!searching && private.isEmpty()) TText(str(S.desktop_tasks_self_empty), 14, color = k.faint)
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
}

@Composable
private fun SelfRow(
    task: Task,
    state: TasksUiState,
    actions: TaskActions,
    sharable: List<TaskPerson>,
    pending: MutableMap<String, String>,
    onEvent: (TasksEvent) -> Unit,
) {
    val k = TasksTheme.c
    val private = isPrivate(task)
    val current = if (private) "" else task.assigneeId.orEmpty()
    val editing = task.id in pending
    val value = if (editing) pending.getValue(task.id) else current
    val busy = task.id in state.busy
    TaskRow(
        task = task, actions = actions, editing = editing,
        tags = {
            if (task.description.isNotBlank()) TText(task.description, 12, color = k.muted, maxLines = 2)
            SubtaskCount(state.subtasksOf(task.id))
        },
        right = {
            DueChip(task, actions.today)
            if (!editing && private) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ZillitIcon(ZillitIcons.Lock, tint = k.faint, size = 12.dp)
                    TText(str(S.desktop_tasks_self_only_you), 12, color = k.faint)
                }
            }
            if (!editing && !private) {
                Column(horizontalAlignment = Alignment.End) {
                    PersonName(state.crewById[task.assigneeId], state.me)
                    TText(str(S.desktop_tasks_self_now_in_theirs), 12, color = k.ok)
                }
            }
            if (state.canPost) {
                TPicker(
                    value = value.ifEmpty { null }, picks = crewPicks(sharable, state.hodIds, exclude = state.me),
                    emptyLabel = str(if (private) S.desktop_assign_to else S.desktop_tasks_self_only_me_private),
                    onChange = { picked -> if ((picked ?: "") == current) pending.remove(task.id) else pending[task.id] = picked.orEmpty() },
                    compact = true, enabled = !busy,
                )
            }
            if (editing) {
                TBtn(str(S.cancel), { pending.remove(task.id) }, kind = BtnKind.Ghost, small = true)
                TBtn(str(S.save), {
                    onEvent(TasksEvent.Share(task.id, pending[task.id]?.ifEmpty { null }))
                    pending.remove(task.id)
                }, small = true, enabled = !busy)
            }
        },
    )
}

private val NARROW = 980.dp
private const val MOVE_WAIT_MS = 4000L
private const val GHOST_ALPHA = 0.35f
private const val CARRIED_ALPHA = 0.92f
private const val TITLE_MAX = 300
