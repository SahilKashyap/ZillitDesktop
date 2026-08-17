package com.zillit.desktop.feature.sides.ui

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
 * Sides as a workspace tool, at the web's path (`/film-tools/sides`).
 */
class SidesToolProvider(
    private val viewModel: SidesViewModel,
    /** Shows a PDF picker; a null answer means "cancelled". */
    private val onPickPdf: (onPicked: (Pair<String, ByteArray>?) -> Unit) -> Unit,
    /** Opens a signed download URL in the system browser (https only). */
    private val onOpenUrl: (String) -> Unit,
) : ToolProvider {

    override val path: String = SIDES_PATH
    override val title: String = "Sides"
    override val icon = ZillitToolIcons.ScriptNote
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
                    is SidesEffect.Notice -> notice = effect.message
                    SidesEffect.PickScriptFile -> onPickPdf { picked ->
                        if (picked != null) {
                            viewModel.onEvent(SidesEvent.ScriptPicked(picked.first, picked.second))
                        }
                    }
                    is SidesEffect.OpenUrl -> onOpenUrl(effect.url)
                }
            }
        }

        SidesScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }
}

const val SIDES_PATH = "/film-tools/sides"
