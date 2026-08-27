package com.zillit.desktop.feature.sos.ui

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

/**
 * SOS as a workspace tool.
 *
 * A window rather than a maximised sub-application: the page is one column of
 * cards, and an alarm the crew may need in a hurry should be able to sit open
 * beside whatever they were doing.
 */
class SosToolProvider(
    private val viewModel: SosViewModel,
    /**
     * Opens an alert's map link in the system browser. The link is a
     * `https://maps…` URL the backend put in the alert's message elements —
     * this module never builds one.
     */
    private val onOpenLink: (String) -> Unit,
    /**
     * Rings whoever raised an alert. Null on a host with no calling, where
     * the buttons are simply not drawn — a call control that does nothing is
     * worse than none, on this screen most of all.
     */
    private val onCall: ((userId: String, deviceId: String, name: String, video: Boolean) -> Unit)? = null,
) : ToolProvider {

    override val path: String = SOS_PATH
    override val title: String = "SOS"
    override val icon = ZillitIcons.Siren
    override val openMode: OpenMode = OpenMode.Window
    override val defaultSize: DpSize = DpSize(880.dp, 820.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SosEffect.Notice -> notice = effect.message
                    is SosEffect.OpenLink -> onOpenLink(effect.url)
                    is SosEffect.PlaceCall ->
                        onCall?.invoke(effect.userId, effect.deviceId, effect.displayName, effect.video)
                }
            }
        }

        SosScreen(state = state, onEvent = viewModel::onEvent, mayCall = onCall != null)
        ZillitToast(message = notice, onDismiss = { notice = null }, tone = ZillitToastTone.Success)
    }
}

/** The web serves the SOS page at `/sos` (`components/Layout.jsx:76`); the desktop keeps the key. */
const val SOS_PATH = "/sos"
