package com.zillit.desktop.feature.distribution.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** The Distribution List as a workspace tool, at the catalogue's route. */
class DistributionToolProvider(
    private val viewModel: DistributionViewModel,
    /** "MUST READ" — the documentation site, in the system browser. */
    private val onOpenUrl: (String) -> Unit = {},
    /**
     * The admin banner's "Click Here": the department listing-order editor.
     * The web mounts `ChangePriorityList` on this page; the desktop's editor
     * lives in the Crew List tool, so the host opens it there.
     */
    private val onOpenListingOrder: ((WindowNavigator) -> Unit)? = null,
    /** A profile picture by user id; null draws initials. */
    private val faces: suspend (String) -> ImageBitmap? = { null },
) : ToolProvider {

    override val path: String = DISTRIBUTION_PATH
    override val title: String = "Distribution List"
    override val icon = ZillitToolIcons.IcDistribution
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1200.dp, 800.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<DistributionEffect.Notice?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DistributionEffect.Notice -> notice = effect
                    is DistributionEffect.OpenUrl -> onOpenUrl(effect.url)
                    DistributionEffect.OpenListingOrder -> onOpenListingOrder?.invoke(navigator)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            DistributionScreen(state = state, onEvent = viewModel::onEvent, faces = faces)
            val shown = notice
            ZillitToast(
                message = shown?.text ?: state.error?.takeIf { state.users.isNotEmpty() },
                tone = if (shown?.success == true) ZillitToastTone.Success else ZillitToastTone.Danger,
                onDismiss = {
                    notice = null
                    viewModel.onEvent(DistributionEvent.DismissError)
                },
            )
        }
    }

    companion object {
        const val DISTRIBUTION_PATH = "/film-tools/distribution"
    }
}
