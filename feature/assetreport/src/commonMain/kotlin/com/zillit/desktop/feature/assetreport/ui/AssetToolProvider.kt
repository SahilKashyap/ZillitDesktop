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
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
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
        var notice by remember { mutableStateOf<AssetEffect.Notice?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AssetEffect.Notice -> notice = effect
                    // The web's "Back to Film Tools": wherever the register was opened from.
                    AssetEffect.Leave -> if (navigator.canGoBack) navigator.back() else navigator.close()
                }
            }
        }

        AssetScreen(state = state, onEvent = viewModel::onEvent, media = viewModel)
        val shown = notice
        ZillitToast(
            message = shown?.text ?: state.error,
            tone = if (shown?.success == true) ZillitToastTone.Success else ZillitToastTone.Danger,
            onDismiss = {
                if (shown != null) notice = null else viewModel.onEvent(AssetEvent.DismissError)
            },
        )
    }

    companion object {
        const val ASSET_PATH = "/film-tools/asset-report"
    }
}
