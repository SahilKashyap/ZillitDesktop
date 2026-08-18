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
) : ToolProvider {

    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1180.dp, 820.dp)

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
            loadAvatar = board.loadAvatar,
            crewNames = board.crewNames,
            unitBadge = { unitId -> counts.unit(unitId) },
        )
    }

    companion object {
        const val INFO_PATH = "/film-tools/info"
        const val CONFIDENTIAL_INFO_PATH = "/film-tools/confidential-info"

        /** Camera & Sound Report — the web's `/film-tools/reports` mount. */
        const val REPORTS_PATH = "/film-tools/reports"

        /** Catering and Message Accounts — boards on the unit host. */
        const val CATERING_PATH = "/film-tools/catering"
        const val ACCOUNTS_PATH = "/film-tools/accounts"
    }
}
