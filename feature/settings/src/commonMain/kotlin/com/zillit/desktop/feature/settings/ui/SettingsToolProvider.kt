package com.zillit.desktop.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.settings.account.AccountEffect
import com.zillit.desktop.feature.settings.account.AccountEvent
import com.zillit.desktop.feature.settings.account.AccountPage
import com.zillit.desktop.feature.settings.account.AccountScreen
import com.zillit.desktop.feature.settings.account.AccountViewModel
import com.zillit.desktop.feature.settings.account.LeaveProductionDialog
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminScreen
import com.zillit.desktop.feature.settings.admin.ui.AdminViewModel
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.approvals.ApprovalsEvent
import com.zillit.desktop.feature.settings.approvals.ApprovalsScreen
import com.zillit.desktop.feature.settings.approvals.ApprovalsViewModel

/**
 * Settings as a workspace window.
 *
 * Like every other destination here, so it can be pinned or torn off — someone
 * adjusting the interface size wants to see the rest of the app change while
 * they do it, which a modal over everything would prevent.
 *
 * ## Two tabs, as on the web
 *
 * The web's Settings page (`SettingsTabs.jsx`) is two tabs: Profile Settings
 * and, for admins, Admin Settings. This window is the same — [SettingsTab]
 * picks which from the route, `/settings` or `/settings/admin`, so a deep link
 * or a restored window opens on the right one. Everything the desktop adds to
 * a person's own settings (appearance, notifications, desktop widgets, the
 * build) lives on the Profile tab.
 *
 * Below each tab are its pages. Profile's are the four behind the "Your
 * account" rows: their profile, their recovery email, their devices, and the
 * invite code. Admin's are the administration pages and the two approval
 * queues, under `/settings/admin/…`.
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
    /**
     * The approval queues, when there is a session to read them with.
     *
     * Null before sign-in and between productions — there is nothing to approve
     * without one.
     */
    private val approvals: ApprovalsViewModel? = null,
    /**
     * The administration pages, when there is a session to read them with.
     *
     * Null before sign-in and between productions. The tab still renders
     * without it — the rows are plain data — and clicking one lands on a page
     * that says it has nothing to show rather than on a crash.
     */
    private val admin: AdminViewModel? = null,
) : ToolProvider {

    override val path: String = SETTINGS_PATH
    override val title: String get() = str(S.settings)
    override val icon = ZillitIcons.Settings

    override val defaultSize: DpSize = DpSize(760.dp, 720.dp)

    /** The account and admin pages live under this path; the host hands us the window. */
    override val hostsOwnRoutes: Boolean = true

    /**
     * What is waiting to be approved on the Admin Settings tab.
     *
     * Only for admins: a badge on a tab whose page has nothing actionable on it
     * is a number the reader cannot clear.
     */
    @Composable
    override fun badge(): Int? {
        val state by viewModel.state.collectAsState()
        return state.admin.pendingTotal.takeIf { it > 0 && state.account.isAdmin }
    }

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SettingsEffect.OpenExternal -> onOpenExternal(effect.url)

                    // Zillit Help is a page of this app's own, so it opens the
                    // way the account pages do rather than in a browser.
                    SettingsEffect.OpenHelp ->
                        navigator.navigate(WorkspaceRoute.Tool(HELP_PATH))

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

                    is SettingsEffect.OpenApprovals ->
                        navigator.navigate(WorkspaceRoute.Tool(effect.queue.path))

                    is SettingsEffect.OpenAdminPage ->
                        navigator.navigate(WorkspaceRoute.Tool(effect.page.path))

                    // A different tool's window, not this one: taking Settings
                    // over would mean the way back is to close the page you
                    // just opened.
                    is SettingsEffect.OpenTool ->
                        navigator.openInNewWindow(WorkspaceRoute.Tool(effect.path))
                }
            }
        }

        val path = route.path
        // The admin tree first: its queues and pages share a prefix, and an
        // account page's slug must never shadow one of them.
        val queue = ApprovalQueue.entries.firstOrNull { path.startsWith(it.path) }
        val adminPage = if (queue == null) AdminDestination.entries.firstOrNull { path == it.path } else null
        val onAdminTree = path.startsWith(ADMIN_SETTINGS_PATH)
        val accountPage = if (onAdminTree) null else AccountPage.fromPath(path)
        val tab = SettingsTab.forPath(path, state.account.isAdmin)

        // The tab says which page it is on. Two identically-titled tabs are the
        // reason tearing one off stops being useful.
        LaunchedEffect(queue, adminPage, accountPage, tab) {
            navigator.setTitle(
                queue?.tabTitle ?: adminPage?.title ?: accountPage?.tabTitle
                    ?: if (tab == SettingsTab.Admin) SettingsTab.Admin.label else title,
            )
        }

        // Back always means the tab the page hangs off, never history alone: a
        // restored window can open straight onto a page, and then there is
        // nothing behind it.
        fun backTo(tabPath: String) {
            if (navigator.canGoBack) navigator.back()
            else navigator.navigate(WorkspaceRoute.Tool(tabPath))
        }

        when {
            queue != null -> ApprovalsPage(queue) { backTo(ADMIN_SETTINGS_PATH) }

            adminPage != null -> AdminPage(adminPage, state) { backTo(ADMIN_SETTINGS_PATH) }

            accountPage != null -> AccountPage(accountPage) { backTo(SETTINGS_PATH) }

            else -> {
                SettingsTabsFrame(
                    active = tab,
                    state = state,
                    onSelect = { chosen ->
                        if (chosen != tab) navigator.navigate(WorkspaceRoute.Tool(chosen.path))
                    },
                ) {
                    when (tab) {
                        SettingsTab.Profile ->
                            SettingsScreen(state = state, onEvent = viewModel::onEvent, showTitle = false)
                        SettingsTab.Admin ->
                            AdminSettingsScreen(state = state, onEvent = viewModel::onEvent, showHeader = false)
                    }
                }
                // Composed with the listing, because the row that opens it is
                // there — leaving is a decision, not a page.
                LeaveDialog(state)
            }
        }
    }

    /**
     * One administration page.
     *
     * Refuses rather than renders when the session is gone: every page here
     * reads the production, and one with no session would show an empty list
     * that reads as a production with no departments.
     */
    @Composable
    private fun AdminPage(page: AdminDestination, state: SettingsUiState, onBack: () -> Unit) {
        val admin = admin ?: return
        val adminState by admin.state.collectAsState()

        AdminScreen(
            destination = page,
            state = adminState,
            onEvent = admin::onEvent,
            onBack = onBack,
            // Which pages this production has at all. The listing filters on
            // the same facts, so the two cannot disagree.
            production = state.admin.production,
        )
    }

    /**
     * An approval queue, or nothing when there is no session to read it with.
     *
     * The row that leads here is absent in that state, so this is a deep link
     * into a signed-out window rather than something a reader can click to.
     */
    @Composable
    private fun ApprovalsPage(queue: ApprovalQueue, onBack: () -> Unit) {
        val approvals = approvals ?: return
        val approvalState by approvals.state.collectAsState()

        // Loads on arrival, and again on the way back from the other queue —
        // `Opened` is a no-op once a queue has answered, so returning to a page
        // does not re-read a list the admin is part-way through.
        LaunchedEffect(queue) { approvals.onEvent(ApprovalsEvent.Opened(queue)) }

        ApprovalsScreen(
            queue = queue,
            state = approvalState,
            onEvent = approvals::onEvent,
            onBack = onBack,
            known = approvals::known,
        )
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
