@file:Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "MaxLineLength") // One panel, laid out top to bottom to the web's drawer.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.HISTORY_PREVIEW
import com.zillit.desktop.feature.tasks.domain.HistoryLine
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.applyMention
import com.zillit.desktop.feature.tasks.domain.assignerOf
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.daysUntil
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.dueLimits
import com.zillit.desktop.feature.tasks.domain.dueTone
import com.zillit.desktop.feature.tasks.domain.DueTone
import com.zillit.desktop.feature.tasks.domain.historyLines
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.mentionAt
import com.zillit.desktop.feature.tasks.domain.mentionMatches
import com.zillit.desktop.feature.tasks.domain.subtaskStats
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The task panel, sliding in from the right over a scrim (`.zt-drawer`). One
 * panel shows a main task, a subtask (a task of its own), or the form for a new
 * one. Nothing is saved until Save changes (or Ctrl/Cmd+S); closing, or moving
 * to another task, with unsaved changes keeps the panel open and turns the bar
 * red. If someone else saved first the server answers 409 and the user loads
 * theirs or saves mine anyway. Comments send straight away.
 */
@Composable
internal fun TaskPanelOverlay(
    state: TasksUiState,
    today: LocalDate,
    onEvent: (TasksEvent) -> Unit,
    mentionable: (Task) -> List<TaskPerson>,
    modifier: Modifier = Modifier,
) {
    val panel = state.panel ?: return
    val k = TasksTheme.c
    val close = { onEvent(if (panel.creating) TasksEvent.CancelCreate else TasksEvent.ClosePanel) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(panel.key) { runCatching { focus.requestFocus() } }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(k.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close))
        Column(
            Modifier.align(Alignment.CenterEnd).widthIn(max = PANEL_WIDTH).fillMaxWidth(0.9f).fillMaxHeight()
                .background(k.surface).border(BorderStroke(1.dp, k.line))
                .focusRequester(focus).focusable()
                // Ctrl/Cmd+S saves (or creates); Escape closes, or nags about unsaved changes.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.S -> {
                            onEvent(if (panel.creating) TasksEvent.Create else TasksEvent.Save())
                            true
                        }
                        event.key == Key.Escape -> {
                            close()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            if (!panel.ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZillitSpinner(size = 16.dp)
                        TText(str(S.desktop_tasks_loading), 14, color = k.muted)
                    }
                }
            } else {
                PanelContent(state, panel, today, onEvent, mentionable, close)
            }
        }
    }
}

