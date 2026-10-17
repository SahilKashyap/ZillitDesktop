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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.CrewGroup
import com.zillit.desktop.feature.tasks.domain.TaskDepartment
import com.zillit.desktop.feature.tasks.domain.DueTone
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskPriority
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.assignerOf
import com.zillit.desktop.feature.tasks.domain.deptColour
import com.zillit.desktop.feature.tasks.domain.dueLabel
import com.zillit.desktop.feature.tasks.domain.dueTone
import com.zillit.desktop.feature.tasks.domain.groupCrew
import com.zillit.desktop.feature.tasks.domain.isPrivate
import com.zillit.desktop.feature.tasks.domain.visibilityOf
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

/** The due date, tinted when it is late or due today (`.zt-due`); "No date" for an undated task, as the web's cards and rows show it. */
@Composable
internal fun DueChip(task: Task, today: LocalDate, modifier: Modifier = Modifier) {
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

/**
 * The DPR a task belongs to, as a dashed chip after the scenes. Text that
 * does not already start with "DPR" gets it in front.
 *
 * Nothing is drawn where the service cannot keep one: the list holds its
 * tasks through `applyFeatures`, so [dpr] is null then.
 */
@Composable
internal fun DprChip(dpr: String?, modifier: Modifier = Modifier) {
    val text = dprText(dpr)
    if (text.isEmpty()) return
    val k = TasksTheme.c
    ZillitTooltip(str(S.desktop_tasks_dpr_full)) {
        Row(
            modifier.dashedEdge(k.line2, DPR_RADIUS).padding(horizontal = 7.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(ZillitIcons.File, tint = k.faint, size = 11.dp)
            TText(text, 12, SemiBold, k.chipFg, maxLines = 1)
        }
    }
}

/**
 * "Private" after the department on a card or in the panel's header: this
 * project task is not on everyone's Board.
 *
 * Only where the service keeps visibility — a task carries none otherwise, so
 * [show] is false and the tag is gone rather than greyed out.
 */
@Composable
internal fun PrivateTag(show: Boolean, modifier: Modifier = Modifier) {
    if (!show) return
    val k = TasksTheme.c
    ZillitTooltip(str(S.desktop_tasks_private_tag_title)) {
        Row(
            modifier.clip(RoundedCornerShape(5.dp)).background(k.privateBg)
                .border(BorderStroke(1.dp, k.privateLine), RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(ZillitIcons.Lock, tint = k.muted, size = 11.dp)
            TText(str(S.desktop_hub_private), 12, SemiBold, k.chipFg, maxLines = 1)
        }
    }
}

/** A dashed border, for the DPR chip (Compose has no dashed `border`). */
private fun Modifier.dashedEdge(color: Color, radius: Float): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF), 0f)),
    )
}

private const val DPR_RADIUS = 5f
private const val DASH_ON = 3f
private const val DASH_OFF = 3f

@Composable
internal fun DeptTag(id: String?, name: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(Color(deptColour(id)))
        TText(name.ifEmpty { str(S.desktop_tasks_no_department) }, 12, SemiBold, TasksTheme.c.chipFg, maxLines = 1)
    }
}

