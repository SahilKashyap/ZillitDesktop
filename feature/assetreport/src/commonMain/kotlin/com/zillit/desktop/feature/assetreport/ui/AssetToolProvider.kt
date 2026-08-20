package com.zillit.desktop.feature.assetreport.ui

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

/** The Asset Register as a workspace tool, at the catalogue's route. */
class AssetToolProvider(
    private val viewModel: AssetViewModel,
) : ToolProvider {

    override val path: String = ASSET_PATH
    override val title: String = "Asset Register"
    override val icon = ZillitToolIcons.IcAssets
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1280.dp, 820.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AssetEffect.Notice -> notice = effect.text
                }
            }
        }

        AssetScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = notice ?: state.error, onDismiss = {
            notice = null
            viewModel.onEvent(AssetEvent.DismissError)
        })
    }

    companion object {
        const val ASSET_PATH = "/film-tools/asset-report"
    }
}
