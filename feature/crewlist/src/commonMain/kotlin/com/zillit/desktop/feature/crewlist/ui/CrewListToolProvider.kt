package com.zillit.desktop.feature.crewlist.ui

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

/** The Crew List as a workspace tool, at the catalogue's route. */
class CrewListToolProvider(
    private val viewModel: CrewListViewModel,
    /**
     * The Crew List widget's shape: contact details under the name instead of
     * in their own columns. Both copies share this one [CrewListViewModel].
     */
    private val compact: Boolean = false,
) : ToolProvider {

    override val path: String = CREW_LIST_PATH
    override val title: String = "Crew List"
    override val icon = ZillitToolIcons.CrewList
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1200.dp, 800.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is CrewListEffect.Notice -> notice = effect.text
                }
            }
        }

        CrewListScreen(
            state = state,
            visibleUnits = viewModel::visibleUnits,
            onEvent = viewModel::onEvent,
            compact = compact,
        )
        ZillitErrorToast(message = notice ?: state.error, onDismiss = {
            notice = null
            viewModel.onEvent(CrewListEvent.DismissError)
        })
    }

    companion object {
        const val CREW_LIST_PATH = "/film-tools/generate-crew-list"
    }
}
