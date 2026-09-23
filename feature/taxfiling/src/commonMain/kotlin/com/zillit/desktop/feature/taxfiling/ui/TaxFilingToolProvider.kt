package com.zillit.desktop.feature.taxfiling.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute

/**
 * Tax filing as a workspace tool.
 *
 * Registered *under* the Account Hub's own path, because that is where it is
 * reached from and nowhere else — it has no tile in the Film Tools grid, on
 * the web or here. The longest-prefix route resolution means the hub keeps
 * `/film-tools/account-hub` and this owns the deeper route, catalogue and
 * filings alike.
 *
 * The hub renders it inside its own shell. Moving between the catalogue and a
 * filing is a route change through [WindowNavigator], so the hub's sidebar
 * row always lands on the catalogue; leaving is the navigator's `close`,
 * which returns the hub to its own area.
 *
 * [openConsent] takes the accountant to HMRC's consent page in their browser.
 * Deliberately not in an embedded view: signing in to HMRC belongs in the
 * browser the person already trusts, and an OAuth grant typed into a window
 * this application drew is a habit worth not teaching.
 */
class TaxFilingToolProvider(
    private val viewModel: TaxFilingViewModel,
    private val openConsent: (String) -> Unit,
) : ToolProvider {

    override val path: String = TAX_FILING_PATH
    override val title: String get() = str(S.desktop_tax_filing)
    override val icon = ZillitIcons.Hierarchy
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(WIDTH.dp, HEIGHT.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        // Held here rather than in the state, so a message that has been read
        // does not reappear when the window is switched away from and back.
        var toast by remember { mutableStateOf<TaxToast?>(null) }

        LaunchedEffect(route.path) { viewModel.onEvent(TaxFilingEvent.RouteChanged(route.path)) }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is TaxFilingEffect.Toast -> toast = effect.toast
                    is TaxFilingEffect.OpenInBrowser -> openConsent(effect.url)
                    is TaxFilingEffect.Navigate -> navigator.navigate(WorkspaceRoute.Tool(effect.path))
                    TaxFilingEffect.LeaveTool -> navigator.close()
                }
            }
        }

        TaxFilingScreen(
            state = state,
            onEvent = viewModel::onEvent,
            toast = toast,
            onToastDismiss = { toast = null },
        )
    }

    companion object {
        /** Under the hub's path, which is the only place it is reached from. */
        const val TAX_FILING_PATH = TaxFilingRoute.BASE_PATH

        private const val WIDTH = 1280
        private const val HEIGHT = 900
    }
}
