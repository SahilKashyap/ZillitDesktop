package com.zillit.desktop.feature.transporthub.ui

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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The Transportation Hub as a workspace tool, at the web's own path
 * (`/film-tools/transportation-hub`).
 *
 * A second provider beside `feature:transportation`, not a replacement: the
 * web's tile has pointed here since April 2026, and the older page stays
 * reachable at its own path while the rest of the hub is ported.
 */
class HubToolProvider(
    private val viewModel: HubViewModel,
    /** Opens a URL in the browser — a driver's last reported position. */
    private val openLink: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = HUB_PATH
    override val title: String get() = str(S.desktop_hub_title)
    override val icon = ZillitToolIcons.Transportation
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 880.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is HubEffect.Notice -> notice = effect.text to effect.success
                    is HubEffect.OpenLink -> openLink(effect.url)
                }
            }
        }

        HubScreen(
            state = state,
            onEvent = viewModel::onEvent,
            openLink = openLink,
            // Its own window: the two tools are separate providers, and
            // replacing this one's content would strand the hub's open form.
            openClassicPage = { navigator.openInNewWindow(WorkspaceRoute.Tool(CLASSIC_PATH)) },
        )
        ZillitToast(
            message = notice?.first,
            onDismiss = { notice = null },
            tone = if (notice?.second == false) ZillitToastTone.Danger else ZillitToastTone.Success,
        )
    }

    companion object {
        const val HUB_PATH = "/film-tools/transportation-hub"

        /** The older Transportation page, still registered at its own path. */
        const val CLASSIC_PATH = "/film-tools/transportation"
    }
}
