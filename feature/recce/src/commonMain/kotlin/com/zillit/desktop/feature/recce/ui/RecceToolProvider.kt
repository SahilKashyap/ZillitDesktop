package com.zillit.desktop.feature.recce.ui

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

/** The recce tool as a workspace tool, at the web's path (`/film-tools/recce`). */
class RecceToolProvider(
    private val viewModel: RecceViewModel,
    /** Opens a maps / what3words URL in the system browser (https only). */
    private val onOpenUrl: (String) -> Unit,
) : ToolProvider {

    override val path: String = RECCE_PATH
    override val title: String = "Recce"
    override val icon = ZillitToolIcons.Location
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
                    is RecceEffect.Notice -> notice = effect.text
                    is RecceEffect.OpenUrl -> onOpenUrl(effect.url)
                }
            }
        }

        RecceScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }

    companion object {
        const val RECCE_PATH = "/film-tools/recce"
    }
}
