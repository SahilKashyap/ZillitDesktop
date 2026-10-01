@file:Suppress("MaxLineLength") // Cards and rows are laid out in one pass, to the web's Tasks.css.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TaskStatus
import com.zillit.desktop.feature.tasks.domain.isPrivate
import kotlinx.datetime.LocalDate

/** The short note under a greyed-out "Mark complete". */
internal fun blockerNote(open: Int): String =
    if (open == 1) str(S.desktop_tasks_open_subtasks_short_one) else str(S.desktop_tasks_open_subtasks_short, open)

/** What every card and row needs from the view that shows it. */
internal class TaskActions(
    val canPost: Boolean,
    val me: String?,
    val crewById: Map<String, TaskPerson>,
    val today: LocalDate,
    val onOpen: (Task) -> Unit,
    val onStatus: (Task, TaskStatus) -> Unit,
    val onDelete: (Task) -> Unit,
    val blockerOf: (Task) -> Int,
    val isBusy: (Task) -> Boolean,
)

/**
 * A board card (`.zt-card`): department (or who a self task is shared with)
 * and priority on top, the title, scenes / due / subtasks / comments, then who
 * it is assigned to. A private self task has a dashed edge; a cancelled card
 * is greyed out and struck through.
 */
@Composable
internal fun TaskCard(
    task: Task,
    subtasks: List<Task>,
    departmentName: String,
    actions: TaskActions,
    modifier: Modifier = Modifier,
) {
    val k = TasksTheme.c
    val assignee = task.assigneeId?.let { actions.crewById[it] }
    val showWho = task.assigneeId != null || !task.isSelf
    val shape = RoundedCornerShape(10.dp)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val private = isPrivate(task)
    Column(
        modifier.fillMaxWidth()
            .alpha(if (task.status == TaskStatus.Cancelled) CANCELLED_ALPHA else 1f)
            .clip(shape)
            .background(if (private) k.privateBg else k.surface)
            .border(BorderStroke(1.dp, if (private) k.privateLine else if (hovered) k.line2 else k.line), shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null) { actions.onOpen(task) }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                if (task.isSelf) SelfTag(task, actions.me, actions.crewById) else DeptTag(task.departmentId, departmentName)
            }
            PriorityChip(task.priority)
            TaskMenu(
                task = task, canPost = actions.canPost, blocker = actions.blockerOf(task),
                onStatus = { actions.onStatus(task, it) }, onDelete = { actions.onDelete(task) },
                modifier = Modifier.padding(end = 0.dp), blockerNote = ::blockerNote,
            )
        }
        TaskTitle(task)
        MetaRow {
            SceneChip(task.scenes)
            DueChip(task, actions.today)
            SubtaskCount(subtasks)
            CommentCount(task.commentCount)
        }
        if (showWho) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TAvatar(assignee, 26.dp)
                Column(Modifier.weight(1f)) {
                    if (task.assigneeId != null) {
                        PersonName(assignee, actions.me)
                    } else {
                        TText(str(S.unassigned), 13, color = k.faint, modifier = Modifier.padding(top = 4.dp))
                    }
                    AssignLine(task, actions.me, actions.crewById)
                }
            }
        }
    }
}

/**
 * A list row (`.zt-row`): `[status box]  Title  [what the view shows]  [⋯]`,
 * the "Subtask of …" line and tags under the title. The box completes an open
 * task or reopens a closed one; clicking the title opens the task.
 */
@Composable
internal fun TaskRow(
    task: Task,
    actions: TaskActions,
    modifier: Modifier = Modifier,
    parentTitle: String = "",
    editing: Boolean = false,
    tags: @Composable () -> Unit = {},
    right: @Composable () -> Unit = {},
) {
    val k = TasksTheme.c
    Column(modifier.fillMaxWidth().alpha(if (task.status == TaskStatus.Cancelled) CANCELLED_ALPHA else 1f)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Row(
            Modifier.fillMaxWidth().background(if (editing) k.warnBg else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBox(task.status, enabled = actions.canPost && !actions.isBusy(task), onToggle = { actions.onStatus(task, it) })
            Column(Modifier.weight(1f).clickable { actions.onOpen(task) }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TaskTitle(task, maxLines = 1)
                if (parentTitle.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TText("↳", 12, color = k.faint)
                        TText(str(S.desktop_tasks_subtask_of, parentTitle), 12, Medium, k.muted, maxLines = 1)
                    }
                }
                MetaRow { tags() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { right() }
            TaskMenu(
                task = task, canPost = actions.canPost, blocker = actions.blockerOf(task),
                onStatus = { actions.onStatus(task, it) }, onDelete = { actions.onDelete(task) },
                onOpen = { actions.onOpen(task) }, blockerNote = ::blockerNote,
            )
        }
    }
}

/** Closed tasks (done and cancelled), folded away behind "Show N closed" — shown outright while a search is on. */
@Composable
internal fun ClosedGroup(count: Int, forced: Boolean, content: @Composable () -> Unit) {
    if (count == 0) return
    var open by remember { mutableStateOf(false) }
    if (!forced) {
        TText(
            str(if (open) S.desktop_tasks_hide_closed else S.desktop_tasks_show_closed, count), 13, SemiBold, TasksTheme.c.muted,
            Modifier.clickable { open = !open }.padding(vertical = 8.dp),
        )
    }
    if (open || forced) GroupBox(title = str(S.desktop_tasks_closed), count = count) { content() }
}

private const val CANCELLED_ALPHA = 0.6f
