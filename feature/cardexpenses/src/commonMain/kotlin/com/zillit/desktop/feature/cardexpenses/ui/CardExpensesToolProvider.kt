package com.zillit.desktop.feature.cardexpenses.ui

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

/**
 * Card Expenses as a workspace window.
 *
 * Maximised and hosting its own routes, like the cash tool: fourteen surfaces
 * behind a sidebar is a sub-application, and the master–detail queues need the
 * width.
 */
class CardExpensesToolProvider(
    private val viewModel: CardExpensesViewModel,
    private val onOpenAttachment: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = CARD_EXPENSES_PATH
    override val title: String = "Card Expenses"
    override val icon = ZillitToolIcons.CardExpense
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        // The first time this tool is shown: the view model is built with the
        // app, before a production is open, so it resolves who the viewer is
        // here rather than in its constructor.
        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is CardEffect.Failed -> failure = effect.message
                    is CardEffect.OpenAttachment -> onOpenAttachment(effect.key)
                }
            }
        }

        LaunchedEffect(state.destination) {
            navigator.setTitle("Cards · ${state.destination.label}")
        }

        CardExpensesScreen(state = state, onEvent = viewModel::onEvent)

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val CARD_EXPENSES_PATH = "/film-tools/card-expenses"
