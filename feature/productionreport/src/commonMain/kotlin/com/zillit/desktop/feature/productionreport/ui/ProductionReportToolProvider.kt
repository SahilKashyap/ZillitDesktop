package com.zillit.desktop.feature.productionreport.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.reportToolName

/**
 * Production Report — or the AD / Wrap report, which share the engine — as a
 * workspace tool at the tile's own path (`/film-tools/production-report`,
 * `/film-tools/ad-report`, `/film-tools/wrap-report`).
 */
class ProductionReportToolProvider(
    private val viewModel: ReportViewModel,
    /** A crew member's photo for the report's faces; null draws initials. */
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
    /**
     * The tool's unit chat for the Chat workspace, drawn with this window's
     * route and navigator; null leaves the manager alone.
     */
    private val chat: (@Composable (route: WorkspaceRoute, navigator: WindowNavigator) -> Unit)? = null,
) : ToolProvider {

    override val path: String = viewModel.kind.path
    override val title: String get() = viewModel.kind.title
    override val icon = when (viewModel.kind) {
        ReportKind.Ad -> ZillitToolIcons.AdDashboard
        else -> ZillitToolIcons.ProductionReport
    }
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 880.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var toast by remember { mutableStateOf<ReportEffect.Toast?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        // Tool name by rights (BE, Sep 2026): the window title follows the in-tool header —
        // "Production Report Creation" for posting users, "Production Report" for viewers.
        val windowTitle = if (viewModel.kind == ReportKind.Production) reportToolName(state.isPoster) else title
        LaunchedEffect(navigator, windowTitle) { navigator.setTitle(windowTitle) }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ReportEffect.Toast -> toast = effect
                }
            }
        }

        val chatPane: (@Composable () -> Unit)? = chat?.let { host -> @Composable { host(route, navigator) } }
        ProductionReportScreen(state = state, onEvent = viewModel::onEvent, chat = chatPane, loadAvatar = loadAvatar)
        ZillitToast(
            message = toast?.message,
            onDismiss = { toast = null },
            tone = if (toast?.isError == true) ZillitToastTone.Danger else ZillitToastTone.Success,
        )
    }
}

const val PRODUCTION_REPORT_PATH = "/film-tools/production-report"
const val AD_REPORT_PATH = "/film-tools/ad-report"
const val WRAP_REPORT_PATH = "/film-tools/wrap-report"
