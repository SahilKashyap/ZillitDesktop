package com.zillit.desktop.feature.pagedistribution.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * One of the three distribution tools as a workspace tool. Three instances,
 * one engine — the path and title come from the tool.
 */
class DistributionToolProvider(
    private val viewModel: DistributionViewModel,
    override val path: String,
    override val title: String,
    override val icon: ImageVector,
    /** Shows a PDF picker; a null answer means "cancelled". */
    private val onPickPdf: (onPicked: (Pair<String, ByteArray>?) -> Unit) -> Unit,
    /** A user id shown as "Name (Designation)". */
    private val resolveUser: (String) -> String?,
) : ToolProvider {

    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DistributionEffect.Notice -> notice = effect.text
                    is DistributionEffect.PickPdf -> onPickPdf { picked ->
                        if (picked != null) {
                            viewModel.onEvent(DistributionEvent.PdfPicked(picked.first, picked.second, effect.replaces))
                        }
                    }
                }
            }
        }

        DistributionScreen(state = state, onEvent = viewModel::onEvent, resolveUser = resolveUser)
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }

    companion object {
        const val SCHEDULE_PATH = "/film-tools/schedule-distribution"
        const val SCRIPT_PATH = "/film-tools/script-distribution"
        const val DOD_PATH = "/film-tools/dod"
    }
}
