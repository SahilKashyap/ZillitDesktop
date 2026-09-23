package com.zillit.desktop.feature.bankrec.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Bank Reconciliation as a workspace window.
 *
 * Registered under the Account Hub's own path, because that is where it is
 * reached from and nowhere else — it has no tile in the Film Tools grid, on
 * the web or here. The longest-prefix route resolution leaves the hub owning
 * `/film-tools/account-hub` and gives this the deeper route.
 *
 * The Account Hub embeds it in its own shell, as the web does; the workspace
 * tab's full view gives the module's header and tabs back to the panels.
 */
class BankRecToolProvider(
    private val viewModel: BankRecViewModel,
) : ToolProvider {

    override val path: String = BANK_REC_PATH
    override val title: String get() = str(S.desktop_bank_reconciliation)
    override val icon = ZillitIcons.Bank
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(WIDTH.dp, HEIGHT.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }

        // A deep link lands on the tab it names: the workspace resolves this
        // provider by longest prefix, so `…/bank-reconciliation/exceptions`
        // arrives with its tail intact, as the web's URL would.
        LaunchedEffect(route.path) { viewModel.openRoute(route.path.removePrefix(BANK_REC_PATH)) }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is BankRecEffect.Failed -> failure = effect.message
                    // The design system's own, which is where every other
                    // copy in this application goes.
                    is BankRecEffect.CopyToClipboard -> copyTextToClipboard(effect.text)
                }
            }
        }

        // The tab title names the open tab, so several torn-off windows of the
        // same reconciliation are told apart on the taskbar.
        LaunchedEffect(state.tab) {
            navigator.setTitle(str(S.desktop_br_window_title, state.tab.label))
        }

        CompositionLocalProvider(LocalBankRecPeople provides viewModel.people) {
            BankRecScreen(state = state, onEvent = viewModel::onEvent)
        }

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }

    companion object {
        /** Under the hub's path, which is the only place it is reached from. */
        const val BANK_REC_PATH = "/film-tools/account-hub/bank-reconciliation"

        private const val WIDTH = 1600
        private const val HEIGHT = 950
    }
}
