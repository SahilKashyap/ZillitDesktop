package com.zillit.desktop.feature.productionreport.ui

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
import com.zillit.desktop.feature.productionreport.domain.ReportKind

/**
 * Production Report — or the AD / Wrap report, which share the engine — as a
 * workspace tool at the tile's own path (`/film-tools/production-report`,
 * `/film-tools/ad-report`, `/film-tools/wrap-report`).
 */
class ProductionReportToolProvider(
    private val viewModel: ReportViewModel,
) : ToolProvider {

    private val kind: ReportKind get() = viewModel.kind

    override val path: String = viewModel.kind.path
    override val title: String = viewModel.kind.title
    override val icon = when (viewModel.kind) {
        ReportKind.Ad -> ZillitToolIcons.AdDashboard
        else -> ZillitToolIcons.ProductionReport
    }
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
                    is ReportEffect.Notice -> notice = effect.message
                }
            }
        }

        ProductionReportScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }
}

const val PRODUCTION_REPORT_PATH = "/film-tools/production-report"
const val AD_REPORT_PATH = "/film-tools/ad-report"
const val WRAP_REPORT_PATH = "/film-tools/wrap-report"
