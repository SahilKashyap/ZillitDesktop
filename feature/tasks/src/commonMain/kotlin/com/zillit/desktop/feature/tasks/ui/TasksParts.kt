@file:Suppress("LongMethod","CyclomaticComplexMethod","MaxLineLength") // Small shared pieces, one composable each.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.CrewGroup
import com.zillit.desktop.feature.tasks.domain.DueTone
import com.zillit.desktop.feature.tasks.domain.SubtaskStats
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.dueLabel
import com.zillit.desktop.feature.tasks.domain.dueTone
import com.zillit.desktop.feature.tasks.domain.groupCrew
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.subtaskStats
import kotlinx.datetime.LocalDate

/** Colour of a task status. */
@Composable
internal fun statusColour(status: TaskStatus): Color {
    val colors = ZillitTheme.colors
    return when (status) {
        TaskStatus.Todo -> colors.textMuted
        TaskStatus.Progress -> colors.info
        TaskStatus.Done -> colors.success
        TaskStatus.Cancelled -> colors.danger
    }
}

private fun TaskStatus.tone() = when (this) {
    TaskStatus.Todo -> StatusTone.Neutral
    TaskStatus.Progress -> StatusTone.Progress
    TaskStatus.Done -> StatusTone.Ready
    TaskStatus.Cancelled -> StatusTone.Rejected
}

@Composable
internal fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
    ZillitStatusPill(label = statusText(status), tone = status.tone(), dot = true, modifier = modifier)
}

@Composable
internal fun PriorityChip(priority: TaskPriority, modifier: Modifier = Modifier) {
    val tone = when (priority) {
        TaskPriority.High -> StatusTone.Rejected
        TaskPriority.Med -> StatusTone.Pending
        TaskPriority.Low -> StatusTone.Neutral
    }
    ZillitStatusPill(label = priorityText(priority), tone = tone, modifier = modifier)
}

/**
 * The tick box at the left of a row. A click completes an open task or reopens
 * a closed one; without posting rights it only shows the status.
 */
