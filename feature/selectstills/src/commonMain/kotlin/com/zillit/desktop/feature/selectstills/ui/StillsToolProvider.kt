package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The Select Stills tool, at the web's path (`/film-tools/still-kills`).
 *
 * The pages are the tool's own top bar, not routes: the web nests
 * `/photos`, `/review`, `/upload`, `/cast` and `/settings`, but nothing
 * outside the tool links to one, so a single window owns all five.
 *
 * Opens maximised, as the web's `toolRegistry` row does — a gallery of stills
 * in a small window is unusable.
 */
class StillsToolProvider(
    private val viewModel: StillsViewModel,
    private val images: StillsImageSource,
    /** Hands a signed link to the OS, for "Download original". */
    private val onDownload: (url: String, name: String) -> Unit,
) : ToolProvider {

    override val path: String = STILLS_PATH
    override val title: String get() = str(S.desktop_stk_tool_label)
    /**
     * A picture frame, not [ZillitIcons.Camera] — that glyph is a camcorder
     * and means video in this app. The web's tile uses Ant's still-camera
     * outline, and a frame is the nearest thing here that reads as a still.
     */
    override val icon = ZillitIcons.Photo
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1320.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(viewModel) { viewModel.onEvent(StillsEvent.Load) }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is StillsEffect.Download -> onDownload(effect.url, effect.name)
                }
            }
        }
        CompositionLocalProvider(LocalStillsImages provides images) {
            StillsScreen(state = state, onEvent = viewModel::onEvent, queue = viewModel.queue)
        }
    }
}

const val STILLS_PATH = "/film-tools/still-kills"
