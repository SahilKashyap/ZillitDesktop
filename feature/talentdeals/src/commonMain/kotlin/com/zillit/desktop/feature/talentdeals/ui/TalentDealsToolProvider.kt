package com.zillit.desktop.feature.talentdeals.ui

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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Talent Deals as a workspace tool, at the catalogue's route. The tool's own
 * faces (register, contract, form) are the view model's route, not window
 * routes — the window has one path, as Deal Memo's does.
 */
class TalentDealsToolProvider(private val viewModel: TalentDealsViewModel) : ToolProvider {

    override val path: String = TALENT_DEALS_PATH
    override val title: String get() = str(S.desktop_td_title)
    override val icon = ZillitIcons.File
    override val openMode: OpenMode = OpenMode.Window
    override val defaultSize: DpSize = DpSize(1180.dp, 780.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<TalentDealsEffect.Notice?>(null) }

        // Runs on every window open: the viewer is re-read and the face on
        // screen refreshed — the view model outlives production switches.
        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is TalentDealsEffect.Notice -> notice = effect
                    TalentDealsEffect.CloseTool -> navigator.close()
                }
            }
        }

        TalentDealsScreen(
            state = state,
            onEvent = viewModel::onEvent,
            signatureLoader = viewModel::signatureImage,
        )
        ZillitToast(
            message = notice?.text,
            tone = if (notice?.isError == false) ZillitToastTone.Success else ZillitToastTone.Danger,
            onDismiss = { notice = null },
        )
    }

    companion object {
        /** The web's `/film-tools/talent-deals`; the grid tile `talent_deals_tool` opens here. */
        const val TALENT_DEALS_PATH = "/film-tools/talent-deals"
    }
}
