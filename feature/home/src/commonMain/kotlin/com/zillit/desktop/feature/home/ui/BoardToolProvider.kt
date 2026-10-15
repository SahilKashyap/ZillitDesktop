package com.zillit.desktop.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.badges.BadgeCounts
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import kotlinx.coroutines.flow.StateFlow

/**
 * A film tool that IS a notice board — Info and Confidential Info.
 *
 * On the web both tools mount the same unit-chat component the Home bulletin
 * uses; the only differences are the REST segment (`info` /
 * `confidentialinfo`), the socket namespace, and the badge slot. So the
 * desktop hosts them as the Home board with a different repository behind it
 * — see `HomeFeedRepositoryImpl.board` — and this provider is the whole tool.
 */
class BoardToolProvider(
    override val path: String,
    override val title: String,
    override val icon: ImageVector,
    private val feedViewModel: HomeFeedViewModel,
    private val board: HomeBoardContext = HomeBoardContext(),
    private val badges: StateFlow<BadgeCounts>? = null,
    /**
     * The tool's name as the PRODUCTION calls it, when the server names it at
     * all. A board tool is named twice — by the constant the client was built
     * with, and by the `unit_name` the rights carry — and the two need not
     * agree: the report chat ships as `production_report_tool_chat_label` and
     * a production may call it "Daily Production Report". The board shows the
     * production's name inside, so a tab reading anything else is the wrong
     * window. Null (or blank) falls back to [title].
     */
    private val unitTitle: (() -> String?)? = null,
    /**
     * The tool's own action in the board's tab strip. The report chat's
     * "Create Production Report" (ZL-21828): the web gave that chat the
     * creation tool's header so the one leads to the other, and this is the
     * half of that header the desktop does not already have — the window
     * frame is the way back, the tab carries the title, and the theme is the
     * whole app's rather than one tool's.
     */
    private val headerAction: (@Composable () -> Unit)? = null,
) : ToolProvider {

    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1180.dp, 820.dp)

    override fun titleFor(route: WorkspaceRoute): String =
        unitTitle?.invoke()?.takeIf { it.isNotBlank() } ?: title

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val feedState by feedViewModel.state.collectAsState()
        val counts = badges?.collectAsState()?.value ?: BadgeCounts.Empty

        // The board loads its (single) unit on first show, not at startup: the
        // tools call that names the unit carries project and user headers.
        LaunchedEffect(feedViewModel) { feedViewModel.onEvent(HomeFeedEvent.Load) }

        HomeFeedScreen(
            state = feedState,
            onEvent = feedViewModel::onEvent,
            media = board.media,
            onOpenAttachment = board.onOpenAttachment,
            resolveAuthor = board.resolveAuthor,
            player = board.player,
            onOpenLocation = board.onOpenLocation,
            onOpenLink = board.onOpenLink,
            loadAvatar = board.loadAvatar,
            crewNames = board.crewNames,
            unitBadge = { unitId -> counts.unit(unitId) },
            headerAction = headerAction,
        )
    }

    companion object {
        const val INFO_PATH = "/film-tools/info"
        const val CONFIDENTIAL_INFO_PATH = "/film-tools/confidential-info"

        /** Camera & Sound Report — the web's `/film-tools/reports` mount. */
        const val REPORTS_PATH = "/film-tools/reports"

        /** Script Notes — the same engine on the script-notes host. */
        const val SCRIPT_NOTES_PATH = "/film-tools/script-notes"

        /** Catering and Message Accounts — boards on the unit host. */
        const val CATERING_PATH = "/film-tools/catering"
        const val ACCOUNTS_PATH = "/film-tools/accounts"
    }
}