@Composable
private fun ColumnScope.PanelContent(
    state: TasksUiState,
    panel: TaskPanel,
    today: LocalDate,
    onEvent: (TasksEvent) -> Unit,
    mentionable: (Task) -> List<TaskPerson>,
    close: () -> Unit,
) {
    val k = TasksTheme.c
    val draft = panel.draft ?: return
    val task = panel.task
    val creating = panel.creating
    val readOnly = !state.canPost
    val isSub = draft.parentId != null
    val main = draft.parentId?.let { state.byId[it] }
    val blocker = if (!creating && task != null) closeBlocker(state.tasks, task) else 0
    val mainTitle = main?.title?.takeIf { it.isNotBlank() } ?: if (creating) panel.parentTitle else task?.parentTitle.orEmpty()
    val priv = isPrivate(Task(id = "", title = "", isSelf = draft.isSelf, assigneeId = draft.assigneeId, createdBy = task?.createdBy ?: state.me)) && (main == null || isPrivate(main))
    val canReassign = creating || task?.canReassign == true
    val showComments = !creating && !priv && task != null
    val limits = dueLimits(draft.parentId, if (creating) null else task?.id, state.byId, state.tasks)
    val cancelled = draft.status == TaskStatus.Cancelled
    val set = { next: TaskDraft -> onEvent(TasksEvent.DraftChanged(next)) }

    // Header ---------------------------------------------------------------------------
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Crumb(state, panel, draft, task, main, mainTitle, priv, Modifier.weight(1f), onEvent)
        if (!creating && state.canPost && task != null) {
            val blocked = blocker > 0 && draft.status.isOpen
            val (fg, bg) = when {
                cancelled -> k.cncl to k.cnclBg
                draft.status == TaskStatus.Done -> k.ok to k.okBg
                blocked -> k.faint to k.surface
                else -> k.fg to k.surface
            }
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier.height(32.dp).clip(shape).background(bg)
                    .then(if (draft.status.isOpen) Modifier.border(BorderStroke(1.dp, k.line2), shape) else Modifier)
                    .then(
                        if (blocked) Modifier else Modifier.clickable {
                            val back = panel.orig?.status?.takeIf { it.isOpen } ?: TaskStatus.Todo
                            onEvent(TasksEvent.PickStatus(if (draft.status.isOpen) TaskStatus.Done else back))
                        },
                    ).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(if (cancelled) ZillitIcons.Close else ZillitIcons.Check, tint = fg, size = 14.dp)
                TText(
                    str(when { cancelled -> S.cancelled; draft.status == TaskStatus.Done -> S.completed; else -> S.desktop_tasks_menu_mark_complete }),
                    13, Medium, fg, maxLines = 1,
                )
            }
        }
        if (!creating && task != null) {
            TaskMenu(
                task = task.copy(status = draft.status), canPost = state.canPost, blocker = blocker,
                onStatus = { onEvent(TasksEvent.ChooseStatus(it)) }, onDelete = { onEvent(TasksEvent.DeleteOpenTask) }, blockerNote = ::blockerNote,
            )
        }
        TIconBtn(ZillitIcons.Close, str(S.close), close)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))

    // Body -----------------------------------------------------------------------------
    ZillitScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (cancelled) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(k.cnclBg).padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TText(str(if (isSub) S.desktop_tasks_cancelled_banner_subtask else S.desktop_tasks_cancelled_banner), 13, Medium, k.cncl, Modifier.weight(1f))
                if (state.canPost && !creating) TText(str(S.desktop_tasks_menu_reopen), 13, SemiBold, k.accent, Modifier.clickable { onEvent(TasksEvent.ChooseStatus(TaskStatus.Todo)) }.padding(4.dp))
            }
        }
        // The task name: no box, and a thin blue line under it while it has the focus.
        TInput(
            value = draft.title, onChange = { set(draft.copy(title = it.replace('\n', ' '))) },
            placeholder = str(if (isSub) S.desktop_tasks_field_subtask_title else S.desktop_tasks_field_title),
            style = txt(22, SemiBold, 29.sp), singleLine = false, enabled = !readOnly, maxLength = TITLE_MAX,
            modifier = Modifier.fillMaxWidth(),
            decoration = { inner, focused ->
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(bottom = 6.dp)) { inner() }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(if (focused) k.accent else Color.Transparent))
                }
            },
        )
        if (draft.isSelf && priv) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitIcon(ZillitIcons.Lock, tint = k.muted, size = 12.dp)
                TText(str(S.desktop_tasks_private_note), 13, color = k.muted)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Prop(ZillitIcons.User, str(S.desktop_tasks_field_assignee)) {
                TPicker(
                    value = draft.assigneeId, picks = crewPicks(state.assignableCrew, state.hodIds),
                    emptyLabel = str(if (draft.isSelf) S.desktop_tasks_self_only_me_private else S.unassigned),
                    onChange = { set(draft.copy(assigneeId = it)) }, enabled = !readOnly && canReassign, avatars = true,
                )
                if (!canReassign) TText(str(S.desktop_tasks_reassign_creator_only), 12, color = k.faint)
            }
            val assigner = if (!creating && task != null && task.assigneeId != null && draft.assigneeId == task.assigneeId) assignerOf(task) else null
            if (assigner != null && task != null) Prop(ZillitIcons.Tick, str(S.desktop_tasks_assigned_by)) { AssignedBy(task, assigner, state, today) }
            Prop(ZillitIcons.Calendar, str(S.ah_run_detail_col_due)) {
                val due = draft.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                TDate(
                    value = draft.dueDate,
                    label = due?.let { fullDayText(it) } ?: str(S.desktop_tasks_add_due_date),
                    onChange = { set(draft.copy(dueDate = it)) }, today = today, enabled = !readOnly, min = limits.min, max = limits.max,
                    hint = when {
                        limits.max != null -> str(S.desktop_tasks_due_limit_main, dayText(LocalDate.parse(limits.max), today))
                        limits.min != null -> str(S.desktop_tasks_due_limit_subtasks, dayText(LocalDate.parse(limits.min), today))
                        else -> null
                    },
                )
                if (due != null) DueHint(draft, today)
            }
            Prop(ZillitIcons.Clock, str(S.status)) {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Segs {
                    TaskStatus.board.forEach { status ->
                        val look = statusLook(status)
                        Seg(statusText(status), draft.status == status, look.dot, look.fg, look.bg, look.dot, enabled = !readOnly) { onEvent(TasksEvent.PickStatus(status)) }
                    }
                }
                if (blocker > 0) TText(blockerNote(blocker), 12, color = k.faint)
              }
            }
            Prop(ZillitIcons.Pin, str(S.priority)) {
                Segs {
                    TaskPriority.entries.forEach { p ->
                        val (dot, fg, bg) = when (p) {
                            TaskPriority.High -> Triple(Color(0xFFE2574C), k.late, k.lateBg)
                            TaskPriority.Med -> Triple(Color(0xFFD9A21B), k.warn, k.warnBg)
                            TaskPriority.Low -> Triple(Color(0xFF9AA1B2), k.chipFg, k.chip)
                        }
                        Seg(priorityText(p), draft.priority == p, dot, fg, bg, dot, enabled = !readOnly) { set(draft.copy(priority = p)) }
                    }
                }
            }
            if (!draft.isSelf) {
                Prop(ZillitIcons.Tag, str(S.department)) {
                    TPicker(
                        value = draft.departmentId, picks = state.departments.map { Pick(it.id, it.name, colour = Color(deptColour(it.id))) },
                        emptyLabel = str(if (creating) S.desktop_choose_a_department else S.desktop_tasks_no_department),
                        onChange = { set(draft.copy(departmentId = it)) }, enabled = !readOnly, searchHint = str(S.desktop_tasks_pick_dept),
                    )
                }
                Prop(ZillitIcons.Camera, str(S.av_scenes)) {
                    TInput(
                        draft.scenes, { set(draft.copy(scenes = it)) }, Modifier.fillMaxWidth(), placeholder = str(S.desktop_tasks_scenes_placeholder), maxLength = SCENES_MAX, enabled = !readOnly,
                        decoration = { inner, focused ->
                            Box(
                                Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp)).background(if (focused) k.surface else Color.Transparent)
                                    .border(BorderStroke(1.dp, if (focused) k.accent else Color.Transparent), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { inner() }
                        },
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Section(str(if (draft.isSelf) S.notes else S.description)) {
            TInput(
                draft.description, { set(draft.copy(description = it)) }, Modifier.fillMaxWidth(), placeholder = str(S.desktop_tasks_description_placeholder),
                style = txt(14, lineHeight = 22.sp), singleLine = false, maxLength = DESCRIPTION_MAX, enabled = !readOnly,
                decoration = { inner, focused ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 104.dp).clip(RoundedCornerShape(10.dp)).background(if (focused) k.surface else k.surface2)
                            .border(BorderStroke(1.dp, if (focused) k.accent else Color.Transparent), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                    ) { inner() }
                },
            )
        }

        if (!creating && !isSub && task != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            SubtaskSection(state, task, today, onEvent)
        }
        if (showComments) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            CommentsSection(state, task)
        }
        if (!creating && task?.history != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            HistorySection(state, task, today, panel.key)
        }
        if (!creating && task != null) {
            TText(str(S.desktop_tasks_created_by, state.crewById[task.createdBy]?.nameRole ?: str(S.desktop_tasks_former_member)), 12, color = k.faint)
        }
    }

    // Footer ---------------------------------------------------------------------------
    Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
    when {
        creating -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TBtn(str(S.cancel), { onEvent(TasksEvent.CancelCreate) }, kind = BtnKind.Ghost)
            if (state.canPost) {
                TBtn(
                    str(when { panel.saving -> S.desktop_tasks_creating; isSub -> S.desktop_tasks_create_subtask; else -> S.desktop_tasks_create_task }),
                    { onEvent(TasksEvent.Create) }, enabled = !panel.saving && draft.title.isNotBlank(),
                )
            } else {
                TBtn(str(S.desktop_request_access), { onEvent(TasksEvent.AskForRights) })
            }
        }
        panel.conflict != null -> SaveBar(str(S.desktop_tasks_conflict_hint), danger = true) {
            TBtn(str(S.desktop_tasks_conflict_load_theirs), { onEvent(TasksEvent.LoadTheirs) }, kind = BtnKind.Ghost, small = true)
            if (state.canPost) TBtn(str(S.desktop_tasks_conflict_save_mine), { onEvent(TasksEvent.Save(overwrite = true)) }, small = true, enabled = !panel.saving)
        }
        panel.dirty && state.canPost -> SaveBar(str(if (panel.nag) S.desktop_tasks_unsaved_nag else S.desktop_tasks_unsaved), danger = panel.nag) {
            TBtn(str(S.ah_discard), { onEvent(TasksEvent.Discard) }, kind = BtnKind.Ghost, small = true, enabled = !panel.saving)
            TBtn(str(if (panel.saving) S.desktop_tasks_saving else S.dm_setup_save), { onEvent(TasksEvent.Save()) }, small = true, enabled = !panel.saving && draft.title.isNotBlank())
        }
    }
    if (showComments && state.canPost) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Composer(panel, state, mentionable(task), onEvent)
    }
    if (!creating && readOnly) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TText(str(S.desktop_tasks_read_only_hint), 13, color = k.muted, modifier = Modifier.weight(1f))
            TBtn(str(S.desktop_request_access), { onEvent(TasksEvent.AskForRights) }, kind = BtnKind.Ghost, small = true)
        }
    }
}

