package com.zillit.desktop.feature.crewdood.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Crew DOOD as a workspace tool, at the catalogue's route.
 *
 * A Film Tools tool, not an Account Hub page: it opens from the tools grid and
 * its back arrow goes wherever it was opened from. Maximized, like every other
 * wide chart — a 1,100-day grid in a small window is unreadable.
 */
class CrewDoodToolProvider(
    private val viewModel: CrewDoodViewModel,
) : ToolProvider {

    override val path: String = CREW_DOOD_PATH
    override val title: String get() = str(S.desktop_cdood_label)
    override val icon = ZillitToolIcons.CrewList
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1360.dp, 840.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val controller = remember { DoodGridController() }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    // The web's "Back to tools": wherever the chart was opened from.
                    CrewDoodEffect.Leave -> if (navigator.canGoBack) navigator.back() else navigator.close()
                    is CrewDoodEffect.JumpTo -> controller.jumpTo(effect.band)
                    CrewDoodEffect.ResetScroll -> controller.resetScroll()
                }
            }
        }

        CrewDoodScreen(state = state, onEvent = viewModel::onEvent, controller = controller)
    }

    companion object {
        /** The web's route — `/film-tools/crew-dood`. */
        const val CREW_DOOD_PATH = "/film-tools/crew-dood"
    }
}
