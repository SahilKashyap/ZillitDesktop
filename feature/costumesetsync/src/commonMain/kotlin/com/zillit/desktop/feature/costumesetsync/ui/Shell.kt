package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.feature.costumesetsync.domain.CsyncBadges
import com.zillit.desktop.feature.costumesetsync.domain.SCAN_ENABLED
import com.zillit.desktop.feature.costumesetsync.ui.screens.FirstRun
import kotlin.time.Clock

private val SEARCH_WIDTH = 380.dp

/** One page inside a tab group. Its badge is its unread notifications ([CsyncBadges.unitForPath]). */
private data class NavItem(val to: String, val labelKey: String)

/** A primary tab; a group lists its pages as a second strip. */
private data class NavTab(
    val id: String,
    val labelKey: String,
    val start: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val items: List<NavItem> = emptyList(),
    val financeOnly: Boolean = false,
    /** Shown only while `SCAN_ENABLED` is on (the web's `scan: true`). */
    val scanOnly: Boolean = false,
)

private val TABS = listOf(
    NavTab("dashboard", "csync_nav_dashboard", "dashboard", ZillitIcons.Grid),
    NavTab("breakdown", "csync_nav_scenes", "breakdown", ZillitIcons.Calendar),
    NavTab(
        "character", "csync_nav_character_breakdown", "characters", ZillitIcons.Users,
        listOf(NavItem("characters", "csync_nav_characters"), NavItem("actors", "csync_nav_actors")),
    ),
    NavTab(
        "costumes", "csync_nav_costumes", "costumes", ZillitIcons.Tag,
        // The menu shows unread notification badges only, no work counts (was:
        // the dashboard's figures, until 2026-10-05).
        listOf(
            NavItem("costumes", "csync_nav_costumes"),
            NavItem("fittings", "csync_nav_fittings"),
            NavItem("cleaning", "csync_nav_cleaning"),
            NavItem("alterations", "csync_nav_alterations"),
            NavItem("damages", "csync_nav_damage"),
            NavItem("missing", "csync_nav_missing"),
            NavItem("labels", "csync_nav_qr_labels"),
            NavItem("vendors", "csync_nav_vendors_rentals"),
        ),
    ),
    NavTab(
        "continuity", "csync_nav_continuity", "continuity", ZillitIcons.Camera,
        listOf(NavItem("continuity", "csync_nav_on_set"), NavItem("continuity/book", "csync_nav_book")),
    ),
    NavTab("reports", "csync_nav_reports", "reports", ZillitIcons.BarChart),
    NavTab("budget", "csync_nav_budget", "budget", ZillitIcons.Wallet, financeOnly = true),
    NavTab("gallery", "csync_nav_gallery", "gallery", ZillitIcons.Photo),
    // Hidden, as on the web: see SCAN_ENABLED.
    NavTab("scan", "csync_nav_scan", "scan", AhIcons.QrCode, scanOnly = true),
)

/** Which primary tab lights for a route head — a record's own page lights its list's tab. */
private fun tabOf(head: String): String? = when (head) {
    "dashboard" -> "dashboard"
    "breakdown", "scenes", "changes" -> "breakdown"
    "characters", "actors" -> "character"
    "costumes", "fittings", "cleaning", "alterations", "damages", "missing", "labels", "vendors" -> "costumes"
    "continuity" -> "continuity"
    "reports" -> "reports"
    "budget" -> "budget"
    "gallery" -> "gallery"
    "scan" -> "scan"
    else -> null
}

private fun itemOf(route: SyncRoute): String =
    if (route.head == "continuity" && route.segments.getOrNull(1) == "book") "continuity/book" else route.head

/**
 * The tool's frame: header, the two tab strips, the page. A primary strip picks
 * the section; a group (Character Breakdown, Costumes, Continuity) lists its
 * pages as a second strip under it, with the live figures the web's menu carries.
 *
 * Nothing under it renders until the rights list has confirmed the tool and
 * the service's meta and production record have answered, so no screen can
 * make the speculative request that would read as a 403.
 */