@Composable
private fun Crumb(state: TasksUiState, panel: TaskPanel, draft: TaskDraft, task: Task?, main: Task?, mainTitle: String, priv: Boolean, modifier: Modifier, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (panel.creating) Pill(str(S.continue_new).uppercase(), k.accent, k.accentSoft, size = 11)
        when {
            draft.parentId != null -> {
                TText("↳", 13, color = k.muted)
                TText(str(S.desktop_tasks_subtask), 13, SemiBold, k.fg, maxLines = 1)
                TText("/", 13, color = k.line2)
                TText(mainTitle, 13, if (main != null) SemiBold else FontWeight.Normal, if (main != null) k.accent else k.muted, if (main != null) Modifier.clickable { onEvent(TasksEvent.BackToParent) } else Modifier, maxLines = 1)
            }
            panel.creating -> TText(str(if (draft.isSelf) S.desktop_tasks_self_task else S.desktop_tasks_project_task), 14, SemiBold, maxLines = 1)
            draft.isSelf -> {
                ZillitIcon(ZillitIcons.Lock, tint = k.muted, size = 12.dp)
                TText(str(S.desktop_tasks_self_task), 13, SemiBold, k.fg, maxLines = 1)
                TText("/", 13, color = k.line2)
                val first = { p: TaskPerson? -> p?.fullName?.substringBefore(' ') ?: str(S.desktop_tasks_former_member) }
                TText(
                    when {
                        priv -> str(S.desktop_tasks_self_only_you)
                        task?.assigneeId == state.me -> first(state.crewById[task?.createdBy])
                        else -> first(state.crewById[task?.assigneeId])
                    },
                    13, color = k.muted, maxLines = 1,
                )
            }
            else -> {
                Dot(Color(deptColour(draft.departmentId)))
                TText(state.departmentName(draft.departmentId).ifEmpty { str(S.desktop_tasks_no_department) }, 13, SemiBold, k.fg, maxLines = 1)
            }
        }
    }
}

