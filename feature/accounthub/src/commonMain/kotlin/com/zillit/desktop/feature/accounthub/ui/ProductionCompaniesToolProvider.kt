package com.zillit.desktop.feature.accounthub.ui

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
import com.zillit.desktop.feature.accounthub.ui.pages.ProductionCompaniesPage

/**
 * Admin Settings → Production Setup, as a page of the Settings window.
 *
 * The web's tile opens `/settings/production-setup`, which hosts the Account
 * Hub's Companies section alone — not the console. This is that page: it shares
 * the console's view model, so a company saved here is the one the console's
 * Production Setup shows, and it is registered under the web's path so the
 * registry's longest-prefix match picks it over the Settings provider that
 * owns everything else below `/settings`.
 *
 * Opened in the Settings window itself, with a back arrow to Admin Settings.
 */
class ProductionCompaniesToolProvider(
    private val viewModel: AccountHubViewModel,
) : ToolProvider {
    override val path: String = PRODUCTION_SETUP_COMPANIES_PATH
    override val title: String = str(S.ps_production_setup)
    override val icon = ZillitIcons.Bank
    override val hostsOwnRoutes: Boolean = true

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.openCompanies() }

        // Only a refusal is this page's to show; the console's hand-offs
        // (another tool, the way back to Film Tools) have nowhere to go from here.
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                if (effect is AccountHubEffect.Failed) failure = effect.message
            }
        }

        LaunchedEffect(Unit) { navigator.setTitle(title) }

        LaunchedEffect(state.setup.dirtySections) {
            navigator.setDirty(state.setup.dirtySections.isNotEmpty())
        }

        ProductionCompaniesPage(
            state = state,
            onEvent = viewModel::onEvent,
            onBack = {
                if (navigator.canGoBack) navigator.back()
                else navigator.navigate(WorkspaceRoute.Tool(PRODUCTION_SETUP_BACK_PATH))
            },
        )

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

/** The web's `/settings/production-setup`. */
const val PRODUCTION_SETUP_COMPANIES_PATH = "/settings/production-setup"

/** Where its back arrow lands when there is no history: Admin Settings, `ADMIN_SETTINGS_PATH` in `feature:settings`. */
const val PRODUCTION_SETUP_BACK_PATH = "/settings/admin"
