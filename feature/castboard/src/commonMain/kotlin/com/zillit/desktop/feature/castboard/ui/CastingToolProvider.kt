package com.zillit.desktop.feature.castboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.castboard.domain.CastingMedia

/**
 * A cast board — Casting or Wardrobe, whichever its view model carries.
 *
 * Registered once per tile: the grid has a tile per tool, and whichever is
 * clicked, the board opens on the lists that viewer's rights allow.
 */
class CastingToolProvider(
    private val viewModel: CastingViewModel,
    override val path: String,
    private val loadPhoto: (suspend (CastingMedia) -> ImageBitmap?)? = null,
) : ToolProvider {

    override val title: String get() = viewModel.board.title
    override val icon =
        if (viewModel.board.showsScenes) ZillitToolIcons.Wardrobe else ZillitToolIcons.Casting
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1180.dp, 820.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) { viewModel.onEvent(CastingEvent.Load) }

        CastingScreen(
            state = state,
            onEvent = viewModel::onEvent,
            loadPhoto = loadPhoto,
            board = viewModel.board,
        )
    }
}

const val CASTING_PATH = "/film-tools/casting"
const val CASTING_MAIN_PATH = "/film-tools/casting-main"
const val CASTING_BACKGROUND_PATH = "/film-tools/casting-background"
const val WARDROBE_MAIN_PATH = "/film-tools/wardrobe-main"
const val WARDROBE_BACKGROUND_PATH = "/film-tools/wardrobe-background"
