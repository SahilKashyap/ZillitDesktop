@file:Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "MaxLineLength") // One panel, laid out top to bottom to the web's drawer.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
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
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
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
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.applyMention
import com.zillit.desktop.feature.tasks.domain.assignerOf
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.daysUntil
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.dueLimits
import com.zillit.desktop.feature.tasks.domain.DueWhy
import com.zillit.desktop.feature.tasks.domain.dueTone
import com.zillit.desktop.feature.tasks.domain.DueTone
import com.zillit.desktop.feature.tasks.domain.historyLines
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.mentionAt
import com.zillit.desktop.feature.tasks.domain.mentionMatches
import com.zillit.desktop.feature.tasks.domain.subtaskStats
import com.zillit.desktop.feature.tasks.domain.visibilityOf
import com.zillit.desktop.feature.tasks.domain.whoCanSee
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
    /** The host's guarded launcher, behind a link in the description or a comment. */
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val panel = state.panel ?: return
    val k = TasksTheme.c
    // The ✕ and the scrim close (a new task's form just goes, as the web's
    // does); Escape cancels a new task — a new subtask back to its main task.
    val close = { onEvent(TasksEvent.ClosePanel) }
    val escape = { onEvent(if (panel.creating) TasksEvent.CancelCreate else TasksEvent.ClosePanel) }
    val focus = remember { FocusRequester() }
    // A new task starts in its title (see PanelContent); anything else in the panel.
    LaunchedEffect(panel.key) { if (!panel.creating) runCatching { focus.requestFocus() } }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(k.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close))
        Column(
            Modifier.align(Alignment.CenterEnd).widthIn(max = PANEL_WIDTH).fillMaxWidth(0.9f).fillMaxHeight()
                .background(k.surface).border(BorderStroke(1.dp, k.line))
                // The key handlers come before the panel's own focus target, so they
                // also hear keys while the panel itself (not a field in it) has the focus.
                // Ctrl/Cmd+S saves (or creates), wherever the focus is in the panel.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed) || event.key != Key.S) {
                        return@onPreviewKeyEvent false
                    }
                    onEvent(if (panel.creating) TasksEvent.Create else TasksEvent.Save())
                    true
                }
                // Escape closes, or nags about unsaved changes — only once nothing
                // inside used it: the @mention list takes it first to close itself.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return@onKeyEvent false
                    escape()
                    true
                }
                .focusRequester(focus).focusable(),
        ) {
            if (!panel.ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZillitSpinner(size = 16.dp)
                        TText(str(S.desktop_tasks_loading), 14, color = k.muted)
                    }
                }
            } else {
                PanelContent(state, panel, today, onEvent, mentionable, close, onOpenUrl)
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
    onOpenUrl: ((String) -> Unit)?,
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
    // Who can see the task, as the panel's own copy has it: the switch and the
    // assignee picker both move it, so this follows the draft rather than the server.
    val visibilityOn = state.features.visibility
    val shown = Task(
        id = task?.id.orEmpty(), title = "", isSelf = draft.isSelf, assigneeId = draft.assigneeId,
        createdBy = task?.createdBy ?: state.me, assignedBy = task?.assignedBy,
        visibility = draft.visibility.takeIf { visibilityOn },
    )
    val priv = isPrivate(shown) && (main == null || isPrivate(main))
    val canReassign = creating || task?.canReassign == true
    val showComments = !creating && !priv && task != null
    val limits = dueLimits(draft.parentId, if (creating) null else task?.id, state.byId, state.tasks, today.toString())
    val cancelled = draft.status == TaskStatus.Cancelled
    val set = { next: TaskDraft -> onEvent(TasksEvent.DraftChanged(next)) }
    val titleFocus = remember(panel.key) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(panel.key) { if (creating) runCatching { titleFocus.requestFocus() } }

    // Header ---------------------------------------------------------------------------
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Crumb(state, panel, draft, task, main, mainTitle, priv, visibilityOn, Modifier.weight(1f), onEvent)
        if (!creating && state.canPost && task != null) {
            val blocked = blocker > 0 && draft.status.isOpen
            val (fg, bg) = when {
                cancelled -> k.cncl to k.cnclBg
                draft.status == TaskStatus.Done -> k.ok to k.okBg
                blocked -> k.faint to k.surface
                else -> k.fg to k.surface
            }
            val shape = RoundedCornerShape(8.dp)
            val tip = when {
                blocked -> blockerNote(blocker)
                draft.status.isClosed -> str(S.desktop_tasks_menu_reopen)
                else -> null
            }
            val button = @Composable {
                Row(
                    Modifier.height(32.dp).clip(shape).background(bg)
                        .then(if (draft.status.isOpen) Modifier.border(BorderStroke(1.dp, k.line2), shape) else Modifier)
                        // Blocked, the click still answers: it says why (as the web's does), and changes nothing.
                        .clickable {
                            val back = panel.orig?.status?.takeIf { it.isOpen } ?: TaskStatus.Todo
                            onEvent(TasksEvent.PickStatus(if (draft.status.isOpen) TaskStatus.Done else back))
                        }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitIcon(if (cancelled) ZillitIcons.Close else ZillitIcons.Check, tint = fg, size = 14.dp)
                    TText(
                        str(when { cancelled -> S.cancelled; draft.status == TaskStatus.Done -> S.completed; else -> S.desktop_tasks_menu_mark_complete }),
                        13, Medium, fg, maxLines = 1,
                    )
                }
            }
            if (tip != null) ZillitTooltip(tip) { button() } else button()
        }
        if (!creating && task != null) {
            TaskMenu(
                task = task.copy(status = draft.status), canPost = state.canPost, blocker = blocker,
                onStatus = { onEvent(TasksEvent.ChooseStatus(it)) }, onDelete = { onEvent(TasksEvent.DeleteOpenTask) }, blockerNote = ::blockerNote,
                subtaskCount = if (draft.parentId == null) state.subtasksOf(task.id).size else 0,
                onVisibility = if (visibilityOn) { next -> onEvent(TasksEvent.ChooseVisibility(next)) } else null,
            )
        }
        TIconBtn(ZillitIcons.Close, str(S.close), close)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))

    // Body -----------------------------------------------------------------------------
    // Top to bottom, as the web's drawer has it: the name, Shared tasks, the
    // description, the details, who created it, then the Comments | History tabs.
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
        // Enter creates a new task, or leaves the field on an existing one; it never breaks the line.
        TInput(
            value = draft.title, onChange = { set(draft.copy(title = it.replace('\n', ' '))) },
            placeholder = str(if (isSub) S.desktop_tasks_field_subtask_title else S.desktop_tasks_field_title),
            style = txt(22, SemiBold, 29.sp).copy(
                textDecoration = if (cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            ),
            singleLine = false, enabled = !readOnly, maxLength = TITLE_MAX,
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).onPreviewKeyEvent { event ->
                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    if (creating) onEvent(TasksEvent.Create) else focusManager.clearFocus()
                }
                true
            },
            decoration = { inner, focused ->
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(bottom = 6.dp)) { inner() }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(if (focused) k.accent else Color.Transparent))
                }
            },
        )
        // With the Visibility row there is no note here: the row says it, and better.
        if (draft.isSelf && priv && !visibilityOn) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitIcon(ZillitIcons.Lock, tint = k.muted, size = 12.dp)
                TText(str(S.desktop_tasks_private_note), 13, color = k.muted)
            }
        }

        // Shared tasks come before the description, and only on an existing main task.
        if (!creating && !isSub && task != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            SubtaskSection(state, task, today, onEvent)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Section(str(if (draft.isSelf) S.notes else S.description)) {
            DescriptionField(
                value = draft.description,
                onChange = { set(draft.copy(description = it)) },
                enabled = !readOnly,
                placeholder = str(S.desktop_tasks_description_placeholder),
                onOpenUrl = onOpenUrl,
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Prop(ZillitIcons.User, str(S.desktop_tasks_field_assignee)) {
                // Only the creator can reassign a self task: the picker is greyed out, and says why on hover.
                ZillitTooltip(if (canReassign) "" else str(S.desktop_tasks_reassign_creator_only)) {
                    TPicker(
                        value = draft.assigneeId, picks = crewPicks(state.assignableCrew, state.hodIds, me = state.me),
                        emptyLabel = str(if (draft.isSelf) S.desktop_tasks_self_only_me_private else S.unassigned),
                        onChange = { set(draft.copy(assigneeId = it)) }, enabled = !readOnly && canReassign, avatars = true,
                        searchHint = str(S.desktop_tasks_search_people), former = str(S.desktop_tasks_former_member),
                        defaultOpen = panel.pickAssignee,
                    )
                }
            }
            val assigner = if (!creating && task != null && task.assigneeId != null && draft.assigneeId == task.assigneeId) assignerOf(task) else null
            if (assigner != null && task != null) Prop(ZillitIcons.Tick, str(S.desktop_tasks_assigned_by)) { AssignedBy(task, assigner, state, today) }
            if (visibilityOn) {
                Prop(if (draft.visibility == TaskVisibility.Private) ZillitIcons.Lock else ZillitIcons.Globe, str(S.visibility)) {
                    VisibilityRow(state, panel, draft, task, main, isSub, readOnly, onEvent)
                }
            }
            Prop(ZillitIcons.Calendar, str(S.ah_run_detail_col_due)) {
                val due = draft.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                TDate(
                    value = draft.dueDate,
                    label = due?.let { fullDayText(it) } ?: str(S.desktop_tasks_add_due_date),
                    onChange = { set(draft.copy(dueDate = it)) }, today = today, enabled = !readOnly, min = limits.min, max = limits.max,
                    // The note says which rule the greyed-out days come from.
                    hint = when (limits.why) {
                        DueWhy.Main -> str(S.desktop_tasks_due_after_main, yearDayText(limits.max))
                        DueWhy.Subtasks -> str(S.desktop_tasks_due_note_subtasks, yearDayText(limits.min))
                        null -> str(S.desktop_tasks_due_note_past)
                    },
                )
                if (due != null) DueHint(draft, today)
            }
            Prop(ZillitIcons.Clock, str(S.status)) {
                Segs {
                    TaskStatus.board.forEach { status ->
                        val look = statusLook(status)
                        // Done while subtasks are open: still pressable (it says why), with the reason on hover.
                        val stopped = status == TaskStatus.Done && blocker > 0
                        ZillitTooltip(if (stopped) blockerNote(blocker) else "") {
                            Seg(statusText(status), draft.status == status, look.dot, look.fg, look.bg, look.dot, enabled = !readOnly) { onEvent(TasksEvent.PickStatus(status)) }
                        }
                    }
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
                        value = draft.departmentId, picks = deptPicks(state.departments, state.crew),
                        // A new task with none goes to the server's default: the creator's own department.
                        emptyLabel = str(if (creating) S.desktop_tasks_department_default else S.desktop_tasks_no_department),
                        onChange = { set(draft.copy(departmentId = it)) }, enabled = !readOnly, searchHint = str(S.desktop_tasks_pick_dept),
                    )
                }
                Prop(ZillitIcons.Camera, str(S.av_scenes)) {
                    FieldInput(draft.scenes, { set(draft.copy(scenes = it)) }, str(S.desktop_tasks_scenes_placeholder), SCENES_MAX, !readOnly)
                }
                // The DPR, under Scenes, only where the service keeps one.
                if (state.features.dpr) {
                    Prop(ZillitIcons.File, str(S.desktop_tasks_field_dpr)) {
                        FieldInput(draft.dpr, { set(draft.copy(dpr = it)) }, str(S.desktop_tasks_dpr_placeholder), DPR_MAX, !readOnly)
                    }
                }
            }
        }

        if (!creating && task != null) {
            TText(
                str(S.desktop_tasks_created_by, creatorName(task, state)),
                12, color = k.faint,
            )
        }
        // Comments | History at the foot of the panel. A new task has neither.
        if (!creating && task != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            ActivityTabs(state, panel, task, today, priv, onEvent, onOpenUrl)
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
        state.panelDirty && state.canPost -> SaveBar(str(if (panel.nag) S.desktop_tasks_unsaved_nag else S.desktop_tasks_unsaved), danger = panel.nag) {
            TBtn(str(S.ah_discard), { onEvent(TasksEvent.Discard) }, kind = BtnKind.Ghost, small = true, enabled = !panel.saving)
            TBtn(str(if (panel.saving) S.desktop_tasks_saving else S.dm_setup_save), { onEvent(TasksEvent.Save()) }, small = true, enabled = !panel.saving && draft.title.isNotBlank())
        }
    }
    if (showComments && state.canPost && panel.tab == PanelTab.Comments) {
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
private fun Crumb(
    state: TasksUiState,
    panel: TaskPanel,
    draft: TaskDraft,
    task: Task?,
    main: Task?,
    mainTitle: String,
    priv: Boolean,
    visibilityOn: Boolean,
    modifier: Modifier,
    onEvent: (TasksEvent) -> Unit,
) {
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
                        visibilityOn && draft.visibility == TaskVisibility.Public -> str(S.desktop_tasks_self_public_lc)
                        priv -> str(S.desktop_tasks_self_only_you)
                        task?.assigneeId == state.me -> "${str(S.fromText)} ${first(state.crewById[task?.createdBy])}"
                        else -> "${str(S.desktop_tasks_field_assignee)} ${first(state.crewById[draft.assigneeId])}"
                    },
                    13, color = k.muted, maxLines = 1,
                )
            }
            else -> {
                Dot(Color(deptColour(draft.departmentId)))
                TText(state.departmentName(draft.departmentId).ifEmpty { str(S.desktop_tasks_no_department) }, 13, SemiBold, k.fg, maxLines = 1)
                PrivateTag(visibilityOn && draft.visibility == TaskVisibility.Private)
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

/** "Overdue by 2 days" / "Due today" / "Tomorrow" / "In 5 days" beside the date; nothing once the task is closed. */
@Composable
private fun DueHint(draft: TaskDraft, today: LocalDate) {
    val k = TasksTheme.c
    if (draft.status.isClosed) return
    val n = daysUntil(draft.dueDate, today) ?: return
    val text = when {
        n < -1 -> str(S.desktop_tasks_hint_overdue, -n)
        n == -1 -> str(S.desktop_tasks_hint_overdue_one)
        n == 0 -> str(S.desktop_inv_due_today)
        n == 1 -> str(S.tomorrow)
        else -> str(S.desktop_tasks_in_days, n)
    }
    val tone = dueTone(Task(id = "", title = "", dueDate = draft.dueDate, status = draft.status), today)
    TText(text, 12, SemiBold, when (tone) { DueTone.Late -> k.late; DueTone.Today -> k.warn; DueTone.None -> k.muted })
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TText(title, 14, SemiBold)
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
 * A main task's shared tasks (subtasks in the code). A subtask is a task of
 * its own, so this lists them from the task list and every change here is a
 * one-click write on the subtask.
 *
 * Laid out as the standalone app has it: the summary beside the heading with
 * each part in its status colour, a bar that is green for done then blue for
 * in progress, then one bordered list ending in the add row. Cancelled
 * subtasks are left out of the total and of the bar.
 */
@Composable
private fun SubtaskSection(state: TasksUiState, task: Task, today: LocalDate, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    val subtasks = state.subtasksOf(task.id)
    val stats = subtaskStats(subtasks)
    val actions = state.actions(today, onEvent)
    val shape = RoundedCornerShape(10.dp)
    // Each part of the summary in its own status colour, as the demo draws it.
    val parts = listOf(
        Triple(stats.done, S.desktop_tasks_subtasks_done, k.doneDot),
        Triple(stats.progress, S.desktop_tasks_subtasks_progress, k.progDot),
        Triple(stats.todo, S.desktop_tasks_subtasks_todo, k.muted),
        Triple(stats.cancelled, S.desktop_tasks_subtasks_cancelled, k.cncl),
    ).filter { it.first > 0 }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TText(str(S.desktop_tasks_field_subtasks), 14, SemiBold)
            Segs {
                parts.forEach { (n, key, colour) -> TText(str(key, n), 12, Medium, colour, maxLines = 1) }
            }
        }
        if (stats.total > 0) {
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(k.line)) {
                if (stats.done > 0) Box(Modifier.weight(stats.done.toFloat()).fillMaxHeight().background(k.doneDot))
                if (stats.progress > 0) Box(Modifier.weight(stats.progress.toFloat()).fillMaxHeight().background(k.progDot))
                if (stats.todo > 0) Box(Modifier.weight(stats.todo.toFloat()).fillMaxHeight())
            }
        }
        // Nothing to show and nothing to add: say so. For someone who can post
        // the add row IS the list, so no empty line is needed.
        if (subtasks.isEmpty() && !state.canPost) {
            if (state.loaded) TText(str(S.desktop_tasks_subtasks_empty), 13, color = k.faint)
            return@Column
        }
        Column(Modifier.fillMaxWidth().clip(shape).border(BorderStroke(1.dp, k.line), shape)) {
            subtasks.forEachIndexed { at, sub ->
                if (at > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
                Row(
                    Modifier.fillMaxWidth().clickable { onEvent(TasksEvent.OpenTask(sub.id)) }
                        .padding(start = 10.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusBox(sub.status, enabled = state.canPost && sub.id !in state.busy, onToggle = { actions.onStatus(sub, it) })
                    TaskTitle(sub, Modifier.weight(1f).padding(vertical = 4.dp), maxLines = 2)
                    if (sub.dueDate != null) DueChip(sub, today)
                    StatusChip(sub.status)
                    // The avatar alone: hovering it says who (or "Unassigned" / "Former crew member").
                    val who = sub.assigneeId?.let { state.crewById[it] }
                    ZillitTooltip(
                        who?.nameRole ?: str(if (sub.assigneeId != null) S.desktop_tasks_former_member else S.unassigned),
                    ) { TAvatar(who, 22.dp) }
                    TaskMenu(
                        task = sub, canPost = state.canPost, blocker = 0, onStatus = { actions.onStatus(sub, it) }, onDelete = { actions.onDelete(sub) },
                        onOpen = { actions.onOpen(sub) }, blockerNote = ::blockerNote,
                    )
                }
            }
            if (state.canPost) {
                if (subtasks.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
                Row(
                    Modifier.fillMaxWidth().clickable { onEvent(TasksEvent.NewSubtask(task)) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitIcon(ZillitIcons.Add, tint = k.accent, size = 16.dp)
                    TText(str(S.desktop_tasks_add_subtask), 14, SemiBold, k.accent)
                }
            }
        }
    }
}

@Composable
private fun CommentsSection(state: TasksUiState, task: Task, onOpenUrl: ((String) -> Unit)?) {
    val k = TasksTheme.c
    val comments = task.comments.orEmpty()
    val now = remember(task.updatedMillis, comments.size) { Clock.System.now().toEpochMilliseconds() }
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
                    // Mentions highlighted and links clickable, as the description's are.
                    RichText(
                        comment.text,
                        mentionNames = comment.mentions.mapNotNull { state.crewById[it]?.fullName },
                        onOpenUrl = onOpenUrl,
                    )
                }
            }
        }
    }
}

