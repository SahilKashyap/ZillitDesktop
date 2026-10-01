@file:Suppress("LongMethod","CyclomaticComplexMethod","ComplexCondition","MaxLineLength") // One panel, laid out top to bottom: it reads as a page, and splitting it would scatter one layout.

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.HISTORY_PREVIEW
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskComment
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.applyMention
import com.zillit.desktop.feature.tasks.domain.assignerOf
import com.zillit.desktop.feature.tasks.domain.closeBlocker
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.dueLimits
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
 * The task panel, sliding in from the right over a scrim. One panel shows a
 * main task, a subtask (a task of its own), or the form for a new one.
 *
 * Opening a task gives an editable copy: nothing is saved until Save changes
 * (or Ctrl/Cmd+S). Closing with unsaved changes keeps the panel open and turns
 * the bar red; so does moving to a subtask, to the main task or to the
 * new-subtask form, because that replaces what the panel holds. If someone else
 * saved first the server answers 409 and the user loads theirs or saves mine
 * anyway. Comments send straight away.
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
    val colors = ZillitTheme.colors
    val close = { onEvent(if (panel.creating) TasksEvent.CancelCreate else TasksEvent.ClosePanel) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(panel.key) { runCatching { focus.requestFocus() } }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(colors.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        )
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(PANEL_WIDTH)
                .fillMaxHeight()
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border))
                .focusRequester(focus)
                .focusable()
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
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        ZillitSpinner()
                        ZillitText(str(S.desktop_tasks_loading), color = colors.textSecondary)
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
    val colors = ZillitTheme.colors
    val draft = panel.draft ?: return
    val task = panel.task
    val creating = panel.creating
    val readOnly = !state.canPost
    val isSub = draft.parentId != null
    val main = draft.parentId?.let { state.byId[it] }
    val blocker = if (!creating && task != null) closeBlocker(state.tasks, task) else 0
    val mainTitle = main?.title?.takeIf { it.isNotBlank() } ?: if (creating) panel.parentTitle else task?.parentTitle.orEmpty()
    val priv = isPrivate(Task(id = "", title = "", isSelf = draft.isSelf, assigneeId = draft.assigneeId, createdBy = task?.createdBy ?: state.me)) &&
        (main == null || isPrivate(main))
    val canReassign = creating || task?.canReassign == true
    val showComments = !creating && !priv && task != null
    val limits = dueLimits(draft.parentId, if (creating) null else task?.id, state.byId, state.tasks)
    val cancelled = draft.status == TaskStatus.Cancelled
    val set = { next: TaskDraft -> onEvent(TasksEvent.DraftChanged(next)) }

    // Header ---------------------------------------------------------------------------
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Crumb(state, panel, draft, task, main, mainTitle, priv, Modifier.weight(1f), onEvent)
        if (!creating && state.canPost && task != null) {
            val blocked = blocker > 0 && draft.status.isOpen
            ZillitButton(
                text = str(
                    when {
                        cancelled -> S.cancelled
                        draft.status == TaskStatus.Done -> S.completed
                        else -> S.desktop_tasks_menu_mark_complete
                    },
                ),
                onClick = {
                    // Open: complete it. Closed: back to what it was, or To do.
                    val back = panel.orig?.status?.takeIf { it.isOpen } ?: TaskStatus.Todo
                    onEvent(TasksEvent.PickStatus(if (draft.status.isOpen) TaskStatus.Done else back))
                },
                variant = if (draft.status.isOpen) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = if (cancelled) ZillitIcons.Close else ZillitIcons.Check,
                enabled = !blocked,
            )
        }
        if (!creating && task != null) {
            TaskMenu(
                task = task.copy(status = draft.status),
                canPost = state.canPost,
                blocker = blocker,
                onStatus = { onEvent(TasksEvent.ChooseStatus(it)) },
                onDelete = { onEvent(TasksEvent.DeleteOpenTask) },
                blockerNote = ::blockerNote,
            )
        }
        ZillitIconButton(ZillitIcons.Close, str(S.close), onClick = close)
    }
    ZillitDivider()

    // Body -----------------------------------------------------------------------------
    ZillitScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (cancelled) {
            ZillitNotice(
                text = str(if (isSub) S.desktop_tasks_cancelled_banner_subtask else S.desktop_tasks_cancelled_banner),
                tone = StatusTone.Rejected,
                action = if (state.canPost && !creating) {
                    { ZillitButton(str(S.desktop_tasks_menu_reopen), onClick = { onEvent(TasksEvent.ChooseStatus(TaskStatus.Todo)) }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small) }
                } else {
                    null
                },
            )
        }
        ZillitTextField(
            value = draft.title,
            onValueChange = { set(draft.copy(title = it.replace('\n', ' '))) },
            placeholder = str(if (isSub) S.desktop_tasks_field_subtask_title else S.desktop_tasks_field_title),
            maxLength = TITLE_MAX,
            enabled = !readOnly,
            modifier = Modifier.fillMaxWidth(),
            onImeAction = { if (creating) onEvent(TasksEvent.Create) },
        )
        if (draft.isSelf && priv) {
            ZillitText(str(S.desktop_tasks_private_note), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }

        Prop(str(S.desktop_tasks_field_assignee)) {
            ChoicePicker(
                value = draft.assigneeId,
                choices = crewChoices(state.assignableCrew, state.hodIds),
                emptyLabel = str(if (draft.isSelf) S.desktop_tasks_self_only_me_private else S.unassigned),
                onChange = { set(draft.copy(assigneeId = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !readOnly && canReassign,
            )
            if (!canReassign) {
                ZillitText(str(S.desktop_tasks_reassign_creator_only), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            }
        }
        val assigner = if (!creating && task != null && task.assigneeId != null && draft.assigneeId == task.assigneeId) assignerOf(task) else null
        if (assigner != null && task != null) {
            Prop(str(S.desktop_tasks_assigned_by)) { AssignedBy(task, assigner, state, today) }
        }
        Prop(str(S.ah_run_detail_col_due)) {
            DueField(
                value = draft.dueDate,
                onChange = { set(draft.copy(dueDate = it)) },
                enabled = !readOnly,
                minDate = limits.min?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                helper = when {
                    limits.max != null -> str(S.desktop_tasks_due_limit_main, dayText(LocalDate.parse(limits.max), today))
                    limits.min != null -> str(S.desktop_tasks_due_limit_subtasks, dayText(LocalDate.parse(limits.min), today))
                    else -> null
                },
            )
        }
        Prop(str(S.status)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                TaskStatus.board.forEach { status ->
                    ZillitChoiceChip(
                        label = statusText(status),
                        selected = draft.status == status,
                        onClick = { if (!readOnly) onEvent(TasksEvent.PickStatus(status)) },
                    )
                }
            }
            if (blocker > 0) ZillitText(blockerNote(blocker), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        Prop(str(S.priority)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                TaskPriority.entries.forEach { priority ->
                    ZillitChoiceChip(
                        label = priorityText(priority),
                        selected = draft.priority == priority,
                        onClick = { if (!readOnly) set(draft.copy(priority = priority)) },
                    )
                }
            }
        }
        if (!draft.isSelf) {
            Prop(str(S.department)) {
                ChoicePicker(
                    value = draft.departmentId,
                    choices = state.departments.map { Choice(it.id, it.name) },
                    emptyLabel = str(if (creating) S.desktop_choose_a_department else S.desktop_tasks_no_department),
                    onChange = { set(draft.copy(departmentId = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !readOnly,
                )
            }
            Prop(str(S.av_scenes)) {
                ZillitTextField(
                    value = draft.scenes,
                    onValueChange = { set(draft.copy(scenes = it)) },
                    placeholder = str(S.desktop_tasks_scenes_placeholder),
                    maxLength = SCENES_MAX,
                    enabled = !readOnly,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ZillitDivider()
        ZillitSectionLabel(str(if (draft.isSelf) S.notes else S.description))
        ZillitTextField(
            value = draft.description,
            onValueChange = { set(draft.copy(description = it)) },
            placeholder = str(S.desktop_tasks_description_placeholder),
            singleLine = false,
            maxLength = DESCRIPTION_MAX,
            enabled = !readOnly,
            modifier = Modifier.fillMaxWidth().heightIn(min = DESCRIPTION_MIN),
        )

        if (!creating && !isSub && task != null) {
            ZillitDivider()
            SubtaskSection(state, task, today, onEvent)
        }

        if (showComments) {
            ZillitDivider()
            CommentsSection(state, task)
        }
        if (!creating && task?.history != null) {
            ZillitDivider()
            HistorySection(state, task, today, panel.key)
        }
        if (!creating && task != null) {
            ZillitText(
                str(S.desktop_tasks_created_by, state.crewById[task.createdBy]?.nameRole ?: str(S.desktop_tasks_former_member)),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }

    // Footer ---------------------------------------------------------------------------
    ZillitDivider()
    when {
        creating -> Row(
            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(str(S.cancel), onClick = { onEvent(TasksEvent.CancelCreate) }, variant = ButtonVariant.Secondary)
            if (state.canPost) {
                ZillitButton(
                    text = str(
                        when {
                            panel.saving -> S.desktop_tasks_creating
                            isSub -> S.desktop_tasks_create_subtask
                            else -> S.desktop_tasks_create_task
                        },
                    ),
                    onClick = { onEvent(TasksEvent.Create) },
                    enabled = !panel.saving && draft.title.isNotBlank(),
                )
            } else {
                ZillitButton(str(S.desktop_request_access), onClick = { onEvent(TasksEvent.AskForRights) })
            }
        }
        panel.conflict != null -> SaveBar(
            message = str(S.desktop_tasks_conflict_hint),
            danger = true,
        ) {
            ZillitButton(str(S.desktop_tasks_conflict_load_theirs), onClick = { onEvent(TasksEvent.LoadTheirs) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            if (state.canPost) {
                ZillitButton(str(S.desktop_tasks_conflict_save_mine), onClick = { onEvent(TasksEvent.Save(overwrite = true)) }, size = ButtonSize.Small, enabled = !panel.saving)
            }
        }
        panel.dirty && state.canPost -> SaveBar(
            message = str(if (panel.nag) S.desktop_tasks_unsaved_nag else S.desktop_tasks_unsaved),
            danger = panel.nag,
        ) {
            ZillitButton(str(S.ah_discard), onClick = { onEvent(TasksEvent.Discard) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = !panel.saving)
            ZillitButton(
                text = str(if (panel.saving) S.desktop_tasks_saving else S.dm_setup_save),
                onClick = { onEvent(TasksEvent.Save()) },
                size = ButtonSize.Small,
                enabled = !panel.saving && draft.title.isNotBlank(),
            )
        }
    }
    if (showComments && state.canPost) {
        ZillitDivider()
        Composer(panel, state, mentionable(task), onEvent)
    }
    if (!creating && readOnly) {
        ZillitDivider()
        Row(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(str(S.desktop_tasks_read_only_hint), style = ZillitTheme.typography.bodySmall, color = colors.textMuted, modifier = Modifier.weight(1f))
            ZillitButton(str(S.desktop_request_access), onClick = { onEvent(TasksEvent.AskForRights) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
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
    modifier: Modifier,
    onEvent: (TasksEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (panel.creating) ZillitText(str(S.continue_new), style = ZillitTheme.typography.labelSmall, color = colors.accentText)
        when {
            draft.parentId != null -> {
                ZillitText(str(S.desktop_tasks_subtask), style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
                ZillitText("/", color = colors.textMuted)
                ZillitText(
                    text = mainTitle,
                    style = ZillitTheme.typography.bodyMedium,
                    color = if (main != null) colors.accentText else colors.textSecondary,
                    maxLines = 1,
                    modifier = if (main != null) Modifier.clickable { onEvent(TasksEvent.BackToParent) } else Modifier,
                )
            }
            panel.creating -> ZillitText(
                str(if (draft.isSelf) S.desktop_tasks_self_task else S.desktop_tasks_project_task),
                style = ZillitTheme.typography.titleSmall,
                color = colors.textPrimary,
            )
            draft.isSelf -> {
                ZillitIcon(ZillitIcons.Lock, tint = colors.textMuted, size = LOCK)
                ZillitText(str(S.desktop_tasks_self_task), style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
                ZillitText("/", color = colors.textMuted)
                val first = { p: TaskPerson? -> p?.fullName?.substringBefore(' ') ?: str(S.desktop_tasks_former_member) }
                ZillitText(
                    text = when {
                        priv -> str(S.desktop_tasks_self_only_you)
                        task?.assigneeId == state.me -> first(state.crewById[task?.createdBy])
                        else -> first(state.crewById[task?.assigneeId])
                    },
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            else -> DeptTag(draft.departmentId, state.departmentName(draft.departmentId).ifEmpty { str(S.desktop_tasks_no_department) })
        }
    }
}

@Composable
private fun Prop(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.Top) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.width(LABEL_WIDTH).padding(top = ZillitTheme.spacing.sm),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) { content() }
    }
}

/** A due date: free typing, or the calendar. Only a complete date (or none) reaches [onChange]. */
@Composable
internal fun DueField(
    value: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minDate: LocalDate? = null,
    helper: String? = null,
) {
    var text by remember(value) { mutableStateOf(value.orEmpty()) }
    ZillitDateField(
        value = text,
        onValueChange = { typed ->
            text = typed
            when {
                typed.isBlank() -> onChange(null)
                runCatching { LocalDate.parse(typed.trim()) }.isSuccess -> onChange(typed.trim())
            }
        },
        modifier = modifier,
        enabled = enabled,
        minDate = minDate,
        helperText = helper,
        placeholder = str(S.desktop_tasks_add_due_date),
    )
}

private fun dateOf(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date

/** "Sameer Khan · Sep 29", or "Self-assigned" when the assignee took it themselves. */
@Composable
private fun AssignedBy(task: Task, by: String, state: TasksUiState, today: LocalDate) {
    val assignedOn = if (task.assignedAtMillis > 0) dayText(dateOf(task.assignedAtMillis), today) else ""
    if (by == task.assigneeId) {
        ZillitText(
            listOf(str(S.desktop_tasks_self_assigned), assignedOn).filter { it.isNotEmpty() }.joinToString(" · "),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
        return
    }
    val person = state.crewById[by]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        PersonAvatar(person, ASSIGNER_AVATAR)
        Column {
            PersonName(person, state.me)
            if (assignedOn.isNotEmpty()) ZillitText(assignedOn, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        }
    }
}

@Composable
private fun SaveBar(message: String, danger: Boolean, actions: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(if (danger) colors.dangerSoft else colors.surfaceSunken).padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(message, style = ZillitTheme.typography.label, color = if (danger) colors.danger else colors.textSecondary, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * A main task's subtasks. A subtask is a task of its own, so this lists them
 * from the task list and every change here is a one-click write on the
 * subtask. Cancelled subtasks are left out of the total and of the bar.
 */
@Composable
private fun SubtaskSection(state: TasksUiState, task: Task, today: LocalDate, onEvent: (TasksEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val subtasks = state.subtasksOf(task.id)
    val stats = subtaskStats(subtasks)
    val summary = listOf(
        stats.done to S.desktop_tasks_subtasks_done,
        stats.progress to S.desktop_tasks_subtasks_progress,
        stats.todo to S.desktop_tasks_subtasks_todo,
        stats.cancelled to S.desktop_tasks_subtasks_cancelled,
    ).filter { it.first > 0 }.joinToString(" · ") { (n, key) -> str(key, n) }
    val actions = state.actions(today, onEvent)

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSectionLabel(str(S.desktop_tasks_field_subtasks))
            if (stats.total > 0) ZillitText(str(S.desktop_tasks_subtasks_count, stats.done, stats.total), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        if (subtasks.isEmpty()) {
            if (state.loaded) ZillitText(str(S.desktop_tasks_subtasks_empty), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        } else {
            ZillitText(summary, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            if (stats.total > 0) ZillitProgressBar(fraction = stats.done.toFloat() / stats.total, fillColor = colors.success, modifier = Modifier.fillMaxWidth())
            subtasks.forEach { sub ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEvent(TasksEvent.OpenTask(sub.id)) }.padding(vertical = ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    StatusBox(sub.status, enabled = state.canPost && sub.id !in state.busy, onToggle = { actions.onStatus(sub, it) })
                    TaskTitle(sub, Modifier.weight(1f), maxLines = 2)
                    StatusChip(sub.status)
                    PersonAvatar(sub.assigneeId?.let { state.crewById[it] }, SUB_AVATAR)
                    DueChip(sub, today)
                    TaskMenu(
                        task = sub,
                        canPost = state.canPost,
                        blocker = 0,
                        onStatus = { actions.onStatus(sub, it) },
                        onDelete = { actions.onDelete(sub) },
                        onOpen = { actions.onOpen(sub) },
                        blockerNote = ::blockerNote,
                    )
                }
            }
        }
        if (state.canPost) {
            ZillitButton(
                text = str(S.desktop_tasks_add_subtask),
                onClick = { onEvent(TasksEvent.NewSubtask(task)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

@Composable
private fun CommentsSection(state: TasksUiState, task: Task) {
    val colors = ZillitTheme.colors
    val comments = task.comments.orEmpty()
    val now = remember(task.updatedMillis, comments.size) { Clock.System.now().toEpochMilliseconds() }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSectionLabel(str(S.cs_comments))
            if (comments.isNotEmpty()) ZillitText(comments.size.toString(), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        if (comments.isEmpty()) {
            ZillitText(str(S.desktop_tasks_no_comments), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
        comments.forEach { comment ->
            val author = state.crewById[comment.senderId]
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                PersonAvatar(author, COMMENT_AVATAR)
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        PersonName(author, state.me)
                        ZillitText(timeAgo(comment.createdMillis, now), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                    }
                    CommentText(comment, state.crewById)
                }
            }
        }
    }
}

/** Comment text with each real @mention highlighted. */
@Composable
private fun CommentText(comment: TaskComment, crewById: Map<String, TaskPerson>) {
    val colors = ZillitTheme.colors
    val names = comment.mentions.mapNotNull { crewById[it]?.fullName }
    val text = remember(comment.text, names, colors.accentText) {
        buildAnnotatedString {
            var rest = comment.text
            while (rest.isNotEmpty()) {
                val hit = names.map { "@$it" }.map { it to rest.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
                if (hit == null) {
                    append(rest)
                    break
                }
                append(rest.substring(0, hit.second))
                withStyle(SpanStyle(color = colors.accentText)) { append(hit.first) }
                rest = rest.substring(hit.second + hit.first.length)
            }
        }
    }
    ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
}

/** The comment box with its @mention list: typing "@" lists the people who can be mentioned. */
@Composable
private fun Composer(panel: TaskPanel, state: TasksUiState, people: List<TaskPerson>, onEvent: (TasksEvent) -> Unit) {
    var field by remember(panel.key) { mutableStateOf(TextFieldValue(panel.comment)) }
    // The view model clears the box after a send.
    LaunchedEffect(panel.comment) { if (panel.comment != field.text) field = TextFieldValue(panel.comment) }
    val mention = mentionAt(field.text, field.selection.start, people)
    val offered = mention?.let { mentionMatches(people, it.query).take(MENTION_ROWS) }.orEmpty()

    Column(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        offered.forEach { person ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    val (text, caret) = applyMention(field.text, mention!!, field.selection.start, person)
                    field = TextFieldValue(text, TextRange(caret))
                    onEvent(TasksEvent.CommentChanged(text))
                }.padding(vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                PersonAvatar(person, MENTION_AVATAR)
                PersonName(person, state.me)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            PersonAvatar(state.crewById[state.me], COMMENT_AVATAR)
            ZillitTextField(
                value = field,
                onValueChange = {
                    field = it
                    onEvent(TasksEvent.CommentChanged(it.text))
                },
                placeholder = str(S.desktop_tasks_comment_placeholder),
                maxLength = COMMENT_MAX,
                modifier = Modifier.weight(1f),
                // Enter picks nobody and sends; a mention is picked by clicking it.
                onImeAction = { onEvent(TasksEvent.SendComment) },
            )
            ZillitButton(
                text = str(S.send),
                onClick = { onEvent(TasksEvent.SendComment) },
                size = ButtonSize.Small,
                enabled = !panel.sending && panel.comment.isNotBlank(),
            )
        }
    }
}

/**
 * Who changed what on this task, and when, newest first. It is the server's
 * record (`task.history`, on every single-task response) and is only read
 * here; nothing in the app writes it.
 */
@Composable
private fun HistorySection(state: TasksUiState, task: Task, today: LocalDate, key: Long) {
    val colors = ZillitTheme.colors
    val history = task.history.orEmpty()
    var all by remember(key) { mutableStateOf(false) }
    val shown = if (all) history else history.take(HISTORY_PREVIEW)
    val now = remember(history.size) { Clock.System.now().toEpochMilliseconds() }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSectionLabel(str(S.history))
            if (history.isNotEmpty()) ZillitText(history.size.toString(), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        if (history.isEmpty()) ZillitText(str(S.desktop_tasks_history_empty), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        shown.forEach { entry ->
            val person = state.crewById[entry.userId]
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                PersonAvatar(person, HISTORY_AVATAR)
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        PersonName(person, state.me, fallback = entry.userName.ifEmpty { null })
                        ZillitText(timeAgo(entry.createdMillis, now), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                    }
                    historyLines(entry, task.isSubtask).forEach { line ->
                        ZillitText(
                            text = historyText(line, state.crewById, state::departmentName, today),
                            style = ZillitTheme.typography.bodySmall,
                            color = if (line is com.zillit.desktop.feature.tasks.domain.HistoryLine.Note) colors.textMuted else colors.textSecondary,
                        )
                    }
                }
            }
        }
        if (history.size > HISTORY_PREVIEW) {
            ZillitText(
                text = if (all) str(S.cs_templates_show_less) else str(S.desktop_tasks_history_show_all, history.size),
                style = ZillitTheme.typography.label,
                color = colors.accentText,
                modifier = Modifier.clickable { all = !all },
            )
        }
    }
}

private val PANEL_WIDTH = 560.dp
private val LABEL_WIDTH = 110.dp
private val DESCRIPTION_MIN = 96.dp
private val LOCK = 12.dp
private val ASSIGNER_AVATAR = 24.dp
private val SUB_AVATAR = 22.dp
private val COMMENT_AVATAR = 30.dp
private val MENTION_AVATAR = 24.dp
private val HISTORY_AVATAR = 24.dp
private const val TITLE_MAX = 300
private const val SCENES_MAX = 200
private const val DESCRIPTION_MAX = 10000
private const val COMMENT_MAX = 4000
private const val MENTION_ROWS = 6
