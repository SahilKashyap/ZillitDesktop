package com.zillit.desktop.feature.budgetbuilder.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Budget Builder as a workspace tool.
 *
 * The path matches the web's (`/film-tools/budget-builder`) so the tools
 * grid, badge routing and deep links agree across clients.
 */
class BudgetBuilderToolProvider(
    private val viewModel: BudgetBuilderViewModel,
    /**
     * Launches the embedded application. Injected because the launch is the
     * host's act — a gateway, a Chromium window — and this module has
     * neither. [onProblem] reports a launch that could not happen, in words
     * fit for the toast.
     */
    private val onLaunch: (onProblem: (String) -> Unit) -> Unit,
) : ToolProvider {

    override val path: String = BUDGET_BUILDER_PATH
    override val title: String get() = str(S.desktop_bb_title)
    override val icon = ZillitIcons.BarChart

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // Held here rather than in state so a failure that has been read does
        // not reappear when the window is switched away from and back.
        var problem by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    BudgetBuilderEffect.Launch -> onLaunch { reason -> problem = reason }
                }
            }
        }

        BudgetBuilderScreen(state = state, onEvent = viewModel::onEvent)

        ZillitErrorToast(message = problem, onDismiss = { problem = null })
    }
}

const val BUDGET_BUILDER_PATH = "/film-tools/budget-builder"
