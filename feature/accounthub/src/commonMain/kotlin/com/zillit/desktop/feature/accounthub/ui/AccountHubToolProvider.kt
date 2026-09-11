package com.zillit.desktop.feature.accounthub.ui

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
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The Account Hub as a workspace window.
 *
 * Opens maximised and hosts its own routes: it is a console over four screens
 * with a sidebar of its own, and giving it anything less than the window would
 * leave a settings page with nine sections inside a pane.
 *
 * The path matches the web's so the tools grid, badge routing and any deep link
 * agree across clients.
 */
class AccountHubToolProvider(
    private val viewModel: AccountHubViewModel,
) : ToolProvider {

    override val path: String = ACCOUNT_HUB_PATH
    override val title: String = "Account Hub"
    override val icon = ZillitIcons.Ledger
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // Held here rather than in the state so a failure that has been read
        // does not reappear when the window is switched away from and back.
        var failure by remember { mutableStateOf<String?>(null) }

        // The first time this tool is shown: the view model is built with the
        // app, before a production is open, so it resolves who the viewer is
        // here rather than in its constructor.
        LaunchedEffect(viewModel) { viewModel.start() }

        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AccountHubEffect.Failed -> failure = effect.message
                    // A *different* window, not this one. Purchase Orders and
                    // Payroll are their own tools with their own routes; taking
                    // over the console's window would mean the way back to the
                    // hub is to close the tool you just opened.
                    is AccountHubEffect.OpenTool ->
                        navigator.openInNewWindow(WorkspaceRoute.Tool(effect.path))
                }
            }
        }

        // The tab title names the open screen, so several torn-off windows of
        // the same console are told apart on the taskbar.
        LaunchedEffect(state.area) {
            navigator.setTitle(state.area?.let { "Account Hub · ${it.label}" } ?: "Account Hub")
        }

        AccountHubScreen(
            state = state,
            onEvent = viewModel::onEvent,
            canAttachAgreements = viewModel.canAttachAgreements,
            // Read once when the console is composed. A clock that ticked
            // under the period-close dialog would change which week the
            // confirmation was for.
            nowMillis = remember { viewModel.nowMillis() },
            canImportBudget = viewModel.canImportBudget,
        )

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val ACCOUNT_HUB_PATH = "/film-tools/account-hub"