/** One field: the label with its icon at the left, the control at the right (`.zt-prop`). */
@Composable
private fun Prop(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, content: @Composable () -> Unit) {
    val k = TasksTheme.c
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(128.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ZillitIcon(icon, tint = k.faint, size = 16.dp)
            TText(label, 13, color = k.muted, maxLines = 1)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Segs(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** A status or priority choice: a pill with a dot, tinted when chosen (`.zt-segs button`). */
@Composable
private fun Seg(label: String, on: Boolean, dot: Color, fg: Color, bg: Color, edge: Color, enabled: Boolean, onClick: () -> Unit) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.height(30.dp).clip(shape).background(if (on) bg else k.surface)
            .border(BorderStroke(1.dp, if (on) edge else k.line2), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Dot(dot)
        TText(label, 13, if (on) SemiBold else FontWeight.Normal, if (on) fg else k.muted, maxLines = 1)
    }
}

/** "In 3 days" / "Today" / "2 days ago", tinted as the due chip is. */
@Composable
private fun DueHint(draft: TaskDraft, today: LocalDate) {
    val k = TasksTheme.c
    val n = daysUntil(draft.dueDate, today) ?: return
    val text = when {
        n == 0 -> str(S.today)
        n == 1 -> str(S.desktop_tasks_in_day)
        n > 1 -> str(S.desktop_tasks_in_days, n)
        n == -1 -> str(S.desktop_tasks_day_ago_due)
        else -> str(S.desktop_tasks_days_ago_due, -n)
    }
    val tone = dueTone(Task(id = "", title = "", dueDate = draft.dueDate, status = draft.status), today)
    TText(text, 12, SemiBold, when (tone) { DueTone.Late -> k.late; DueTone.Today -> k.warn; DueTone.None -> k.muted })
}

@Composable
private fun Section(title: String, count: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TText(title, 14, SemiBold)
            count?.let { TText(it, 12, SemiBold, TasksTheme.c.faint) }
        }
        content()
    }
}

