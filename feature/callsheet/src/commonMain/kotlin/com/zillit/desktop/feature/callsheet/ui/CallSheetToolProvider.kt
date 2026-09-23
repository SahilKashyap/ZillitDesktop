package com.zillit.desktop.feature.callsheet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Call Sheet Creation as a workspace tool.
 *
 * The path matches the web's (`/film-tools/call-sheet`) so the tools grid,
 * badges and deep links agree across clients.
 */
class CallSheetToolProvider(
    private val viewModel: CallSheetViewModel,
    /** A crew member's photo for the sheet's faces; null draws initials. */
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
) : ToolProvider {

    override val path: String = CALL_SHEET_PATH
    override val title: String = "Call Sheet"
    override val icon = ZillitToolIcons.IcContinuity
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 880.dp)

    /**
     * The window title by rights — "Call Sheet Creation" for posting users,
     * "Drafts Call Sheet" for everyone else — once the rights have answered;
     * the plain name until then.
     */
    override fun titleFor(route: WorkspaceRoute): String {
        val state = viewModel.state.value
        return if (state.viewer.ready) state.toolTitle else title
    }

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var toast by remember { mutableStateOf<SheetEffect.Toast?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SheetEffect.Toast -> toast = effect
                }
            }
        }

        CallSheetScreen(state = state, onEvent = viewModel::onEvent, loadAvatar = loadAvatar)
        ZillitToast(
            message = toast?.message,
            onDismiss = { toast = null },
            tone = if (toast?.isError == true) ZillitToastTone.Danger else ZillitToastTone.Success,
        )
    }
}

const val CALL_SHEET_PATH = "/film-tools/call-sheet"
