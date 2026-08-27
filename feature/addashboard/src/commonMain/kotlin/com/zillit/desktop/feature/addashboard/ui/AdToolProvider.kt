package com.zillit.desktop.feature.addashboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** The AD dashboard as a workspace tool, at the web's path. */
class AdToolProvider(private val viewModel: AdViewModel) : ToolProvider {

    override val path: String = AD_DASHBOARD_PATH
    override val title: String = "AD Dashboard"
    override val icon = ZillitIcons.Users
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(WIDTH.dp, HEIGHT.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AdEffect.Failed -> failure = effect.message
                }
            }
        }

        AdScreen(state = state, onEvent = viewModel::onEvent)

        ZillitToast(
            message = failure,
            onDismiss = { failure = null },
            tone = ZillitToastTone.Danger,
        )
    }

    companion object {
        const val AD_DASHBOARD_PATH = "/film-tools/ad-dashboard"
        private const val WIDTH = 1440
        private const val HEIGHT = 900
    }
}