private fun dateOf(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date

/** "Sameer Khan · Sep 29", or "Self-assigned" when the assignee took it themselves. */
@Composable
private fun AssignedBy(task: Task, by: String, state: TasksUiState, today: LocalDate) {
    val k = TasksTheme.c
    val assignedOn = if (task.assignedAtMillis > 0) dayText(dateOf(task.assignedAtMillis), today) else ""
    if (by == task.assigneeId) {
        TText(listOf(str(S.desktop_tasks_self_assigned), assignedOn).filter { it.isNotEmpty() }.joinToString(" · "), 14, color = k.fg)
        return
    }
    val person = state.crewById[by]
    Row(Modifier.padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TAvatar(person, 24.dp)
        Column {
            PersonName(person, state.me)
            if (assignedOn.isNotEmpty()) TText(assignedOn, 12, color = k.faint)
        }
    }
}

@Composable
private fun SaveBar(message: String, danger: Boolean, actions: @Composable () -> Unit) {
    val k = TasksTheme.c
    Row(
        Modifier.fillMaxWidth().background(if (danger) k.lateBg else k.surface2).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(if (danger) k.late else k.warn)
        TText(message, 13, Medium, if (danger) k.late else k.fg, Modifier.weight(1f))
        actions()
    }
}

/**
 * A main task's subtasks. A subtask is a task of its own, so this lists them
 * from the task list and every change here is a one-click write on the subtask.
 * Cancelled subtasks are left out of the total and of the bar.
 */
@Composable
private fun SubtaskSection(state: TasksUiState, task: Task, today: LocalDate, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    val subtasks = state.subtasksOf(task.id)
    val stats = subtaskStats(subtasks)
    val summary = listOf(
        stats.done to S.desktop_tasks_subtasks_done, stats.progress to S.desktop_tasks_subtasks_progress,
        stats.todo to S.desktop_tasks_subtasks_todo, stats.cancelled to S.desktop_tasks_subtasks_cancelled,
    ).filter { it.first > 0 }.joinToString(" · ") { (n, key) -> str(key, n) }
    val actions = state.actions(today, onEvent)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TText(str(S.desktop_tasks_field_subtasks), 14, SemiBold)
            if (stats.total > 0) TText(str(S.desktop_tasks_subtasks_count, stats.done, stats.total), 12, SemiBold, k.faint)
        }
        if (subtasks.isEmpty()) {
            if (state.loaded) TText(str(S.desktop_tasks_subtasks_empty), 13, color = k.faint)
        } else {
            TText(summary, 13, color = k.muted)
            if (stats.total > 0) {
                Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(k.line)) {
                    if (stats.done > 0) Box(Modifier.weight(stats.done.toFloat()).fillMaxHeight().background(k.doneDot))
                    if (stats.progress > 0) Box(Modifier.weight(stats.progress.toFloat()).fillMaxHeight().background(k.progDot))
                    if (stats.todo > 0) Box(Modifier.weight(stats.todo.toFloat()).fillMaxHeight())
                }
            }
            subtasks.forEach { sub ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onEvent(TasksEvent.OpenTask(sub.id)) }.padding(start = 8.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusBox(sub.status, enabled = state.canPost && sub.id !in state.busy, onToggle = { actions.onStatus(sub, it) })
                    TaskTitle(sub, Modifier.weight(1f).padding(vertical = 5.dp), maxLines = 2)
                    StatusChip(sub.status)
                    TAvatar(sub.assigneeId?.let { state.crewById[it] }, 22.dp)
                    DueChip(sub, today)
                    TaskMenu(
                        task = sub, canPost = state.canPost, blocker = 0, onStatus = { actions.onStatus(sub, it) }, onDelete = { actions.onDelete(sub) },
                        onOpen = { actions.onOpen(sub) }, blockerNote = ::blockerNote,
                    )
                }
            }
        }
        if (state.canPost) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { onEvent(TasksEvent.NewSubtask(task)) }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(ZillitIcons.Add, tint = k.accent, size = 16.dp)
                TText(str(S.desktop_tasks_add_subtask), 14, SemiBold, k.accent)
            }
        }
    }
}

