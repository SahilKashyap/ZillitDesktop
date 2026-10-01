@file:Suppress("MaxLineLength") // Cards and rows are laid out in one pass.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
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
 * A board card: department (or who a self task is shared with) and priority on
 * top, the title, scenes / due date / subtask and comment counts, then who it
 * is assigned to. Everything shown comes from the project. A cancelled card is
 * greyed out and struck through, and sits at the bottom of Done.
 */
@Composable
internal fun TaskCard(
    task: Task,
    subtasks: List<Task>,
    departmentName: String,
    actions: TaskActions,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val assignee = task.assigneeId?.let { actions.crewById[it] }
    val showWho = task.assigneeId != null || !task.isSelf
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (task.status == TaskStatus.Cancelled) CANCELLED_ALPHA else 1f)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(BorderStroke(1.dp, if (isPrivate(task)) colors.borderStrong else colors.border), ZillitTheme.shapes.large)
            .clickable { actions.onOpen(task) }
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (task.isSelf) SelfTag(task, actions.me, actions.crewById, Modifier.weight(1f)) else DeptTag(task.departmentId, departmentName, Modifier.weight(1f))
            PriorityChip(task.priority)
            TaskMenu(
                task = task,
                canPost = actions.canPost,
                blocker = actions.blockerOf(task),
                onStatus = { actions.onStatus(task, it) },
                onDelete = { actions.onDelete(task) },
                blockerNote = ::blockerNote,
            )
        }
        TaskTitle(task)
        MetaRow {
            if (task.scenes.isNotBlank()) ZillitText(task.scenes, style = ZillitTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
            DueChip(task, actions.today)
            SubtaskCount(subtasks)
            if (task.commentCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitIcon(ZillitIcons.Chat, tint = colors.textMuted, size = COMMENT_ICON)
                    ZillitText(task.commentCount.toString(), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                }
            }
        }
        if (showWho) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                PersonAvatar(assignee)
                Column(Modifier.weight(1f)) {
                    if (task.assigneeId != null) {
                        PersonName(assignee, actions.me)
                    } else {
                        ZillitText(str(S.unassigned), style = ZillitTheme.typography.label, color = colors.textMuted)
                    }
                    AssignLine(task, actions.me, actions.crewById)
                }
            }
        }
    }
}

/**
 * A list row, as My Tasks and Self Tasks show it:
 * `[status box]  Title   [what the view shows]  [⋯]`, with the "Subtask of …"
 * line and tags under the title. The box completes an open task or reopens a
 * closed one in one click; clicking the title opens the task.
 */
@Composable
internal fun TaskRow(
    task: Task,
    actions: TaskActions,
    modifier: Modifier = Modifier,
    parentTitle: String = "",
    tags: @Composable () -> Unit = {},
    right: @Composable () -> Unit = {},
) {
    val colors = ZillitTheme.colors
    Column(modifier.fillMaxWidth().alpha(if (task.status == TaskStatus.Cancelled) CANCELLED_ALPHA else 1f)) {
        ZillitDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            StatusBox(task.status, enabled = actions.canPost && !actions.isBusy(task), onToggle = { actions.onStatus(task, it) })
            Column(Modifier.weight(1f).clickable { actions.onOpen(task) }, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                TaskTitle(task, maxLines = 1)
                if (parentTitle.isNotEmpty()) {
                    ZillitText(str(S.desktop_tasks_subtask_of, parentTitle), style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
                }
                MetaRow { tags() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) { right() }
            TaskMenu(
                task = task,
                canPost = actions.canPost,
                blocker = actions.blockerOf(task),
                onStatus = { actions.onStatus(task, it) },
                onDelete = { actions.onDelete(task) },
                onOpen = { actions.onOpen(task) },
                blockerNote = ::blockerNote,
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
        ZillitText(
            text = str(if (open) S.desktop_tasks_hide_closed else S.desktop_tasks_show_closed, count),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.accentText,
            modifier = Modifier.clickable { open = !open }.padding(vertical = ZillitTheme.spacing.xs),
        )
    }
    if (open || forced) {
        GroupBox(title = str(S.desktop_tasks_closed), count = count) { content() }
    }
}

private const val CANCELLED_ALPHA = 0.6f
private val COMMENT_ICON = 13.dp
