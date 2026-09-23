package com.zillit.desktop.feature.costreport.ui

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
import com.zillit.desktop.feature.costreport.domain.CostReportTab
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/** Cost Report as a workspace tool, at the web's path (`/film-tools/cost-report`). */
class CostReportToolProvider(
    private val viewModel: CostReportViewModel,
    /** "Name · Designation" for a user id, or null when unknown. */
    private val resolveUser: (String) -> String?,
) : ToolProvider {

    override val path: String = COST_REPORT_PATH
    override val title: String get() = str(S.cr_title)
    override val icon = ZillitIcons.BarChart
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        // `/current` and `/posted` open their tab, as the web's tab URLs do.
        LaunchedEffect(route.path) {
            when (route.path.removePrefix(COST_REPORT_PATH).trim('/').substringBefore('/')) {
                "current", "live" -> viewModel.onEvent(CostReportEvent.SelectTab(CostReportTab.Current))
                "posted" -> viewModel.onEvent(CostReportEvent.SelectTab(CostReportTab.Posted))
            }
        }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is CostReportEffect.Notice -> notice = effect.text
                    CostReportEffect.OpenAnalytics ->
                        navigator.navigate(WorkspaceRoute.Tool("$COST_REPORT_PATH/$ANALYTICS_SEGMENT"))
                }
            }
        }

        CostReportScreen(state = state, onEvent = viewModel::onEvent, resolveUser = resolveUser)
        ZillitToast(message = notice, onDismiss = { notice = null }, tone = ZillitToastTone.Success)
    }

    companion object {
        const val COST_REPORT_PATH = "/film-tools/cost-report"
        const val ANALYTICS_SEGMENT = "analytics"
    }
}