@Composable
fun SyncOnsetShell(viewModel: SyncOnsetViewModel, onTitle: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val nav = remember { SyncNav() }
    var toast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // The tool's unread notifications, per unit, from Zillit's badge ledger.
    val unread by viewModel.badges.unread.collectAsState(emptyMap())
    val projectId = viewModel.projectId()
    // Setup finished in this visit: open the tool even though the counts are still 0 (a setup without a script adds no
    // scenes).
    var setUpHere by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(viewModel, projectId) { viewModel.onEvent(SyncHostEvent.Load) }
    LaunchedEffect(Unit) { onTitle(str(S.desktop_csync_tool_name)) }

    val ctx = remember(state, projectId, nav) {
        SyncCtx(
            api = viewModel.api,
            viewer = state.viewer,
            project = state.project,
            meta = state.meta,
            nav = nav,
            scope = scope,
            frames = viewModel.frames,
            projectId = projectId,
            currentUserId = viewModel.userId(),
            toast = { text, ok -> toast = text to ok },
            askRights = { kind -> viewModel.onEvent(SyncHostEvent.AskRights(kind)) },
            // The web still dispatches `csync:changed` after a write and nothing
            // listens any more: the nav counts it fed went with the badges.
            changed = {},
            now = { Clock.System.now().toEpochMilliseconds() },
            host = viewModel.host,
            badges = viewModel.badges,
        )
    }

    Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            when {
                !state.viewer.resolved -> Gate(str(S.desktop_csync_loading), null)
                !state.viewer.enabled -> Gate(str(S.desktop_csync_gate_off_title), str(S.desktop_csync_gate_off_hint))
                !state.viewer.canView -> Gate(
                    str(S.desktop_csync_gate_noview_title),
                    str(S.desktop_csync_gate_noview_hint),
                )
                !state.projectReady || state.meta == null -> Gate(str(S.desktop_csync_loading), null)
                else -> CompositionLocalProvider(LocalSync provides ctx) {
                    SyncFrame(
                        unread = unread,
                        setUpHere = setUpHere,
                        onSetUp = { setUpHere = true },
                        onReloadProject = { viewModel.onEvent(SyncHostEvent.ReloadProject) },
                    )
                }
            }
        }
        ZillitToast(
            message = toast?.first,
            onDismiss = { toast = null },
            tone = if (toast?.second == true) ZillitToastTone.Success else ZillitToastTone.Danger,
        )
    }
}

@Composable
private fun Gate(title: String, hint: String?) {
    EmptyState(title = title, hint = hint, modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl))
}

@Composable
internal fun SyncFrame(
    unread: Map<String, Int>,
    setUpHere: Boolean,
    onSetUp: () -> Unit,
    onReloadProject: () -> Unit,
) {
    val ctx = LocalSync.current
    // A production with nothing in it opens to the first-run landing instead of the tabs (the web's `FirstRun`).
    if (ctx.project.notSetUp && !setUpHere) {
        TopBar(search = false)
        FirstRun(
            onChanged = onReloadProject,
            onDone = {
                onSetUp()
                ctx.nav.go("breakdown")
            },
        )
        return
    }
    val route = ctx.nav.current
    val activeTab = tabOf(route.head)
    val tabs = TABS.filter { (!it.financeOnly || ctx.isFinance) && (!it.scanOnly || SCAN_ENABLED) }
    fun unreadOf(to: String): Int = CsyncBadges.unitForPath(to)?.let { unread[it] } ?: 0

    ReadPageUnread(route, unread)
    TopBar(search = true)
    SyncTabBar(
        tabs = tabs.map { tab ->
            SyncTabModel(
                id = tab.id,
                label = t(tab.labelKey),
                icon = tab.icon,
                start = tab.start,
                items = tab.items.map { SyncTabItem(it.to, t(it.labelKey), unreadOf(it.to)) },
                count = if (tab.items.isEmpty()) unreadOf(tab.start) else 0,
            )
        },
        activeId = activeTab,
        activeItem = itemOf(route),
        onGo = ctx.nav::go,
    )
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.surfaceSunken),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xl),
    ) {
        SyncRoutes(route)
    }
}

/**
 * The page's own unread, read as it is looked at — keyed on the live count, so
 * one that arrives while the page is open clears too, and on the unit, so
 * Continuity ▸ Book never clears On set's call sheet.
 *
 * "Production created" has no tab: opening the module is reading it, scoped to
 * that `level_1` so the record-chat rows under the same unit are left alone.
 */
@Composable
private fun ReadPageUnread(route: SyncRoute, unread: Map<String, Int>) {
    val ctx = LocalSync.current
    val pageUnit = CsyncBadges.unitForPath(route.path)
    val pageUnread = pageUnit?.let { unread[it] } ?: 0
    LaunchedEffect(pageUnit, pageUnread) {
        if (pageUnit != null && pageUnread > 0) ctx.badges.readUnit(pageUnit)
    }
    val general = unread[CsyncBadges.GENERAL] ?: 0
    LaunchedEffect(general) { if (general > 0) ctx.badges.readProductionCreated() }
}

/** The top bar. [search] is off on the first-run landing, where there is nothing to find yet (the web's `bare`). */
@Composable
private fun TopBar(search: Boolean) {
    val ctx = LocalSync.current
    // The web's `.csync-topbar`: a 60px bar on the surface with a bottom rule, 18px bold title, then the
    // search and (on the web) a theme toggle — omitted here, the desktop has no per-tool theme. There is no
    // bell: unread notifications are Zillit's own, shown on the tabs (`CsyncBadges`).
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(ZillitTheme.colors.surface).padding(
            horizontal = 16.dp,
            vertical = 10.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(
            str(S.desktop_csync_tool_name),
            style = ZillitTheme.typography.titleLarge.copy(
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                letterSpacing = (-0.18).sp,
            ),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (!ctx.canPost) {
            ZillitButton(
                t("csync_request_posting_access"),
                onClick = { ctx.askRights(RightsKind.Post) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        if (!ctx.canDownload) {
            ZillitButton(
                t("csync_request_download_access"),
                onClick = { ctx.askRights(RightsKind.Download) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        if (search) GlobalSearch(Modifier.width(SEARCH_WIDTH))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}
