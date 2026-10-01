package com.zillit.desktop.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The Tasks tool, at the web's path (`/film-tools/tasks`).
 *
 * The views are the tool's own sidebar, not routes: the web nests
 * `/film-tools/tasks/mine` and `/self`, but nothing outside the tool links to
 * them, so one window owns all three.
 */
class TasksToolProvider(private val viewModel: TasksViewModel) : ToolProvider {

    override val path: String = TASKS_PATH
    override val title: String get() = str(S.desktop_tasks_tool_label)
    override val icon = ZillitToolIcons.Tasks
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1240.dp, 820.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(viewModel) { viewModel.onEvent(TasksEvent.Load) }
        TasksScreen(state = state, onEvent = viewModel::onEvent, mentionable = viewModel::mentionablePeople)
    }
}

const val TASKS_PATH = "/film-tools/tasks"
