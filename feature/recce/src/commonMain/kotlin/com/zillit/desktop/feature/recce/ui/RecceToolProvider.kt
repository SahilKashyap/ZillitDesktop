package com.zillit.desktop.feature.recce.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** The recce tool as a workspace tool, at the web's path (`/film-tools/recce`). */
class RecceToolProvider(
    private val viewModel: RecceViewModel,
    /** Opens a maps / what3words URL in the system browser (https only). */
    private val onOpenUrl: (String) -> Unit,
) : ToolProvider {

    override val path: String = RECCE_PATH
    override val title: String = "Recce"
    override val icon = ZillitToolIcons.Location
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var toast by remember { mutableStateOf<Pair<String, ZillitToastTone>?>(null) }
        var scrollToTop by remember { mutableIntStateOf(0) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is RecceEffect.Notice ->
                        toast = effect.text to if (effect.success) ZillitToastTone.Success else ZillitToastTone.Danger
                    is RecceEffect.OpenUrl -> onOpenUrl(effect.url)
                    RecceEffect.ScrollToTop -> scrollToTop++
                }
            }
        }

        // The window follows the page: the recce's title on its tab, and the
        // unsaved-edits mark while the form is dirty.
        val windowTitle = when (val page = state.route) {
            ReccePage.Index -> title
            is ReccePage.Detail -> state.selected?.title?.takeIf { it.isNotBlank() }?.let { "$it · Recce" } ?: title
            is ReccePage.Form -> if (page.id == null) "Create Recce" else "Edit Recce"
        }
        LaunchedEffect(windowTitle) { navigator.setTitle(windowTitle) }
        val dirty = state.editor?.dirty == true
        LaunchedEffect(dirty) { navigator.setDirty(dirty) }

        RecceScreen(state = state, onEvent = viewModel::onEvent, scrollToTop = scrollToTop)
        ZillitToast(
            message = toast?.first,
            onDismiss = { toast = null },
            tone = toast?.second ?: ZillitToastTone.Success,
        )
    }

    companion object {
        const val RECCE_PATH = "/film-tools/recce"
    }
}
