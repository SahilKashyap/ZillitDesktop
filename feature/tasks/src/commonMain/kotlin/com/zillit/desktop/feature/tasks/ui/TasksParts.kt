@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MaxLineLength") // Small shared pieces drawn to the web's Tasks.css.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.CrewGroup
import com.zillit.desktop.feature.tasks.domain.DueTone
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.assignerOf
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.dueLabel
import com.zillit.desktop.feature.tasks.domain.dueTone
import com.zillit.desktop.feature.tasks.domain.groupCrew
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.subtaskStats
import kotlinx.datetime.LocalDate

/** The status label: a coloured wash, a dot, the name (`.zt-st`). */
@Composable
internal fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
    val look = statusLook(status)
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(look.bg).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(look.dot, 7.dp)
        TText(statusText(status), 12, SemiBold, look.fg, maxLines = 1)
    }
}

/** High / Med / Low as the card's pill. */
@Composable
internal fun PriorityChip(priority: TaskPriority, modifier: Modifier = Modifier) {
    val k = TasksTheme.c
    val (fg, bg) = when (priority) {
        TaskPriority.High -> k.late to k.lateBg
        TaskPriority.Med -> k.warn to k.warnBg
        TaskPriority.Low -> k.chipFg to k.chip
    }
    val text = if (priority == TaskPriority.Med) str(S.desktop_tasks_prio_med) else priorityText(priority)
    Pill(text, fg, bg, modifier, size = 11)
}

/** The tick box at the left of a row. A click completes an open task or reopens a closed one. */
@Composable
internal fun StatusBox(status: TaskStatus, enabled: Boolean, onToggle: (TaskStatus) -> Unit, modifier: Modifier = Modifier) {
    val k = TasksTheme.c
    val look = statusLook(status)
    val shape = RoundedCornerShape(5.dp)
    val filled = status == TaskStatus.Done
    Box(
        modifier.size(20.dp).clip(shape)
            .background(if (filled) k.doneDot else Color.Transparent)
            .border(BorderStroke(1.5.dp, if (filled) k.doneDot else if (status == TaskStatus.Todo) k.line2 else look.dot), shape)
            .then(if (enabled) Modifier.clickable { onToggle(if (status.isOpen) TaskStatus.Done else TaskStatus.Todo) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            TaskStatus.Done -> ZillitIcon(ZillitIcons.Check, tint = Color.White, size = 13.dp)
            TaskStatus.Cancelled -> ZillitIcon(ZillitIcons.Close, tint = look.dot, size = 12.dp)
            TaskStatus.Progress -> Dot(look.dot, 8.dp)
            TaskStatus.Todo -> Unit
        }
    }
}

/** The due date, tinted when it is late or due today (`.zt-due`). */
@Composable
internal fun DueChip(task: Task, today: LocalDate, modifier: Modifier = Modifier) {
    if (task.dueDate == null) return
    val k = TasksTheme.c
    val (fg, bg) = when (dueTone(task, today)) {
        DueTone.Late -> k.late to k.lateBg
        DueTone.Today -> k.warn to k.warnBg
        DueTone.None -> k.chipFg to k.chip
    }
    TText(
        dueText(dueLabel(task.dueDate, today)), 12, SemiBold, fg,
        modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1,
    )
}

/** The scenes, boxed (`.zt-scene`). */
@Composable
internal fun SceneChip(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    TText(text, 12, SemiBold, TasksTheme.c.chipFg, modifier.border(BorderStroke(1.dp, TasksTheme.c.line2), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp), maxLines = 1)
}

@Composable
internal fun DeptTag(id: String?, name: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(Color(deptColour(id)))
        TText(name.ifEmpty { str(S.desktop_tasks_no_department) }, 12, SemiBold, TasksTheme.c.chipFg, maxLines = 1)
    }
}

/** "Self task · Only you" / "Self task · name" on a self task. */
@Composable
internal fun SelfTag(task: Task, me: String?, crewById: Map<String, TaskPerson>, modifier: Modifier = Modifier) {
    val k = TasksTheme.c
    val first = { p: TaskPerson? -> p?.fullName?.substringBefore(' ') ?: str(S.desktop_tasks_former_member) }
    val who = when {
        isPrivate(task) -> str(S.desktop_tasks_self_only_you)
        task.assigneeId == me -> first(crewById[task.createdBy])
        else -> first(crewById[task.assigneeId])
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitIcon(ZillitIcons.Lock, tint = k.muted, size = 12.dp)
        TText(str(S.desktop_tasks_self_task), 12, SemiBold, k.chipFg, maxLines = 1)
        TText(who, 12, Medium, k.faint, maxLines = 1)
    }
}

/** A person: name with "(you)", the designation under it. */
@Composable
internal fun PersonName(person: TaskPerson?, me: String?, modifier: Modifier = Modifier, fallback: String? = null) {
    val k = TasksTheme.c
    Column(modifier) {
        val name = person?.fullName ?: fallback ?: str(S.desktop_tasks_former_member)
        TText(if (person != null && person.id == me) "$name ${str(S.desktop_tasks_you)}" else name, 13, Medium, k.fg, maxLines = 1)
        if (person != null && person.designation.isNotBlank()) TText(person.designation, 12, color = k.faint, maxLines = 1)
    }
}

