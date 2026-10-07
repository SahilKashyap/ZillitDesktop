package com.zillit.desktop.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.LaunchedEffect
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.calendar.CalendarScreen
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.badges.BadgeCounts
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.feature.home.domain.AudioPlayer
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Renders the dashboard inside a workspace window.
 *
 * A `ToolProvider` like any other, so Home is a window the user can pin, tear
 * off or close — not a privileged screen the frame owns. That is the whole
 * point of the workspace contract (plan §3.3).
 */
/**
 * Everything the board needs from the platform to render — grouped because
 * they travel together, exactly as `MediaCapture` groups the composer's
 * capture hooks. All default to "absent", which degrades the affordance.
 */
class HomeBoardContext(
    /** Null when the production's storage is unreachable; media shows chips. */
    val media: NoticeMediaSource? = null,
    /** Save to Downloads and open with the OS. Injected — the UI does no IO. */
    val onOpenAttachment: (NoticeAttachment) -> Unit = {},
    /** The sender's display line, resolved from the crew list at render time. */
    val resolveAuthor: (String?) -> String? = { null },
    /** Plays voice messages; null when the platform has no audio out. */
    val player: AudioPlayer? = null,
    /** Opens a shared location in the browser's maps. */
    val onOpenLocation: (GeoPoint) -> Unit = {},
    /** Opens a web address (the library's Links tab) in the browser. */
    val onOpenLink: (String) -> Unit = {},
    /** A profile picture's bytes by user id; null falls back to initials. */
    val loadAvatar: suspend (String) -> ByteArray? = { null },
    /** The production's crew names — mention picker and highlights. */
    val crewNames: () -> List<String> = { emptyList() },
)

@Suppress("LongParameterList") // Each seam is a host fact the grid reads; grouping them would only rename the list.
class HomeToolProvider(
    private val viewModel: HomeViewModel,
    private val feedViewModel: HomeFeedViewModel,
    private val calendarViewModel: CalendarViewModel? = null,
    private val board: HomeBoardContext = HomeBoardContext(),
    /**
     * The live unread counts. A flow, not a snapshot: the tabs and tiles
     * recompose as counts move, which a captured value could never do.
     */
    private val badges: kotlinx.coroutines.flow.StateFlow<BadgeCounts>? = null,
    /**
     * Where the production's tool switches live — the admin settings page the
     * grid's customise button opens. Null hides the button entirely.
     */
    private val customiseToolsRoute: String? = null,
    /**
     * Joins a calendar event's call. Null on a host with no calling, where
     * the Join button is not drawn.
     */
    private val onJoinEventCall: ((CalendarEvent) -> Unit)? = null,
    /** The viewer's department identifier, read when the grid draws — a tool's ⓘ can depend on it. */
    private val viewerDepartment: () -> String? = { null },
    /** Whether the open production is a non-film one (`project_type_id == "other"`). */
    private val isOtherProject: () -> Boolean = { false },
    /** Whether the open production is a personal one — the grid shows no ⓘ there, as on the web. */
    private val isPersonalProject: () -> Boolean = { false },
    /** The open production's `project_type`, for the documentation links. */
    private val projectType: () -> String? = { null },
    /**
     * The Viewing & Posting Rights Grid's route — the admin notice's second
     * "Click here". Null leaves that line out.
     */
    private val permissionGridRoute: String? = null,
    /** Opens a web address outside the app — the ⓘ's documentation page. */
    private val onOpenUrl: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = "/home"
    override val title: String get() = str(S.home)
    override val icon = ZillitIcons.Home

    // One provider, two faces: `/home` is the board, `/home/tools` the grid.
    override fun titleFor(route: WorkspaceRoute): String =
        if (route.path.endsWith(TOOLS_SEGMENT)) str(S.desktop_film_tools) else title

    override fun iconFor(route: WorkspaceRoute): ImageVector =
        if (route.path.endsWith(TOOLS_SEGMENT)) ZillitIcons.Tools else icon

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        // `/home/tools` is the grid of everything this user may open; `/home`
        // itself is the production's own board, which is what people actually
        // arrive for.
        if (route.path.endsWith(TOOLS_SEGMENT)) {
            ToolGridContent(navigator)
            return
        }

        val feedState by feedViewModel.state.collectAsState()
        val counts = badges?.collectAsState()?.value ?: BadgeCounts.Empty

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
            calendar = calendarViewModel?.let { vm ->
                {
                    val calendarState by vm.state.collectAsState()
                    // Loaded when the tab is first shown rather than at startup:
                    // the events call carries project and user in its header.
                    LaunchedEffect(vm) { vm.load() }
                    CalendarScreen(
                        state = calendarState,
                        onEvent = vm::onEvent,
                        loadAvatar = board.loadAvatar,
                        onJoinCall = onJoinEventCall,
                    )
                }
            },
        )
    }

    @Composable
    private fun ToolGridContent(navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val counts = badges?.collectAsState()?.value ?: BadgeCounts.Empty

        // Every arrival at the grid rereads the list — Android's onResume
        // refresh, for the same reason: switches flipped in Admin Settings
        // while this tab was away must show without reopening the production.
        LaunchedEffect(Unit) { viewModel.onEvent(HomeEvent.Reload) }
        // The page names its own tab on every arrival: the admin notice's
        // "Click here" takes this window to the tool switches, which retitle
        // it, and coming back must not leave their name behind.
        LaunchedEffect(Unit) { navigator.setTitle(str(S.desktop_film_tools)) }

        HomeScreen(
            state = state,
            // The whole slice, not a lookup: the grid orders tiles by unread
            // count, so it needs something it can compare between frames.
            toolBadges = counts.toolMap(),
            viewerDepartment = viewerDepartment(),
            isOtherProject = isOtherProject(),
            isPersonalProject = isPersonalProject(),
            projectType = projectType(),
            onOpenUrl = onOpenUrl,
            onCustomiseTools = customiseToolsRoute?.let { route ->
                { navigator.navigate(WorkspaceRoute.Tool(route)) }
            },
            // Another tool's window, as the web's `openWindow(path)` does.
            onOpenPermissionGrid = permissionGridRoute?.let { route ->
                { navigator.openInNewWindow(WorkspaceRoute.Tool(route)) }
            },
            onEvent = { event ->
                // Through the navigator the host already hands us, rather than a
                // ViewModel passed in: the registry is built before the
                // workspace exists, so depending on it here would be circular.
                when (event) {
                    is HomeEvent.OpenTool -> navigator.openInNewWindow(event.route)
                    else -> viewModel.onEvent(event)
                }
            },
        )
    }

    private companion object {
        const val TOOLS_SEGMENT = "/tools"
    }
}
