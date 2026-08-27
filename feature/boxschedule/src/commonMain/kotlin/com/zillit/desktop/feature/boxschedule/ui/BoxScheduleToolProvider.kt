package com.zillit.desktop.feature.boxschedule.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Box Schedule as a workspace tool.
 *
 * Registered at BOTH the tile's route and the web's mount path: the web
 * serves `boxScheduleV2` at `/film-tools/pre-production`, which the legacy
 * `pre_production_tool` tile also opens, while the `box_schedule_tool` tile
 * points at `/film-tools/box-schedule`. Rights are `box_schedule_tool` only.
 */
class BoxScheduleToolProvider(
    private val viewModel: BoxScheduleViewModel,
    override val path: String = BOX_SCHEDULE_PATH,
    /**
     * Joins an event's call. Null on a host with no calling, where the Join
     * button is not drawn.
     */
    private val onJoinCall: ((roomId: String, title: String, video: Boolean) -> Unit)? = null,
) : ToolProvider {

    override val title: String = "Box Schedule"
    override val icon = ZillitToolIcons.PreProduction
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is BoxScheduleEffect.Notice -> notice = effect.message
                    is BoxScheduleEffect.JoinCall ->
                        onJoinCall?.invoke(effect.roomId, effect.title, effect.video)
                }
            }
        }

        BoxScheduleScreen(state = state, onEvent = viewModel::onEvent, mayCall = onJoinCall != null)
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }
}

const val BOX_SCHEDULE_PATH = "/film-tools/box-schedule"
const val PRE_PRODUCTION_PATH = "/film-tools/pre-production"