@Composable
internal fun PersonAvatar(person: TaskPerson?, size: androidx.compose.ui.unit.Dp = 26.dp) = TAvatar(person, size)

/** "Assigned by …" under the assignee on a card. */
@Composable
internal fun AssignLine(task: Task, me: String?, crewById: Map<String, TaskPerson>) {
    val by = assignerOf(task) ?: return
    if (task.assigneeId == null || by == task.assigneeId) return
    val name = if (by == me) str(S.desktop_tasks_you).trim('(', ')') else crewById[by]?.fullName ?: str(S.desktop_tasks_former_member)
    TText(str(S.desktop_tasks_assigned_by_line, name), 12, color = TasksTheme.c.faint, maxLines = 1)
}

/** "☑ 1/2" for a main task's subtasks; cancelled ones are not counted. */
@Composable
internal fun SubtaskCount(subtasks: List<Task>) {
    val stats = subtaskStats(subtasks)
    if (stats.total == 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitIcon(ZillitIcons.Tick, tint = TasksTheme.c.muted, size = 14.dp)
        TText("${stats.done}/${stats.total}", 12, color = TasksTheme.c.muted)
    }
}

@Composable
internal fun CommentCount(count: Int) {
    if (count <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitIcon(ZillitIcons.Chat, tint = TasksTheme.c.muted, size = 14.dp)
        TText(count.toString(), 12, color = TasksTheme.c.muted)
    }
}

/** A white box with its heading and a count, over rows — one group of a list (`.zt-group`). */
@Composable
internal fun GroupBox(title: String, count: Int, modifier: Modifier = Modifier, sub: String? = null, late: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(k.surface).border(BorderStroke(1.dp, k.line), shape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TText(title, 14, SemiBold, if (late) k.late else k.fg)
            sub?.let { TText(it, 13, color = k.faint) }
            Box(Modifier.weight(1f))
            TText(count.toString(), 14, Medium, k.faint)
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
        TIconBtn(ZillitIcons.MoreHorizontal, str(S.docusign_bulk_job_more_actions_cd), { open = true })
        val entries = buildList<ZillitMenuEntry> {
            onOpen?.let { add(ZillitMenuEntry.Action(str(if (task.isSubtask) S.desktop_tasks_menu_open_subtask else S.desktop_tasks_menu_open_task), onClick = it)) }
            if (canPost) {
                if (task.status.isOpen) {
                    listOf(TaskStatus.Todo, TaskStatus.Progress).filter { it != task.status }.forEach { status ->
                        add(ZillitMenuEntry.Action(str(S.drive_move_to_destination, statusText(status)), onClick = { onStatus(status) }))
                    }
                    add(
                        ZillitMenuEntry.Action(
                            label = if (blocker > 0) "${str(S.desktop_tasks_menu_mark_complete)} · ${blockerNote(blocker)}" else str(S.desktop_tasks_menu_mark_complete),
                            icon = ZillitIcons.Check, enabled = blocker == 0, onClick = { onStatus(TaskStatus.Done) },
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
        com.zillit.desktop.core.designsystem.component.ZillitDialogShell(
            title = str(if (cancelling) S.desktop_tasks_confirm_cancel_title else S.desktop_tasks_confirm_delete_title),
            subtitle = when {
                cancelling && !task.isSubtask -> str(S.desktop_tasks_confirm_cancel_main)
                cancelling -> null
                else -> str(S.desktop_tasks_confirm_delete_body)
            },
            onDismiss = { confirm = null },
            visible = true,
            width = 420.dp,
            scrollable = false,
            actions = {
                TBtn(str(S.desktop_ds_keep_it), { confirm = null }, kind = BtnKind.Ghost, small = true)
                TBtn(str(if (cancelling) S.desktop_tasks_menu_cancel_task else S.delete), {
                    confirm = null
                    if (cancelling) onStatus(TaskStatus.Cancelled) else onDelete()
                }, small = true)
            },
        ) {}
    }
}

private enum class Confirm { Cancel, Delete }

/** The crew picker's choices: grouped by department, heads of department first. */
internal fun crewPicks(crew: List<TaskPerson>, hodIds: Set<String>, exclude: String? = null): List<Pick> {
    val groups: List<CrewGroup> = groupCrew(crew, hodIds = hodIds, exclude = exclude)
    return groups.flatMap { group ->
        group.people.map { person ->
            Pick(person.id, person.fullName, listOf(person.designation, group.name).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }, person)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetaRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) { content() }
}

/** A task's title: struck through and greyed once it is done or cancelled. */
@Composable
internal fun TaskTitle(task: Task, modifier: Modifier = Modifier, maxLines: Int = 2, size: Int = 14) {
    TText(task.title, size, Medium, if (task.status.isClosed) TasksTheme.c.faint else TasksTheme.c.fg, modifier, maxLines, strike = task.status.isClosed)
}
