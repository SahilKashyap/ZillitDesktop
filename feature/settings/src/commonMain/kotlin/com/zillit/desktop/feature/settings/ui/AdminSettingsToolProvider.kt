package com.zillit.desktop.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminScreen
import com.zillit.desktop.feature.settings.admin.ui.AdminViewModel
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.approvals.ApprovalsEvent
import com.zillit.desktop.feature.settings.approvals.ApprovalsScreen
import com.zillit.desktop.feature.settings.approvals.ApprovalsViewModel

/**
 * Administration, as its own destination in the rail.
 *
 * ## Why it is not a page inside Settings
 *
 * It was, and the two do not belong together. Settings is a person's own
 * preferences — theme, notifications, their profile. Administration is roughly
 * twenty things that change the **production** for everybody on it: crew,
 * departments, units, permissions, approvals. Reaching the second by scrolling
 * past the first put a coordinator's most-used page two clicks and a scroll
 * behind a theme switch.
 *
 * Given its own provider rather than another route on the Settings one so that
 * the two are genuinely separate windows: separate tab titles, separate sizes,
 * and — the part that decides it — separate badges. [badge] is the count of
 * people waiting to be approved, and a `ToolProvider` badges every window it
 * owns. Sharing a provider would print that number on the Settings tab too,
 * where there is nothing to approve.
 *
 * ## It shares one view model with Settings
 *
 * Deliberately: [SettingsUiState] already holds the admin page's state, and its
 * effects are a broadcast flow, so both windows see every effect. Each provider
 * therefore acts on its own and ignores the rest — the `when` below is
 * exhaustive so that a new effect is a compile error here rather than something
 * silently dropped in one window and handled in the other.
 */
class AdminSettingsToolProvider(
    private val viewModel: SettingsViewModel,
    /**
     * The approval queues, when there is a session to read them with.
     *
     * Null before sign-in and between productions — there is nothing to approve
     * without one.
     */
    private val approvals: ApprovalsViewModel? = null,
    /**
     * The sixteen administration pages, when there is a session to read them
     * with.
     *
     * Null before sign-in and between productions. The listing still renders
     * without it — the rows are plain data — and clicking one lands on a page
     * that says it has nothing to show rather than on a crash.
     */
    private val admin: AdminViewModel? = null,
) : ToolProvider {

    override val path: String = ADMIN_SETTINGS_PATH
    override val title: String = ADMIN_SETTINGS_TITLE

    /**
     * Not the Settings gear. Two rail entries sharing one glyph is two entries
     * nobody can tell apart at the collapsed width, which is how the rail sits
     * most of the time.
     */
    override val icon = ZillitToolIcons.Production

    override val defaultSize: DpSize = DpSize(760.dp, 720.dp)

    /** The approval queues live under this path; the host hands us the window. */
    override val hostsOwnRoutes: Boolean = true

    /**
     * What is waiting to be approved.
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
                    is SettingsEffect.OpenApprovals ->
                        navigator.navigate(WorkspaceRoute.Tool(effect.queue.path))

                    is SettingsEffect.OpenAdminPage ->
                        navigator.navigate(WorkspaceRoute.Tool(effect.page.path))

                    // Raised by the Settings listing, in the other window. The
                    // view model is shared and its effects are a broadcast, so
                    // they arrive here too — and are not ours to act on.
                    is SettingsEffect.OpenExternal,
                    is SettingsEffect.OpenAccountPage,
                    SettingsEffect.AskLeaveProduction,
                    SettingsEffect.SignedOut,
                    -> Unit
                }
            }
        }

        val queue = ApprovalQueue.entries.firstOrNull { route.path.startsWith(it.path) }
        // Matched after the queues, which live under the same prefix. Their
        // slugs do not collide, but resolving in this order means a queue can
        // never be shadowed by a page added later.
        val page = queue?.let { null } ?: AdminDestination.entries
            .firstOrNull { route.path == it.path }

        // The tab says which page it is on. Two identically-titled tabs are the
        // reason tearing one off stops being useful.
        LaunchedEffect(queue, page) {
            navigator.setTitle(queue?.tabTitle ?: page?.title ?: title)
        }

        if (state.account.isAdmin) TrackApprovalCounts()

        // Back always means the listing, never history: a restored window can
        // open straight onto a page, and then there is nothing behind it.
        val back = {
            if (navigator.canGoBack) navigator.back()
            else navigator.navigate(WorkspaceRoute.Tool(ADMIN_SETTINGS_PATH))
        }

        when {
            queue != null -> ApprovalsPage(queue, back)

            page != null -> AdminPage(page, state, back)

            // No back control: this is a rail destination now, so there is
            // nothing above it to return to. The pages below it still have one.
            else -> AdminSettingsScreen(state = state, onEvent = viewModel::onEvent)
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
     * Reads both queues once, so the badges are right before anyone looks.
     *
     * A count that only appears after you open the page it counts is not a
     * badge — the whole point of the number on the rail is to save the trip.
     * Two cheap list calls per admin session: `Opened` is a no-op once a queue
     * has answered, and the decisions made on the pages keep it current after.
     */
    @Composable
    private fun TrackApprovalCounts() {
        val approvals = approvals ?: return
        val approvalState by approvals.state.collectAsState()

        LaunchedEffect(approvals) {
            approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
            approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.ProfileChanges))
        }

        val waitingCrew = approvalState.crew.items.size
        val waitingChanges = approvalState.profiles.items.size
        LaunchedEffect(waitingCrew, waitingChanges) {
            viewModel.onEvent(SettingsEvent.ApprovalsCounted(waitingCrew, waitingChanges))
        }
    }

    /**
     * An approval queue, or the reason it cannot be shown.
     *
     * Without a session there is no production to approve anyone onto. The row
     * that leads here is absent in that state, so this is a deep link into a
     * signed-out window rather than something a reader can reach by clicking.
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
}

/** Matches the web app's `/settings?s=admin` tab, as a path. */
const val ADMIN_SETTINGS_PATH = "/settings/admin"

/**
 * Shared with the rail, which cannot import this module — see `DefaultRailItems`
 * for why the rail is a fixed list rather than something derived.
 */
const val ADMIN_SETTINGS_TITLE = "Admin settings"

/**
 * Where each administration page lives.
 *
 * Built from the destination's own slug rather than listed here, so adding a
 * page cannot forget its route.
 */
val AdminDestination.path: String get() = "$ADMIN_SETTINGS_PATH/$slug"

/** Where each queue lives, so a deep link opens the right one. */
val ApprovalQueue.path: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> "$ADMIN_SETTINGS_PATH/new-crew"
        ApprovalQueue.ProfileChanges -> "$ADMIN_SETTINGS_PATH/profile-changes"
    }

private val ApprovalQueue.tabTitle: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> "Approve new crew"
        ApprovalQueue.ProfileChanges -> "Approve profile changes"
    }
