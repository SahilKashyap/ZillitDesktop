package com.zillit.desktop.feature.distribution.ui

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

/** The Distribution List as a workspace tool, at the catalogue's route. */
class DistributionToolProvider(
    private val viewModel: DistributionViewModel,
) : ToolProvider {

    override val path: String = DISTRIBUTION_PATH
    override val title: String = "Distribution List"
    override val icon = ZillitToolIcons.IcDistribution
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1200.dp, 800.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DistributionEffect.Notice -> notice = effect.text
                }
            }
        }

        DistributionScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = notice ?: state.error, onDismiss = {
            notice = null
            viewModel.onEvent(DistributionEvent.DismissError)
        })
    }

    companion object {
        const val DISTRIBUTION_PATH = "/film-tools/distribution"
    }
}
