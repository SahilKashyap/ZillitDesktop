package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.LocalHostedBy
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Cash Expenses as a workspace window.
 *
 * Opens maximised and hosts its own routes: it is a sub-application with a
 * dozen surfaces and its own navigation, so handing it the whole window is
 * both what the plan calls for and what makes the master–detail queues usable.
 *
 * The path matches the web's (`/film-tools/cash-expenses`) so the tools grid,
 * the badge routing and any deep link agree across clients.
 */
class CashExpensesToolProvider(
    private val viewModel: CashExpensesViewModel,
    /** Opens a stored receipt. Injected because this module has no file layer. */
    private val onOpenAttachment: (String) -> Unit = {},
    /** The crew photo for a user id — shown beside every name in the tool. */
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
) : ToolProvider {

    override val path: String = CASH_EXPENSES_PATH
    override val title: String get() = str(S.desktop_ce_tool_title)
    override val icon = ZillitToolIcons.CashExpense
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // Errors are shown here rather than in the state so a failure that has
        // been read does not reappear when the window is switched away from and
        // back — the effect fires once, the toast times out, and that is that.
        var failure by remember { mutableStateOf<String?>(null) }

        // Standing on its own — a Film Tools tile — or inside the Account
        // Hub, which provides its own path here. An accountant who opened the
        // tile gets the crew view, as the web's `?entry=tool` does. Declared
        // before start so the first viewer resolved is already the right one;
        // the view model may be shared with the hub, so each composition says
        // which way it came in when it appears.
        val asTool = LocalHostedBy.current == null
        LaunchedEffect(viewModel, asTool) { viewModel.onEvent(CashEvent.Enter(asTool)) }

        // The first time this tool is shown: the view model is built with the
        // app, before a production is open, so it resolves who the viewer is
        // here rather than in its constructor.
        LaunchedEffect(viewModel) { viewModel.start() }

        // A deep link lands on the page it names — the workspace resolves this
        // provider by longest prefix, so the tail arrives intact. Production
        // Setup's Petty Cash tile uses it to open the settings that live here.
        LaunchedEffect(route.path) {
            CashDestination.fromSlug(route.path.removePrefix(CASH_EXPENSES_PATH).trim('/'))
                ?.let { viewModel.onEvent(CashEvent.Open(it)) }
        }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is CashEffect.Failed -> failure = effect.message
                    is CashEffect.OpenAttachment -> onOpenAttachment(effect.key)
                }
            }
        }

        // The tab title names the open page, so several torn-off windows of the
        // same tool are told apart on the taskbar.
        LaunchedEffect(state.destination) {
            navigator.setTitle(str(S.desktop_ce_window_title, state.destination.label))
        }

        CashExpensesScreen(state = state, onEvent = viewModel::onEvent, loadAvatar = loadAvatar)

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val CASH_EXPENSES_PATH = "/film-tools/cash-expenses"
