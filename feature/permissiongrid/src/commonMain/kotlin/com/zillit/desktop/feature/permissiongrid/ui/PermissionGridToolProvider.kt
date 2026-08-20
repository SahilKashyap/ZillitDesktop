package com.zillit.desktop.feature.permissiongrid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer

const val PERMISSION_GRID_PATH = "/film-tools/permission-grid"

/**
 * The rights grid as a workspace window.
 *
 * [viewer] is a lambda, not a value: the registry is built once per graph,
 * before any production is open, so the rights have to be resolved when the
 * window is first shown rather than captured at construction.
 */
class PermissionGridToolProvider(
    private val viewModel: PermissionGridViewModel,
    private val viewer: () -> PermissionGridViewer,
) : ToolProvider {

    override val path: String = PERMISSION_GRID_PATH
    override val title: String = "Viewing & Posting Rights Grid"
    override val icon = ZillitToolIcons.PostingRights

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.onEvent(PermissionGridEvent.Start(viewer()))
        }

        PermissionGridScreen(state = state, onEvent = viewModel::onEvent)
    }
}
