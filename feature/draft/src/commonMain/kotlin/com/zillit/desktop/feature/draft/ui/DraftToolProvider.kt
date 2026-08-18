package com.zillit.desktop.feature.draft.ui

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
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** Zillit Draft in the workspace — a tool like any other, at [DRAFT_PATH]. */
class DraftToolProvider(private val viewModel: DraftViewModel) : ToolProvider {

    override val path: String = DRAFT_PATH
    override val title: String = "Zillit Draft"
    override val icon = ZillitIcons.Edit
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1360.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DraftEffect.Notice -> notice = effect.text
                }
            }
        }
        LaunchedEffect(state.open?.screenplay?.title) {
            navigator.setTitle(state.open?.let { "Draft · ${it.screenplay.title.ifBlank { "Untitled" }}" }
                ?: "Zillit Draft")
        }
        LaunchedEffect(state.open?.dirty) { navigator.setDirty(state.open?.dirty == true) }

        DraftScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = state.error ?: notice, onDismiss = {
            notice = null
            viewModel.onEvent(DraftEvent.DismissError)
        })
    }
}

const val DRAFT_PATH = "/film-tools/draft"
