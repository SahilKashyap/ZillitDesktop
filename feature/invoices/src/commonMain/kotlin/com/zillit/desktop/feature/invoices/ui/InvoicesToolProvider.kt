package com.zillit.desktop.feature.invoices.ui

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
import kotlin.time.Clock
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Invoices as a workspace tool. The web has no standalone route (accountants
 * reach it inside the Account Hub); the desktop gives it the film-tools path
 * the Android tile opens.
 */
class InvoicesToolProvider(
    private val viewModel: InvoicesViewModel,
    /** For the register's "Nd overdue" column; re-read on every list load. */
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ToolProvider {

    override val path: String = INVOICES_PATH
    override val title: String get() = str(S.ah_invoices)
    override val icon = ZillitIcons.Receipt
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1360.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        // Every entry and route change names the page to show — the Account
        // Hub re-enters with `/film-tools/invoices/<page>`; the bare path is
        // Overview, as the web redirects it.
        LaunchedEffect(route.path) { viewModel.onEvent(InvoicesEvent.OpenRoute(route.path)) }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is InvoicesEffect.Notice -> notice = effect.text
                }
            }
        }

        val nowMs = remember(state.invoices) { nowMillis() }
        InvoicesScreen(
            state = state,
            onEvent = viewModel::onEvent,
            nowMs = nowMs,
            // Inside the hub the navigator returns to the hub; standalone it
            // closes the window. The hub shows this module full-bleed, so the
            // sidebar's back chip is the only way out.
            onBack = navigator::close,
        )
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }

    companion object {
        const val INVOICES_PATH = "/film-tools/invoices"
    }
}
