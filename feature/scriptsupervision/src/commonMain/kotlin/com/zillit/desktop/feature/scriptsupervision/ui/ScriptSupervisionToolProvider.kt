package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Continuity Script Supervision as a workspace tool, at the web's own path.
 *
 * It hosts its own routes: the side menu is the tool's, not the workspace's,
 * as it is on the web — where this module opens as a tool window too since
 * 2026-10-23.
 */
class ScriptSupervisionToolProvider(
    private val viewModel: ScriptSupervisionViewModel,
) : ToolProvider {

    override val path: String = SCRIPT_SUPERVISION_PATH
    override val title: String get() = toolTitle()
    override val icon = ZillitToolIcons.Script
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 880.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        LaunchedEffect(Unit) {
            navigator.setTitle(title)
            viewModel.start()
        }
        ScriptSupervisionShell(
            viewModel = viewModel,
            // The web's Tools button is a route; here it is the Film Tools
            // window, opened beside this one rather than over it.
            onOpenTools = { navigator.openInNewWindow(WorkspaceRoute.Tool(FILM_TOOLS_PATH)) },
        )
    }

    companion object {
        const val SCRIPT_SUPERVISION_PATH = "/film-tools/script-supervision"

        /** The Film Tools grid, where the tool's Tools button goes. */
        const val FILM_TOOLS_PATH = "/film-tools"
    }
}
