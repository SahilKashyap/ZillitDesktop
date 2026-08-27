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
 * The Budget tool.
 *
 * Registered twice — once per tile. `main_budget_tool` and
 * `department_budget_tool` are separate rights rows and separate tiles in the
 * grid, but one screen serves both, exactly as the web's `/budget` page does:
 * which sections it shows is a question of rights, not of which tile you came
 * through. The box-schedule tool takes the same two-paths-one-screen shape.
 */
class BudgetToolProvider(
    private val viewModel: BudgetViewModel,
    override val path: String,
    override val title: String = "Budget",
    /** Hands a budget's file to the host to show or save. */
    private val onOpenFile: (BudgetDocument, Boolean) -> Unit = { _, _ -> },
    /**
     * The conversation for whichever budget is selected — the chat tool's own
     * thread, scoped to this tool and department. The document on screen is
     * passed so the host can open the right room; null hides the pane.
     */
    private val conversation: (@Composable (BudgetDocument?, Boolean) -> Unit)? = null,
) : ToolProvider {

    override val icon = ZillitToolIcons.Account
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

        BudgetScreen(
            state = state,
            onEvent = viewModel::onEvent,
            conversation = conversation?.let { pane ->
                { pane(state.selected, state.canPostHere) }
            },
        )
    }
}

const val MAIN_BUDGET_PATH = "/film-tools/main-budget"
const val DEPARTMENT_BUDGET_PATH = "/film-tools/department-budget"
