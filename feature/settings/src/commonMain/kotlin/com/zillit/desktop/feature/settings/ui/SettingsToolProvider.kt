package com.zillit.desktop.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.settings.account.AccountEffect
import com.zillit.desktop.feature.settings.account.AccountEvent
import com.zillit.desktop.feature.settings.account.AccountPage
import com.zillit.desktop.feature.settings.account.AccountScreen
import com.zillit.desktop.feature.settings.account.AccountViewModel
import com.zillit.desktop.feature.settings.account.LeaveProductionDialog

/**
 * Settings as a workspace window.
 *
 * Like every other destination here, so it can be pinned or torn off — someone
 * adjusting the interface size wants to see the rest of the app change while
 * they do it, which a modal over everything would prevent.
 *
 * ## The listing, and the account pages under it
 *
 * Administration is a rail destination of its own — see
 * [AdminSettingsToolProvider] for why — leaving this window with the reader's
 * own preferences and the four pages behind the "Your account" rows: their
 * profile, their recovery email, their devices, and the invite code.
 *
 * Those four are routes rather than dialogs because each is a page with a form
 * on it, and because a torn-off Settings window should be able to sit open on
 * the profile form while the reader checks a name somewhere else.
 */
class SettingsToolProvider(
    private val viewModel: SettingsViewModel,
    /**
     * Hands a documentation link to the browser.
     *
     * Injected because this module is common code with no browser in it, and
     * because the launcher on the other end is the one place that refuses
     * anything but http(s).
     */
    private val onOpenExternal: (String) -> Unit = {},
    /**
     * The account pages, when there is a session behind them.
     *
     * Null before sign-in: there is no profile to edit and no device list to
     * read without one, and the rows that lead here are absent in that state.
     */
    private val account: AccountViewModel? = null,
    /**
     * Puts text on the system clipboard.
     *
     * Injected for the same reason as the browser launcher: this module is
     * common code, and the clipboard is the frame's.
     */
    private val onCopy: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = SETTINGS_PATH
    override val title: String = "Settings"
    override val icon = ZillitIcons.Settings

    override val defaultSize: DpSize = DpSize(760.dp, 720.dp)

    /** The account pages live under this path; the host hands us the window. */
    override val hostsOwnRoutes: Boolean = true

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SettingsEffect.OpenExternal -> onOpenExternal(effect.url)

                    // A row on the listing, opened in this same window — these
                    // are the reader's own pages, not somewhere else's.
                    is SettingsEffect.OpenAccountPage ->
                        navigator.navigate(WorkspaceRoute.Tool(effect.page.route))

                    // A dialog over the listing rather than a page: there is
                    // nothing to read and one decision to make.
                    SettingsEffect.AskLeaveProduction ->
                        account?.onEvent(AccountEvent.AskLeave)

                    // Nothing to close: signing out takes the whole frame back
                    // to the sign-in screen, and this window goes with it.
                    SettingsEffect.SignedOut -> Unit

                    // Raised on the administration page, in its own window. The
                    // view model is shared and its effects are a broadcast, so
                    // they arrive here too — and are not ours to act on.
                    is SettingsEffect.OpenApprovals,
                    is SettingsEffect.OpenAdminPage,
                    -> Unit
                }
            }
        }

        val page = AccountPage.fromPath(route.path)

        // The tab says which page it is on. Two identically-titled tabs are the
        // reason tearing one off stops being useful.
        LaunchedEffect(page) { navigator.setTitle(page?.tabTitle ?: title) }

        if (page == null) {
            SettingsScreen(state = state, onEvent = viewModel::onEvent)
            // Composed with the listing, because the row that opens it is
            // there — leaving is a decision, not a page.
            LeaveDialog(state)
        } else {
            AccountPage(page) {
                // A restored session can open straight onto one of these, and
                // then there is no history to go back through — so back *means*
                // the listing rather than "whatever came before".
                if (navigator.canGoBack) navigator.back()
                else navigator.navigate(WorkspaceRoute.Tool(SETTINGS_PATH))
            }
        }
    }

    /**
     * One of the reader's own pages.
     *
     * Without a session there is no profile to edit. The row that leads here is
     * absent in that state, so this is a deep link into a signed-out window
     * rather than something anyone can reach by clicking.
     */
    @Composable
    private fun AccountPage(page: AccountPage, onBack: () -> Unit) {
        val account = account ?: return
        val accountState by account.state.collectAsState()

        // Loads on arrival, and again on the way back from another page. Each
        // page fetches only what it needs — see AccountViewModel.
        LaunchedEffect(page) { account.onEvent(AccountEvent.Opened(page)) }
        AccountEffects()

        AccountScreen(
            page = page,
            state = accountState,
            onEvent = account::onEvent,
            onBack = onBack,
        )
    }

    /**
     * Leaving the production, over the listing that offers it.
     *
     * Composed always so the dialog's exit can play; the flag drives
     * visibility, like the sign-out dialog beside it.
     */
    @Composable
    private fun LeaveDialog(state: SettingsUiState) {
        val account = account ?: return
        val accountState by account.state.collectAsState()

        AccountEffects()

        LeaveProductionDialog(
            state = accountState.leave,
            productionName = state.account.productionName,
            isAdmin = state.account.isAdmin,
            onEvent = account::onEvent,
        )
    }

    /**
     * The two things the account pages cannot do for themselves.
     *
     * The clipboard and the frame belong to the host. Signing out from the
     * device list and leaving the production both end this window's session —
     * neither is handled here, because the frame is already listening for the
     * session going away and takes every window with it.
     */
    @Composable
    private fun AccountEffects() {
        val account = account ?: return

        LaunchedEffect(account) {
            account.effects.collect { effect ->
                when (effect) {
                    is AccountEffect.CopyToClipboard -> onCopy(effect.text)

                    // Both leave this window without a session behind it. The
                    // frame notices and returns to sign-in or the production
                    // picker; acting here as well would race it.
                    AccountEffect.SignedOutHere,
                    AccountEffect.LeftProduction,
                    -> Unit
                }
            }
        }
    }
}

const val SETTINGS_PATH = "/settings"

/** Where each account page lives, so a deep link opens the right one. */
val AccountPage.route: String get() = "$SETTINGS_PATH/$slug"
