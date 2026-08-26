package com.zillit.desktop.feature.saportal.ui

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
 * The artiste portal as a workspace tool.
 *
 * Two catalogue entries open it — `sa_portal_tool` and
 * `supporting_artistes_extras_tool` — because the web and Android named the
 * same surface differently. [SA_PORTAL_PATH] and [EXTRAS_PATH] are the two
 * routes the catalogue already carries, and one provider is registered at
 * each rather than duplicating the tool.
 */
class SaPortalToolProvider(
    private val viewModel: SaPortalViewModel,
    override val path: String = SA_PORTAL_PATH,
) : ToolProvider {

    override val title: String = "My work"
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
                    is SaEffect.Failed -> failure = effect.message
                }
            }
        }

        SaPortalScreen(state = state, onEvent = viewModel::onEvent)

        ZillitToast(
            message = failure,
            onDismiss = { failure = null },
            tone = ZillitToastTone.Danger,
        )
    }

    companion object {
        /** The route the catalogue gives `sa_portal_tool`. */
        const val SA_PORTAL_PATH = "/film-tools/sa-portal"

        /** The route the catalogue gives `supporting_artistes_extras_tool`. */
        const val EXTRAS_PATH = "/film-tools/supporting-artistes"

        private const val WIDTH = 1280
        private const val HEIGHT = 900
    }
}
