package com.zillit.desktop.feature.timecard.ui

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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** Timecards as a workspace window. */
class TimecardToolProvider(
    private val viewModel: TimecardViewModel,
) : ToolProvider {

    override val path: String = TIMECARD_PATH
    override val title: String get() = str(S.desktop_timecards)
    override val icon = ZillitToolIcons.Timecard
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is TimecardEffect.Failed -> failure = effect.message
                }
            }
        }
        LaunchedEffect(state.destination) {
            navigator.setTitle(str(S.desktop_timecards_title_with_page, state.destination.label))
        }

        TimecardScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val TIMECARD_PATH = "/film-tools/timecard"
