package com.zillit.desktop.feature.budget.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.budget.domain.BudgetDocument

/**
 * One budget tile.
 *
 * Registered twice with two view models — `main_budget_tool` at
 * [MAIN_BUDGET_PATH] and `department_budget_tool` at [DEPARTMENT_BUDGET_PATH]
 * — exactly as the web mounts `FullBudget` and `DepartmentBudget` on two
 * routes over one shared body (`toolRegistry.js:177-178`).
 */
class BudgetToolProvider(
    private val viewModel: BudgetViewModel,
    override val path: String,
    /** Hands a budget's file to the host to show or save. */
    private val onOpenFile: (BudgetDocument, Boolean) -> Unit = { _, _ -> },
    private val seams: BudgetScreenSeams = BudgetScreenSeams(),
) : ToolProvider {

    override val title: String get() = viewModel.mode.title
    override val icon = ZillitToolIcons.Budget
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1180.dp, 820.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // Rights come with the production, which is open by now — so the load
        // belongs here rather than in the constructor.
        LaunchedEffect(viewModel) { viewModel.onEvent(BudgetEvent.Load) }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is BudgetEffect.Open -> onOpenFile(effect.document, effect.save)
                }
            }
        }

        BudgetScreen(state = state, onEvent = viewModel::onEvent, seams = seams)
    }
}

const val MAIN_BUDGET_PATH = "/film-tools/main-budget"
const val DEPARTMENT_BUDGET_PATH = "/film-tools/department-budget"
