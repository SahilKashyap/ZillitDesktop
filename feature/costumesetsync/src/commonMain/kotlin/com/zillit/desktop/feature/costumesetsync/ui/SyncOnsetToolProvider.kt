package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** Costumes & Set Sync as a workspace tool, at the web's path (`/film-tools/costume-set-sync`). */
class SyncOnsetToolProvider(
    private val viewModel: SyncOnsetViewModel,
    /** The window the tool is showing in, once up — what the app opens the mail window from for a share. */
    private val onWindow: (WindowNavigator) -> Unit = {},
    /** The window the tool was showing in, gone. */
    private val onWindowClosed: (WindowNavigator) -> Unit = {},
) : ToolProvider {

    override val path: String = SYNC_ONSET_PATH
    override val title: String get() = str(S.desktop_csync_tool_name)
    override val icon = ZillitToolIcons.Wardrobe
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        LaunchedEffect(Unit) { navigator.setTitle(title) }
        DisposableEffect(navigator) {
            onWindow(navigator)
            onDispose { onWindowClosed(navigator) }
        }
        SyncOnsetShell(viewModel)
    }

    companion object {
        const val SYNC_ONSET_PATH = "/film-tools/costume-set-sync"
    }
}
