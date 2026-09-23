package com.zillit.desktop.feature.transportation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** Transportation as a workspace tool, at the web's path (`/film-tools/transportation`). */
class TransportToolProvider(
    private val viewModel: TransportViewModel,
    private val slots: TransportSlots = TransportSlots(),
    /** Opens a URL in the browser — a passenger's address, the driver's last position. */
    private val openLink: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = TRANSPORT_PATH
    override val title: String get() = str(S.txt_transportation)
    override val icon = ZillitToolIcons.Transportation
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is TransportEffect.Notice -> notice = effect.text to effect.success
                    is TransportEffect.OpenLink -> openLink(effect.url)
                }
            }
        }

        CompositionLocalProvider(LocalTransportSlots provides slots) {
            TransportScreen(state = state, onEvent = viewModel::onEvent, openLink = openLink)
        }
        ZillitToast(
            message = notice?.first,
            onDismiss = { notice = null },
            tone = if (notice?.second == false) ZillitToastTone.Danger else ZillitToastTone.Success,
        )
    }

    companion object {
        const val TRANSPORT_PATH = "/film-tools/transportation"
    }
}
