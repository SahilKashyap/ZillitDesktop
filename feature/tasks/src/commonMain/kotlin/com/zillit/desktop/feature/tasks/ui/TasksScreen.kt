@file:Suppress("MaxLineLength") // The shell lays out the sidebar, the view and the panel.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * The Tasks tool: a sidebar with the three views (Board, My tasks, Self
 * tasks), the open view, and the task panel over it.
 *
 * Rights gate everything. Until the tool's row has answered nothing is shown
 * or requested; a production without the tool, or a viewer without view
 * rights, gets a plain explanation instead of a request the service would
 * answer with a 403.
 */
@Composable
fun TasksScreen(
    state: TasksUiState,
    onEvent: (TasksEvent) -> Unit,
    mentionable: (Task) -> List<TaskPerson>,
    modifier: Modifier = Modifier,
) {
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val counts = state.counts

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Row(Modifier.fillMaxSize()) {
            ZillitSideNav(
                sections = listOf(
                    SideNavSection(
                        title = null,
                        items = listOf(
                            SideNavItem(TasksView.Board.name, str(S.desktop_tasks_nav_board), ZillitIcons.Grid),
                            SideNavItem(TasksView.Mine.name, str(S.desktop_tasks_nav_mine), ZillitIcons.User, count = if (state.loaded) counts.mine else 0),
                            SideNavItem(TasksView.Self.name, str(S.desktop_tasks_nav_self), ZillitIcons.Lock, count = if (state.loaded) counts.self else 0),
                        ),
                    ),
                ),
                activeId = state.view.name,
                onSelect = { id -> onEvent(TasksEvent.ViewChanged(TasksView.valueOf(id))) },
                modifier = Modifier.width(SIDEBAR).fillMaxHeight().padding(ZillitTheme.spacing.md),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    !state.viewer.resolved -> Centered { ZillitSpinner() }
                    !state.viewer.enabled -> ZillitEmptyState(
                        title = str(S.desktop_tasks_gate_disabled_title),
                        message = str(S.desktop_tasks_gate_disabled_hint),
                        icon = ZillitIcons.Shield,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    !state.viewer.canView -> ZillitEmptyState(
                        title = str(S.desktop_tasks_gate_noview_title),
                        message = str(S.desktop_tasks_gate_noview_hint),
                        icon = ZillitIcons.Shield,
                        modifier = Modifier.align(Alignment.Center),
                        action = { ZillitButton(str(S.desktop_request_access), onClick = { onEvent(TasksEvent.AskForRights) }, variant = ButtonVariant.Secondary) },
                    )
                    else -> when (state.view) {
                        TasksView.Board -> BoardView(state, today, onEvent)
                        TasksView.Mine -> MineView(state, today, onEvent)
                        TasksView.Self -> SelfView(state, today, onEvent)
                    }
                }
            }
        }
        if (state.viewer.canCall) TaskPanelOverlay(state, today, onEvent, mentionable)
        // A refusal from the service, or a rule the client checked first ("2 subtasks are still open").
        ZillitErrorToast(
            message = state.error ?: state.notice,
            onDismiss = { onEvent(TasksEvent.DismissMessage) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(ZillitTheme.spacing.lg),
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private val SIDEBAR = 232.dp