@Composable
private fun CommentsSection(state: TasksUiState, task: Task) {
    val k = TasksTheme.c
    val comments = task.comments.orEmpty()
    val now = remember(task.updatedMillis, comments.size) { Clock.System.now().toEpochMilliseconds() }
    Section(str(S.cs_comments), comments.takeIf { it.isNotEmpty() }?.size?.toString()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (comments.isEmpty()) TText(str(S.desktop_tasks_no_comments), 13, color = k.faint)
            comments.forEach { comment ->
                val author = state.crewById[comment.senderId]
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TAvatar(author, 32.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PersonName(author, state.me)
                            TText(timeAgo(comment.createdMillis, now), 12, color = k.faint)
                        }
                        CommentText(comment.text, comment.mentions.mapNotNull { state.crewById[it]?.fullName })
                    }
                }
            }
        }
    }
}

/** Comment text with each real @mention highlighted. */
@Composable
private fun CommentText(text: String, names: List<String>) {
    val k = TasksTheme.c
    val annotated = remember(text, names, k.accent) {
        buildAnnotatedString {
            var rest = text
            while (rest.isNotEmpty()) {
                val hit = names.map { "@$it" }.map { it to rest.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
                if (hit == null) {
                    append(rest)
                    break
                }
                append(rest.substring(0, hit.second))
                withStyle(SpanStyle(color = k.accent, fontWeight = SemiBold)) { append(hit.first) }
                rest = rest.substring(hit.second + hit.first.length)
            }
        }
    }
    ZillitText(text = annotated, style = txt(14, lineHeight = 20.sp), color = k.fg)
}

/** The comment box with its @mention list: typing "@" lists the people who can be mentioned. */
@Composable
private fun Composer(panel: TaskPanel, state: TasksUiState, people: List<TaskPerson>, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    var field by remember(panel.key) { mutableStateOf(TextFieldValue(panel.comment)) }
    // The view model clears the box after a send.
    LaunchedEffect(panel.comment) { if (panel.comment != field.text) field = TextFieldValue(panel.comment) }
    val mention = mentionAt(field.text, field.selection.start, people)
    val offered = mention?.let { mentionMatches(people, it.query).take(MENTION_ROWS) }.orEmpty()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        offered.forEach { person ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                    val (text, caret) = applyMention(field.text, mention!!, field.selection.start, person)
                    field = TextFieldValue(text, TextRange(caret))
                    onEvent(TasksEvent.CommentChanged(text))
                }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TAvatar(person, 24.dp)
                PersonName(person, state.me)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TAvatar(state.crewById[state.me], 30.dp)
            val shape = RoundedCornerShape(999.dp)
            Row(Modifier.weight(1f).height(38.dp).clip(shape).background(k.surface).border(BorderStroke(1.dp, k.line2), shape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                com.zillit.desktop.feature.tasks.ui.CommentField(field, { field = it; onEvent(TasksEvent.CommentChanged(it.text)) }, str(S.desktop_tasks_comment_placeholder)) { onEvent(TasksEvent.SendComment) }
            }
            TBtn(str(S.send), { onEvent(TasksEvent.SendComment) }, small = true, enabled = !panel.sending && panel.comment.isNotBlank())
        }
    }
}

