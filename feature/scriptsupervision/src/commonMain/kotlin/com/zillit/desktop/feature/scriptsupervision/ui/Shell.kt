package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The tool's own shell — the web's `ScriptSupervisionApp` `Shell()`.
 *
 * The side menu, its filter and the shooting-day picker on the left; the
 * page's title and the global search above the page on the right. The menu
 * collapses, as the web's does since 2026-10-23, and the Tools button goes
 * back to the Film Tools grid (on the web that is a route; here it opens the
 * Tools window).
 *
 * What the web's shell has and this does not: the AI chat float button. The
 * service's AI endpoints are there (`/ai/status`, `/ai/chat`) but the drawer
 * is not ported yet — see the module's README note on what is still open.
 */
@Composable
fun ScriptSupervisionShell(viewModel: ScriptSupervisionViewModel, onOpenTools: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val ctx = remember(state.viewer, state.projectId, onOpenTools) {
        SsCtx(
            api = viewModel.api,
            viewer = state.viewer,
            scope = scope,
            projectId = state.projectId,
            toast = viewModel::toast,
            askRights = viewModel::askRights,
            openTools = onOpenTools,
        )
    }
    val scenes = remember(ctx) { ScenesState(scope, ctx) }
    val cast = remember(ctx) { CastState(scope, ctx) }
    val nav = remember(ctx) { SsNav() }
    // Not before the rights list has confirmed the tool: a call into a
    // production that has it switched off answers 403, and a 403 anywhere in
    // this app reads as "no longer a member".
    LaunchedEffect(ctx, state.viewer.canCall) {
        if (state.viewer.canCall) {
            scenes.reload()
            cast.reload()
        }
    }
    // The stack the menu's page owns, set here so a screen reads one page's
    // position without being told which page it is on. Guarded, because
    // writing state during a composition that did not change it would ask for
    // another one on every frame.
    if (nav.page != state.page) nav.page = state.page

    CompositionLocalProvider(
        LocalSs provides ctx,
        LocalScenes provides scenes,
        LocalCast provides cast,
        LocalNav provides nav,
    ) {
        Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
            when {
                !state.viewer.resolved -> ShellLoading()
                state.viewer.isBlocked -> NoAccess()
                else -> ShellBody(state.page, viewModel::open)
            }
            state.toast?.let { notice ->
                ZillitToast(
                    message = notice.text,
                    tone = if (notice.ok) ZillitToastTone.Success else ZillitToastTone.Danger,
                    onDismiss = viewModel::dismissToast,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(ZillitTheme.spacing.lg),
                )
            }
        }
    }
}

@Composable
private fun ShellLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MutedText(t("loading")) }
}

@Composable
private fun NoAccess() {
    ZillitEmptyState(title = t("no_access_title"), message = t("no_access_hint"))
}

@Composable
private fun ShellBody(page: SsPage, onOpen: (SsPage) -> Unit) {
    var collapsed by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize()) {
        SideMenu(
            page = page,
            collapsed = collapsed,
            filter = filter,
            onFilter = { filter = it },
            onCollapse = { collapsed = !collapsed },
            onOpen = onOpen,
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitScrollColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                SsRoutes(page, onOpen)
            }
        }
    }
}

/**
 * The menu: the Tools button and the collapse toggle at the top (the web moved
 * them there on 2026-10-23), then the filter, the shooting-day picker, and the
 * grouped rows.
 *
 * Collapsed, the rows go and the two buttons stay — a reader who has collapsed
 * the menu still has to be able to leave the tool.
 */
@Suppress("LongMethod") // The menu collapsed, then expanded: two layouts in one place.
@Composable
private fun SideMenu(
    page: SsPage,
    collapsed: Boolean,
    filter: String,
    onFilter: (String) -> Unit,
    onCollapse: () -> Unit,
    onOpen: (SsPage) -> Unit,
) {
    val ctx = LocalSs.current
    val scenes = scenes()
    if (collapsed) {
        Column(
            Modifier.width(COLLAPSED_WIDTH).fillMaxHeight().padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZillitIconButton(
                icon = ZillitIcons.Expand,
                contentDescription = t("expand_menu"),
                onClick = onCollapse,
            )
            ZillitIconButton(
                icon = ZillitIcons.Tools,
                contentDescription = t("back_to_tools"),
                onClick = ctx.openTools,
            )
        }
        return
    }
    val sections = SsGroup.entries.mapNotNull { group ->
        val rows = SsPage.entries
            .filter { it.group == group }
            .filter { filter.isBlank() || t(it.labelKey).contains(filter.trim(), ignoreCase = true) }
            .map { SideNavItem(id = it.route, label = t(it.labelKey), icon = it.icon) }
        if (rows.isEmpty()) null else SideNavSection(title = group.labelKey?.let { t(it) }, items = rows)
    }
    ZillitSideNav(
        sections = sections,
        activeId = page.route,
        onSelect = { route -> onOpen(SsPage.of(route)) },
        modifier = Modifier.fillMaxHeight(),
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitButton(
                        text = t("back_to_tools"),
                        onClick = ctx.openTools,
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Tools,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Collapse,
                        contentDescription = t("collapse_menu"),
                        onClick = onCollapse,
                    )
                }
                ZillitSearchField(
                    value = filter,
                    onValueChange = onFilter,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = t("filter_menu"),
                )
                // Only when the production HAS shooting days: an empty picker
                // would offer a filter that cannot be set.
                if (scenes.days.isNotEmpty()) {
                    val options = scenes.days.map { it.toString() to "${t("day")} $it" }
                    ZillitSearchSelect(
                        value = options.firstOrNull { it.first == scenes.day?.toString() },
                        options = options,
                        onSelect = { scenes.day = it.first.toIntOrNull() },
                        label = { it.second },
                        placeholder = t("all_days"),
                        onClear = { scenes.day = null }.takeIf { scenes.day != null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

private val COLLAPSED_WIDTH = 56.dp

/** The tool's name, for the window title and the Film Tools tile. */
internal fun toolTitle(): String = str(S.desktop_ss_tool_name)