/** "Self task · only you / from Priya / assigned to Arjun" on a self task: which way it went. */
@Composable
internal fun SelfTag(task: Task, me: String?, crewById: Map<String, TaskPerson>, modifier: Modifier = Modifier) {
    val k = TasksTheme.c
    val first = { id: String? -> crewById[id]?.fullName?.substringBefore(' ') ?: str(S.desktop_tasks_former_member) }
    val who = when {
        isPrivate(task) -> str(S.desktop_tasks_self_only_you_lc)
        task.assigneeId == me -> "${str(S.desktop_tasks_self_from)} ${first(task.createdBy)}"
        else -> "${str(S.desktop_tasks_self_assigned_to)} ${first(task.assigneeId)}"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitIcon(ZillitIcons.Lock, tint = k.muted, size = 12.dp)
        TText(str(S.desktop_tasks_self_task), 12, SemiBold, k.chipFg, maxLines = 1)
        TText("· $who", 12, Medium, k.faint, maxLines = 1)
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

/**
 * Who assigned the task, for cards and rows: "Assigned by Sameer Khan ·
 * 2nd Assistant Director" (with "(you)" when it was me), or "Self-assigned"
 * when the assignee took it themselves. Nothing for an unassigned task.
 */
@Composable
internal fun AssignLine(task: Task, me: String?, crewById: Map<String, TaskPerson>) {
    if (task.assigneeId == null) return
    val by = assignerOf(task) ?: return
    val k = TasksTheme.c
    if (by == task.assigneeId) {
        TText(str(S.desktop_tasks_self_assigned), 12, color = k.faint, maxLines = 1)
        return
    }
    val person = crewById[by]
    val text = buildString {
        append(str(S.desktop_tasks_assigned_by_line, person?.fullName ?: str(S.desktop_tasks_former_member)))
        if (by == me) append(" ").append(str(S.desktop_tasks_you))
        if (person != null && person.designation.isNotBlank()) append(" · ").append(person.designation)
    }
    TText(text, 12, color = k.faint, maxLines = 1)
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

/**
 * 📎 3 — how many images and files a task has; nothing without any.
 *
 * Read off `attachments`, which the list carries: there is no count of its own
 * on the wire.
 */
@Composable
internal fun AttachmentCount(count: Int) {
    if (count <= 0) return
    val label = if (count == 1) {
        str(S.desktop_tasks_attachments_count_one)
    } else {
        str(S.desktop_tasks_attachments_count, count)
    }
    ZillitTooltip(label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitIcon(ZillitIcons.Paperclip, tint = TasksTheme.c.muted, size = 14.dp)
            TText(count.toString(), 12, color = TasksTheme.c.muted)
        }
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

/**
 * The ⋯ button and its menu, on every board card, list row and subtask row,
 * and in the panel's header. The two confirmations (cancel, delete) open in a
 * popover at the same button, as the web's do, never as a page dialog: this
 * sits inside cards and scrolling lists, where a full-page shell would be
 * drawn inline. Without posting rights there is no menu at all.
 *
 * [blocker] is how many open subtasks the task has: "Mark complete" is greyed
 * out with the reason while there are any, and cancelling says they go too.
 * [subtaskCount] is how many subtasks it has at all (deleting takes them too).
 */
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
    subtaskCount: Int = 0,
    /**
     * "Make private" / "Make public". Null leaves the item off, which is what
     * the service not keeping visibility means: off is gone, not greyed out.
     * A subtask never gets it either — it follows its main task.
     */
    onVisibility: ((TaskVisibility) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val canDelete = canPost && task.canDelete
    if (!canPost) return

    Box(modifier) {
        TIconBtn(ZillitIcons.MoreHorizontal, str(S.desktop_tasks_menu_label), { open = true })
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
            // Only a main task, and only where the server allows this viewer
            // to switch it: its creator or whoever assigned it.
            val canSwitch = canPost && !task.isSubtask && task.canChangeVisibility
            if (onVisibility != null && canSwitch) {
                val private = visibilityOf(task) == TaskVisibility.Private
                val next = if (private) TaskVisibility.Public else TaskVisibility.Private
                add(ZillitMenuEntry.Divider)
                add(
                    ZillitMenuEntry.Action(
                        label = str(if (private) S.desktop_tasks_menu_make_public else S.desktop_tasks_menu_make_private),
                        icon = if (private) ZillitIcons.Globe else ZillitIcons.Lock,
                        onClick = { onVisibility(next) },
                    ),
                )
            }
            if (canDelete) {
                add(ZillitMenuEntry.Divider)
                add(ZillitMenuEntry.Action(str(S.delete), icon = ZillitIcons.Trash, tone = ZillitMenuTone.Danger, onClick = { confirm = Confirm.Delete }))
            }
        }
        ZillitActionMenu(expanded = open, onDismissRequest = { open = false }, entries = entries)
        ZillitMenuSurface(expanded = confirm != null, onDismissRequest = { confirm = null }) {
            val cancelling = confirm == Confirm.Cancel
            val sub = task.isSubtask
            val title = when {
                cancelling && sub -> S.desktop_tasks_confirm_cancel_subtask_title
                cancelling -> S.desktop_tasks_confirm_cancel_title
                sub -> S.desktop_tasks_confirm_delete_subtask_title
                else -> S.desktop_tasks_confirm_delete_title
            }
            val hint = when {
                !cancelling -> str(if (subtaskCount > 0) S.desktop_tasks_confirm_delete_subtasks else S.desktop_cannot_be_undone)
                blocker == 1 -> str(S.desktop_tasks_confirm_cancel_subtasks_one)
                blocker > 1 -> str(S.desktop_tasks_confirm_cancel_subtasks, blocker)
                else -> str(S.desktop_tasks_confirm_cancel_hint)
            }
            Column(Modifier.widthIn(max = 300.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TText(str(title), 14, SemiBold)
                TText(hint, 13, color = TasksTheme.c.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    // The safe answer comes first, as on the web.
                    TBtn(str(S.desktop_ds_keep_it), { confirm = null }, kind = BtnKind.Ghost, small = true)
                    TBtn(str(if (cancelling) S.desktop_tasks_menu_cancel_task else S.delete), {
                        confirm = null
                        if (cancelling) onStatus(TaskStatus.Cancelled) else onDelete()
                    }, small = true, danger = true)
                }
            }
        }
    }
}

private enum class Confirm { Cancel, Delete }

/**
 * The crew picker's choices: under a heading per department (A–Z, nobody's
 * department last), heads of department first, then by name; "(you)" on my
 * own row. A search finds a name, a designation or a department.
 */
internal fun crewPicks(crew: List<TaskPerson>, hodIds: Set<String>, exclude: String? = null, me: String? = null): List<Pick> {
    val groups: List<CrewGroup> = groupCrew(crew, hodIds = hodIds, exclude = exclude, noDepartment = str(S.desktop_tasks_no_department))
    return groups.flatMap { group ->
        group.people.map { person ->
            Pick(
                id = person.id,
                label = if (person.id == me) "${person.fullName} ${str(S.desktop_tasks_you)}" else person.fullName,
                subtitle = person.designation.ifBlank { null },
                person = person,
                section = group.name,
                search = person.department,
            )
        }
    }
}

/**
 * The department picker's choices, each with its colour and how many people
 * are in it. A search finds a department by its name, or by the name or
 * designation of someone in it, as the web's does.
 */
internal fun deptPicks(departments: List<TaskDepartment>, crew: List<TaskPerson>): List<Pick> = departments.map { dept ->
    val people = crew.filter { it.department.isNotBlank() && it.department.equals(dept.name, ignoreCase = true) }
    Pick(
        id = dept.id,
        label = dept.name,
        subtitle = if (people.size == 1) str(S.desktop_tasks_people_count_one) else str(S.desktop_tasks_people_count, people.size),
        colour = Color(deptColour(dept.id)),
        search = people.joinToString(" ") { "${it.fullName} ${it.designation}" },
    )
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
