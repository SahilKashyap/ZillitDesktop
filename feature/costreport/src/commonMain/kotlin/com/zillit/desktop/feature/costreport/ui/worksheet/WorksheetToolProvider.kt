package com.zillit.desktop.feature.costreport.ui.worksheet

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
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsToolProvider

/**
 * The accountant's Cost Report as a workspace tool, at the web's path
 * (`/film-tools/account-hub/cost-report`). The Account Hub's REPORTS row
 * opens it inside the hub's shell; its back arrow returns there.
 */
class WorksheetToolProvider(
    private val viewModel: WorksheetViewModel,
    /** "Name · Designation" for a user id, or null when unknown. */
    private val resolveUser: (String) -> String?,
) : ToolProvider {

    override val path: String = WORKSHEET_PATH
    override val title: String = "Cost Report"
    override val icon = ZillitIcons.BarChart
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is WorksheetEffect.Notice -> notice = effect.text to effect.error
                    // Inside the hub, closing the embed shows the hub's own
                    // area again — the web's back to `/film-tools/account-hub`.
                    // Tax Filing leaves the same way.
                    WorksheetEffect.Back -> navigator.close()
                    WorksheetEffect.OpenAnalytics -> navigator.navigate(WorkspaceRoute.Tool(ANALYTICS_PATH))
                }
            }
        }
        LaunchedEffect(state.pane) { navigator.setTitle("Cost Report · ${state.pane.label}") }

        WorksheetScreen(
            state = state,
            onEvent = viewModel::onEvent,
            nowMillis = viewModel.nowMillis,
            resolveUser = resolveUser,
        )
        ZillitToast(
            message = notice?.first,
            onDismiss = { notice = null },
            tone = if (notice?.second == true) ZillitToastTone.Danger else ZillitToastTone.Success,
        )
    }

    companion object {
        const val WORKSHEET_PATH = "/film-tools/account-hub/cost-report"

        /** The standalone Analytics page both cost-report surfaces open. */
        const val ANALYTICS_PATH = AnalyticsToolProvider.ANALYTICS_PATH
    }
}