@Composable
internal fun StatusBox(status: TaskStatus, enabled: Boolean, onToggle: (TaskStatus) -> Unit, modifier: Modifier = Modifier) {
    val colour = statusColour(status)
    val filled = status == TaskStatus.Done
    Box(
        modifier = modifier
            .size(STATUS_BOX)
            .clip(CircleShape)
            .background(if (filled) colour else Color.Transparent)
            .border(BorderStroke(BOX_BORDER, colour), CircleShape)
            .then(if (enabled) Modifier.clickable { onToggle(if (status.isOpen) TaskStatus.Done else TaskStatus.Todo) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            TaskStatus.Done -> ZillitIcon(ZillitIcons.Check, tint = ZillitTheme.colors.textOnAccent, size = TICK)
            TaskStatus.Cancelled -> ZillitIcon(ZillitIcons.Close, tint = colour, size = TICK)
            TaskStatus.Progress -> Box(Modifier.size(DOT).clip(CircleShape).background(colour))
            TaskStatus.Todo -> Unit
        }
    }
}

/** The due date of a task, tinted when it is late or due today. */
@Composable
internal fun DueChip(task: Task, today: LocalDate, modifier: Modifier = Modifier) {
    if (task.dueDate == null) return
    val colors = ZillitTheme.colors
    val colour = when (dueTone(task, today)) {
        DueTone.Late -> colors.danger
        DueTone.Today -> colors.warning
        DueTone.None -> colors.textSecondary
    }
    ZillitText(text = dueText(dueLabel(task.dueDate, today)), style = ZillitTheme.typography.label, color = colour, modifier = modifier, maxLines = 1)
}

@Composable
internal fun DeptTag(id: String?, name: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Box(Modifier.size(DEPT_DOT).clip(CircleShape).background(Color(deptColour(id))))
        ZillitText(
            text = name.ifEmpty { str(S.desktop_tasks_no_department) },
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** "Only you" / "Shared with …" for a self task, else its department. */
@Composable
internal fun SelfTag(task: Task, me: String?, crewById: Map<String, TaskPerson>, modifier: Modifier = Modifier) {
    val text = when {
        isPrivate(task) -> str(S.desktop_tasks_self_only_you)
        task.assigneeId == me -> crewById[task.createdBy]?.fullName?.substringBefore(' ') ?: str(S.desktop_tasks_former_member)
        else -> crewById[task.assigneeId]?.fullName?.substringBefore(' ') ?: str(S.desktop_tasks_former_member)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitIcon(ZillitIcons.Lock, tint = ZillitTheme.colors.textMuted, size = SMALL_ICON)
        ZillitText(
            text = "${str(S.desktop_tasks_self_task)} · $text",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** A person, as a name with "(you)" and their designation under it. */
@Composable
internal fun PersonName(person: TaskPerson?, me: String?, modifier: Modifier = Modifier, fallback: String? = null) {
    Column(modifier) {
        val name = person?.fullName ?: fallback ?: str(S.desktop_tasks_former_member)
        ZillitText(
            text = if (person != null && person.id == me) "$name ${str(S.desktop_tasks_you)}" else name,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
        if (person != null && person.designation.isNotBlank()) {
            ZillitText(
                text = person.designation,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun PersonAvatar(person: TaskPerson?, size: androidx.compose.ui.unit.Dp = AVATAR) {
    ZillitAvatar(name = person?.fullName ?: "?", userId = person?.id, size = size)
}

/** "Assigned by …" under the assignee on a card. */
@Composable
internal fun AssignLine(task: Task, me: String?, crewById: Map<String, TaskPerson>) {
    val by = com.zillit.desktop.feature.tasks.domain.assignerOf(task) ?: return
    if (task.assigneeId == null || by == task.assigneeId) return
    val name = if (by == me) str(S.desktop_tasks_you).trim('(', ')') else crewById[by]?.fullName ?: str(S.desktop_tasks_former_member)
    ZillitText(
        text = str(S.desktop_tasks_assigned_by_line, name),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/** "☑ 2/3" for a main task's subtasks; cancelled ones are not counted. */
@Composable
internal fun SubtaskCount(subtasks: List<Task>) {
    val stats: SubtaskStats = subtaskStats(subtasks)
    if (stats.total == 0) return
    ZillitText(
        text = str(S.desktop_tasks_subtasks_count, stats.done, stats.total),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
    )
}

/** A heading with a count, over a card of rows — one group of a list. */
@Composable
internal fun GroupBox(title: String, count: Int, modifier: Modifier = Modifier, sub: String? = null, late: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), ZillitTheme.shapes.large),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall, color = if (late) colors.danger else colors.textPrimary)
            sub?.let { ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted) }
            ZillitText(text = count.toString(), style = ZillitTheme.typography.label, color = colors.textMuted)
        }
        content()
    }
}

/** The ⋯ button and its menu, with the two confirmations (cancel, delete) in a dialog. */
@Composable
internal fun TaskMenu(
    task: Task,
    canPost: Boolean,
    blocker: Int,
    onStatus: (TaskStatus) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    blockerNote: (Int) -> String = { "" },
) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val canDelete = canPost && task.canDelete
    if (onOpen == null && !canPost) return

    Box(modifier) {
        ZillitIconButton(icon = ZillitIcons.MoreHorizontal, contentDescription = str(S.docusign_bulk_job_more_actions_cd), onClick = { open = true })
        val entries = buildList<ZillitMenuEntry> {
            onOpen?.let {
                add(ZillitMenuEntry.Action(str(if (task.isSubtask) S.desktop_tasks_menu_open_subtask else S.desktop_tasks_menu_open_task), onClick = it))
            }
            if (canPost) {
                if (task.status.isOpen) {
                    listOf(TaskStatus.Todo, TaskStatus.Progress).filter { it != task.status }.forEach { status ->
                        add(ZillitMenuEntry.Action(str(S.drive_move_to_destination, statusText(status)), onClick = { onStatus(status) }))
                    }
                    add(
                        ZillitMenuEntry.Action(
                            label = if (blocker > 0) "${str(S.desktop_tasks_menu_mark_complete)} · ${blockerNote(blocker)}" else str(S.desktop_tasks_menu_mark_complete),
                            icon = ZillitIcons.Check,
                            enabled = blocker == 0,
                            onClick = { onStatus(TaskStatus.Done) },
                        ),
                    )
                    add(ZillitMenuEntry.Action(str(S.desktop_tasks_menu_cancel_task), tone = ZillitMenuTone.Danger, onClick = { confirm = Confirm.Cancel }))
                } else {
                    add(ZillitMenuEntry.Action(str(S.desktop_tasks_menu_reopen), icon = ZillitIcons.Reload, onClick = { onStatus(TaskStatus.Todo) }))
                }
            }
            if (canDelete) {
                add(ZillitMenuEntry.Divider)
                add(ZillitMenuEntry.Action(str(S.delete), icon = ZillitIcons.Trash, tone = ZillitMenuTone.Danger, onClick = { confirm = Confirm.Delete }))
            }
        }
        ZillitActionMenu(expanded = open, onDismissRequest = { open = false }, entries = entries)
    }

    confirm?.let { which ->
        val cancelling = which == Confirm.Cancel
        ZillitDialogShell(
            title = str(if (cancelling) S.desktop_tasks_confirm_cancel_title else S.desktop_tasks_confirm_delete_title),
            subtitle = when {
                cancelling && !task.isSubtask -> str(S.desktop_tasks_confirm_cancel_main)
                cancelling -> null
                else -> str(S.desktop_tasks_confirm_delete_body)
            },
            onDismiss = { confirm = null },
            visible = true,
            width = CONFIRM_WIDTH,
            scrollable = false,
            actions = {
                ZillitButton(str(S.desktop_ds_keep_it), onClick = { confirm = null }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                ZillitButton(
                    text = str(if (cancelling) S.desktop_tasks_menu_cancel_task else S.delete),
                    onClick = {
                        confirm = null
                        if (cancelling) onStatus(TaskStatus.Cancelled) else onDelete()
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
            },
        ) {}
    }
}

private enum class Confirm { Cancel, Delete }

/** One choice of a [ChoicePicker]: an id (`""` = nothing chosen), what it reads, and a second line. */
internal data class Choice(val id: String, val label: String, val subtitle: String? = null)

/**
 * A searchable picker over a list of [Choice]s, with the "nothing" choice
 * first. Used for people and for departments: the search matches the label and
 * the second line, so a person is found by name, designation or department.
 */
@Composable
internal fun ChoicePicker(
    value: String?,
    choices: List<Choice>,
    emptyLabel: String,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val none = Choice("", emptyLabel)
    val options = listOf(none) + choices
    val chosen = options.firstOrNull { it.id == (value ?: "") } ?: none
    ZillitSearchSelect(
        value = chosen,
        options = options,
        onSelect = { onChange(it.id.ifEmpty { null }) },
        label = { it.label },
        searchText = { "${it.label} ${it.subtitle.orEmpty()}" },
        subtitle = { it.subtitle },
        modifier = modifier,
        enabled = enabled,
        placeholder = emptyLabel,
    )
}

/** The crew picker's choices: grouped by department, heads of department first. */
internal fun crewChoices(crew: List<TaskPerson>, hodIds: Set<String>, exclude: String? = null): List<Choice> {
    val groups: List<CrewGroup> = groupCrew(crew, hodIds = hodIds, exclude = exclude)
    return groups.flatMap { group ->
        group.people.map { person ->
            Choice(person.id, person.fullName, listOf(person.designation, group.name).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetaRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) { content() }
}

/** A task's title: struck through and greyed once it is done or cancelled. */
@Composable
internal fun TaskTitle(task: Task, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = task.title,
        modifier = modifier,
        style = ZillitTheme.typography.bodyLarge.copy(
            textDecoration = if (task.status.isClosed) TextDecoration.LineThrough else null,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        ),
        color = if (task.status.isClosed) colors.textMuted else colors.textPrimary,
        maxLines = maxLines,
    )
}

private val STATUS_BOX = 20.dp
private val BOX_BORDER = 2.dp
private val TICK = 13.dp
private val DOT = 8.dp
private val DEPT_DOT = 8.dp
private val SMALL_ICON = 12.dp
private val AVATAR = 26.dp
private val CONFIRM_WIDTH = 420.dp
