package com.zillit.desktop.feature.budgetbuilder.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Budget Builder as a workspace tool.
 *
 * The path matches the web's (`/film-tools/budget-builder`) so the tools
 * grid, badge routing and deep links agree across clients.
 *
 * [OpenMode.Maximized] for the web's reason, said in its own words in
 * `toolRegistry.js` and again in `BudgetBuilderEmbed.jsx`: the application
 * carries its own left nav, so inside ordinary chrome "the user got two
 * sidebars and ~600px of chrome before the first number". It is a
 * whole-screen tool, and the way back out is its own.
 */
class BudgetBuilderToolProvider(
    private val viewModel: BudgetBuilderViewModel,
    /**
     * The embedded application. Injected because the browser is the host's to
     * own — a loopback gateway and a Chromium surface, neither of which this
     * module has.
     */
    private val application: @Composable () -> Unit,
) : ToolProvider {

    override val path: String = BUDGET_BUILDER_PATH
    override val title: String get() = str(S.desktop_bb_title)
    override val icon = ZillitIcons.BarChart
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(WINDOW_WIDTH.dp, WINDOW_HEIGHT.dp)

    /**
     * True so the host hands the whole window over: the application does its
     * own routing inside the browser, and nothing in this path's subtree is
     * ours to resolve.
     */
    override val hostsOwnRoutes: Boolean = true

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    // The web navigates back to the tools grid; here the grid
                    // is a window of its own that the tool was opened from, so
                    // leaving is closing this one.
                    BudgetBuilderEffect.Exit -> navigator.close()
                }
            }
        }

        BudgetBuilderScreen(state = state, application = application)
    }

    private companion object {
        /** Restored at this size when someone un-maximizes the window. */
        const val WINDOW_WIDTH = 1440
        const val WINDOW_HEIGHT = 900
    }
}

const val BUDGET_BUILDER_PATH = "/film-tools/budget-builder"
