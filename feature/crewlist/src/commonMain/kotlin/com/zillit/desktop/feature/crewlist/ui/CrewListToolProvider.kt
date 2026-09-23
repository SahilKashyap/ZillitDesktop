package com.zillit.desktop.feature.crewlist.ui

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

/** The Crew List as a workspace tool, at the catalogue's route. */
class CrewListToolProvider(
    private val viewModel: CrewListViewModel,
    /**
     * The Crew List widget's shape: the roster, its search and the PDF only.
     * Both copies share one [CrewListViewModel] when the widget shows the open
     * production.
     */
    private val compact: Boolean = false,
    /** Opens the Crew List widget — the tool's own way to it, as Drive has. */
    private val onOpenWidget: (() -> Unit)? = null,
    /**
     * The app's parts the screen borrows, built per window: the call, chat and
     * mail actions need that window's navigator.
     */
    private val slots: (WindowNavigator) -> CrewListSlots = { CrewListSlots() },
) : ToolProvider {

    override val path: String = CREW_LIST_PATH
    override val title: String get() = str(S.generate_crew_list)
    override val icon = ZillitToolIcons.CrewList
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1200.dp, 800.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val prompting by viewModel.framePrompting.collectAsState()
        var toast by remember { mutableStateOf<CrewListEffect.Toast?>(null) }
        val windowSlots = remember(navigator) { slots(navigator) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is CrewListEffect.Toast -> toast = effect
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            CrewListScreen(
                state = state,
                visibleUnits = viewModel::visibleUnits,
                onEvent = viewModel::onEvent,
                compact = compact,
                onOpenWidget = onOpenWidget,
                slots = if (compact) CrewListSlots(faces = windowSlots.faces) else windowSlots,
                // The toast sits at the foot of the tool, where the canvas reaches.
                canvasCovered = prompting || toast != null || state.error != null,
            )
            val shown = toast
            ZillitToast(
                message = shown?.text ?: state.error,
                tone = if (shown == null || shown.tone == CrewListEffect.Tone.Error) {
                    ZillitToastTone.Danger
                } else {
                    ZillitToastTone.Success
                },
                onDismiss = {
                    if (shown != null) toast = null else viewModel.onEvent(CrewListEvent.Sheet.DismissError)
                },
            )
        }
    }

    companion object {
        const val CREW_LIST_PATH = "/film-tools/generate-crew-list"
    }
}
