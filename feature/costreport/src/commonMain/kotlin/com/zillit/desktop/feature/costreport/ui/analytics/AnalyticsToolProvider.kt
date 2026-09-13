package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The cost report's Analytics page at the web's own path
 * (`/film-tools/cost-report/analytics`), which both the worksheet and the crew
 * report open. It is its own provider so the longest-prefix match picks it
 * over the crew report's `/film-tools/cost-report`.
 */
class AnalyticsToolProvider(private val viewModel: AnalyticsViewModel) : ToolProvider {

    override val path: String = ANALYTICS_PATH
    override val title: String = "Cost Report Analytics"
    override val icon = ZillitIcons.BarChart
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val uriHandler = LocalUriHandler.current

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    // The web's `navigate(-1)`: back to whichever report opened the page.
                    AnalyticsEffect.Back -> if (navigator.canGoBack) navigator.back() else navigator.close()
                    is AnalyticsEffect.OpenLink -> openLink(effect.href, navigator) { uriHandler.openUri(it) }
                }
            }
        }
        LaunchedEffect(state.titleWord) { navigator.setTitle("${state.titleWord} Analytics") }

        AnalyticsScreen(state = state, onEvent = viewModel::onEvent)
    }

    /** An app path stays in the workspace; a web address opens in the browser. */
    private fun openLink(href: String, navigator: WindowNavigator, openUri: (String) -> Unit) {
        when {
            href.startsWith("/") -> navigator.navigate(WorkspaceRoute.Tool(href))
            href.startsWith("http://") || href.startsWith("https://") -> runCatching { openUri(href) }
        }
    }

    companion object {
        const val ANALYTICS_PATH = "/film-tools/cost-report/analytics"
    }
}