/**
 * Who changed what on this task, and when, newest first. It is the server's
 * record (`task.history`, on every single-task response) and is only read here.
 */
@Composable
private fun HistorySection(state: TasksUiState, task: Task, today: LocalDate, key: Long) {
    val k = TasksTheme.c
    val history = task.history.orEmpty()
    var all by remember(key) { mutableStateOf(false) }
    val shown = if (all) history else history.take(HISTORY_PREVIEW)
    val now = remember(history.size) { Clock.System.now().toEpochMilliseconds() }
    Section(str(S.history), history.takeIf { it.isNotEmpty() }?.size?.toString()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (history.isEmpty()) TText(str(S.desktop_tasks_history_empty), 13, color = k.faint)
            shown.forEach { entry ->
                val person = state.crewById[entry.userId]
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TAvatar(person, 24.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PersonName(person, state.me, fallback = entry.userName.ifEmpty { null })
                            TText(timeAgo(entry.createdMillis, now), 12, color = k.faint)
                        }
                        historyLines(entry, task.isSubtask).forEach { line ->
                            TText(historyText(line, state.crewById, state::departmentName, today), 13, color = if (line is HistoryLine.Note) k.faint else k.muted)
                        }
                    }
                }
            }
            if (history.size > HISTORY_PREVIEW) {
                TText(
                    if (all) str(S.cs_templates_show_less) else str(S.desktop_tasks_history_show_all, history.size), 13, SemiBold, k.accent,
                    Modifier.clickable { all = !all }.padding(vertical = 4.dp),
                )
            }
        }
    }
}

private val PANEL_WIDTH = 640.dp
private const val TITLE_MAX = 300
private const val SCENES_MAX = 200
private const val DESCRIPTION_MAX = 10000
private const val MENTION_ROWS = 6