/**
 * The comment box with its @mention list, as the web's `MentionInput`.
 * Typing "@" lists the people who can be mentioned, each with their photo,
 * name and designation, over the box:
 *
 *  - the list follows what is typed after the "@" (a name, a designation or a
 *    department); ↑/↓ move, Enter or Tab puts "@Full Name " in the text, and
 *    the mouse works too;
 *  - Escape closes only the list, and it stays closed for that "@" until
 *    another is typed — as it does after a pick, so a longer name starting the
 *    same way ("Ravi", then "Ravi Kapoor") cannot reopen it and swallow Enter;
 *  - with the list closed, Enter sends.
 */
@Composable
private fun Composer(panel: TaskPanel, state: TasksUiState, people: List<TaskPerson>, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    var field by remember(panel.key) { mutableStateOf(TextFieldValue(panel.comment)) }
    // The view model clears the box after a send.
    LaunchedEffect(panel.comment) { if (panel.comment != field.text) field = TextFieldValue(panel.comment) }
    // The "@" (its index) the list was closed for, by Escape or a pick.
    var closedAt by remember(panel.key) { mutableStateOf<Int?>(null) }
    var active by remember(panel.key) { mutableStateOf(0) }
    val mention = mentionAt(field.text, field.selection.start, people)
    val matches = mention?.let { mentionMatches(people, it.query) }.orEmpty()
    val open = mention != null && matches.isNotEmpty() && closedAt != mention.start
    LaunchedEffect(mention?.query) { active = 0 }
    LaunchedEffect(mention?.start) { if (mention == null) closedAt = null }
    val at = active.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
    val list = rememberLazyListState()
    // Keep the highlighted person in view as the arrows move it.
    LaunchedEffect(at, open) {
        if (!open) return@LaunchedEffect
        val shown = list.layoutInfo.visibleItemsInfo
        val first = shown.firstOrNull()?.index ?: 0
        val last = shown.lastOrNull()?.index ?: 0
        if (at < first) list.scrollToItem(at) else if (at >= last && shown.size > 1) list.scrollToItem((at - shown.size + 2).coerceAtLeast(0))
    }
    val pick = { person: TaskPerson ->
        mention?.let { m ->
            val (text, caret) = applyMention(field.text, m, field.selection.start, person)
            field = TextFieldValue(text, TextRange(caret.coerceAtMost(text.length)))
            closedAt = m.start
            onEvent(TasksEvent.CommentChanged(text))
        }
    }
    val onKey = { event: KeyEvent ->
        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
        when {
            open && (event.key == Key.DirectionDown || event.key == Key.DirectionUp) -> {
                if (event.type == KeyEventType.KeyDown) active = (at + if (event.key == Key.DirectionDown) 1 else -1).coerceIn(0, matches.size - 1)
                true
            }
            open && (enter || event.key == Key.Tab) -> {
                if (event.type == KeyEventType.KeyDown) matches.getOrNull(at)?.let(pick)
                true
            }
            open && event.key == Key.Escape -> {
                if (event.type == KeyEventType.KeyDown) closedAt = mention?.start
                true
            }
            enter -> {
                if (event.type == KeyEventType.KeyDown) onEvent(TasksEvent.SendComment)
                true
            }
            else -> false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (open) {
            val shape = RoundedCornerShape(10.dp)
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().heightIn(max = MENTION_LIST_MAX).clip(shape).background(k.surface).border(BorderStroke(1.dp, k.line2), shape).padding(4.dp),
            ) {
                itemsIndexed(matches, key = { _, person -> person.id }) { index, person ->
                    val source = remember { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    LaunchedEffect(hovered) { if (hovered) active = index }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (index == at) k.accentSoft else Color.Transparent)
                            .hoverable(source).clickable(interactionSource = source, indication = null) { pick(person) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TAvatar(person, 28.dp)
                        PersonName(person, state.me)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TAvatar(state.crewById[state.me], 30.dp)
            val shape = RoundedCornerShape(999.dp)
            Row(Modifier.weight(1f).height(38.dp).clip(shape).background(k.surface).border(BorderStroke(1.dp, k.line2), shape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CommentField(field, { field = it; onEvent(TasksEvent.CommentChanged(it.text)) }, str(S.desktop_tasks_comment_placeholder), onKey)
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (history.isEmpty()) TText(str(S.desktop_tasks_history_empty), 13, color = k.faint)
        shown.forEach { entry ->
            val person = state.crewById[entry.userId]
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TAvatar(person, 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PersonName(person, state.me, fallback = entry.userName.ifEmpty { null })
                        ZillitTooltip(historyWhen(entry.createdMillis)) { TText(timeAgo(entry.createdMillis, now), 12, color = k.faint) }
                    }
                    historyLines(entry, task.isSubtask).forEach { line ->
                        TText(historyText(line, state.crewById, state::departmentName, today), 13, color = if (line is HistoryLine.Note) k.faint else k.muted)
                    }
                }
            }
        }
        if (history.size > HISTORY_PREVIEW) {
            TText(
                if (all) str(S.desktop_tasks_history_show_less) else str(S.desktop_tasks_history_show_all, history.size), 13, SemiBold, k.accent,
                Modifier.clickable { all = !all }.padding(vertical = 4.dp),
            )
        }
    }
}

private val PANEL_WIDTH = 640.dp
private const val TITLE_MAX = 300
private const val SCENES_MAX = 200
private val MENTION_LIST_MAX = 240.dp

/** A one-line field in the details (the scenes, the DPR): a box that only shows itself when focused. */
@Composable
private fun FieldInput(value: String, onChange: (String) -> Unit, placeholder: String, maxLength: Int, enabled: Boolean) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(6.dp)
    TInput(
        value, onChange, Modifier.fillMaxWidth(), placeholder = placeholder, maxLength = maxLength, enabled = enabled,
        decoration = { inner, focused ->
            Box(
                Modifier.fillMaxWidth().height(34.dp).clip(shape).background(if (focused) k.surface else Color.Transparent)
                    .border(BorderStroke(1.dp, if (focused) k.accent else Color.Transparent), shape).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** "Created by Ravi Kapoor · Line Producer", with "(you)" after your own name. */
private fun creatorName(task: Task, state: TasksUiState): String {
    val person = state.crewById[task.createdBy] ?: return str(S.desktop_tasks_former_member)
    val name = if (person.id == state.me) "${person.fullName} ${str(S.desktop_tasks_you)}" else person.fullName
    return if (person.designation.isNotBlank()) "$name · ${person.designation}" else name
}

/**
 * The Visibility row: a Public | Private switch, and under it who can see the
 * task.
 *
 * Only the creator or whoever assigned it may switch it
 * (`permissions.can_change_visibility`, from the server); everyone else gets
 * it disabled, with why as its tooltip. **A subtask has no switch at all**: it
 * follows its main task, and `visibility` is never sent for one.
 */
@Composable
private fun VisibilityRow(
    state: TasksUiState,
    panel: TaskPanel,
    draft: TaskDraft,
    task: Task?,
    main: Task?,
    isSub: Boolean,
    readOnly: Boolean,
    onEvent: (TasksEvent) -> Unit,
) {
    val k = TasksTheme.c
    if (isSub) {
        // It follows its main task, so there is nothing to choose here — but it
        // still says who can see it, counting the people on the main task.
        val follows = main?.let { visibilityOf(it) } ?: draft.visibility
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TText(visibilityText(follows), 14, Medium, k.fg, maxLines = 1)
                TText("· ${str(S.desktop_tasks_visibility_follows_main)}", 13, color = k.faint, maxLines = 1)
            }
            TText(whoCanSeeText(draft.copy(visibility = follows), task, state, isSub = true), 12, color = k.muted)
        }
        return
    }
    // A new task's switch is the creator's to set; an existing one's is the server's call.
    val allowed = !readOnly && (panel.creating || task?.canChangeVisibility == true)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitTooltip(if (allowed) "" else str(S.desktop_tasks_visibility_locked)) {
            Segs {
                TaskVisibility.entries.forEach { choice ->
                    val on = draft.visibility == choice
                    val dot = if (choice == TaskVisibility.Private) k.warn else k.ok
                    Seg(visibilityText(choice), on, dot, if (on) dot else k.muted, if (choice == TaskVisibility.Private) k.warnBg else k.okBg, dot, enabled = allowed) {
                        onEvent(TasksEvent.PickVisibility(choice))
                    }
                }
            }
        }
        TText(whoCanSeeText(draft, task, state, isSub = false), 12, color = k.muted)
    }
}

/**
 * The line under the switch: "Everyone on the project can see this task on
 * their Board.", "Only you can see this task.", "Only you and Sameer Khan can
 * see this task." It follows the panel's copy, so it moves as the assignee or
 * the switch changes.
 */
private fun whoCanSeeText(draft: TaskDraft, task: Task?, state: TasksUiState, isSub: Boolean): String {
    if (draft.visibility == TaskVisibility.Public) return str(S.desktop_tasks_visibility_everyone)
    val creator = task?.createdBy ?: state.me
    val people = whoCanSee(
        Task(
            id = "", title = "", isSelf = draft.isSelf, assigneeId = draft.assigneeId,
            createdBy = creator, assignedBy = task?.assignedBy, visibility = TaskVisibility.Private,
        ),
    ).orEmpty()
    val names = people.map { id ->
        when {
            id == state.me -> str(S.desktop_tasks_visibility_you)
            else -> state.crewById[id]?.fullName ?: str(S.desktop_stk_unknown_person)
        }
    }.distinct()
    if (!isSub && (names.isEmpty() || (names.size == 1 && people.singleOrNull() == state.me))) {
        return str(S.desktop_tasks_visibility_only_you)
    }
    val listed = when {
        names.isEmpty() -> str(S.desktop_tasks_visibility_you)
        names.size == 1 -> names.first()
        else -> str(S.desktop_docdist_x_and_y, names.dropLast(1).joinToString(", "), names.last())
    }
    // A private subtask is also seen by the people on its main task.
    return str(if (isSub) S.desktop_tasks_visibility_only_sub else S.desktop_tasks_visibility_only, listed)
}

/**
 * **Comments | History** at the foot of the panel — the web's `ActivityTabs`.
 *
 * Every task opens on Comments, and the comment box (the panel's own, in its
 * footer) is shown only while that tab is open, so a half-written comment
 * survives a look at History. The History tab is there only when the server
 * sent a history: the list's copy of a task has none.
 */
@Composable
private fun ActivityTabs(
    state: TasksUiState,
    panel: TaskPanel,
    task: Task,
    today: LocalDate,
    priv: Boolean,
    onEvent: (TasksEvent) -> Unit,
    onOpenUrl: ((String) -> Unit)?,
) {
    val k = TasksTheme.c
    val comments = task.comments.orEmpty()
    val hasHistory = task.history != null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActivityTab(str(S.cs_comments), comments.size.takeIf { it > 0 }, panel.tab == PanelTab.Comments) {
                onEvent(TasksEvent.TabChanged(PanelTab.Comments))
            }
            if (hasHistory) {
                ActivityTab(str(S.history), null, panel.tab == PanelTab.History) {
                    onEvent(TasksEvent.TabChanged(PanelTab.History))
                }
            }
        }
        if (panel.tab == PanelTab.History && hasHistory) {
            HistorySection(state, task, today, panel.key)
        } else if (priv) {
            // Nobody else can see the task, so there is nobody to talk to yet.
            TText(str(S.desktop_tasks_comments_private), 13, color = k.faint)
        } else {
            CommentsSection(state, task, onOpenUrl)
        }
    }
}

@Composable
private fun ActivityTab(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.clip(shape).background(if (on) k.chip else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TText(label, 14, if (on) SemiBold else FontWeight.Normal, if (on) k.fg else k.muted, maxLines = 1)
        count?.let { TText(it.toString(), 12, SemiBold, k.faint, maxLines = 1) }
    }
}

/** The service's own limit on the DPR field. */
private const val DPR_MAX = 200
