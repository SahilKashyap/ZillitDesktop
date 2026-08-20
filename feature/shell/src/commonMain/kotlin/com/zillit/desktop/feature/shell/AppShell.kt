package com.zillit.desktop.feature.shell

import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import androidx.compose.foundation.layout.Box
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WorkspaceEvent
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.workspace.WorkspaceViewModel
import com.zillit.desktop.core.workspace.ui.Workspace
import com.zillit.desktop.core.workspace.ui.WorkspaceTabStrip

/**
 * The application frame (plan §3.3): top bar, left rail, workspace, status bar.
 *
 * The frame owns no content of its own — the workspace hosts whatever windows
 * are open, and features reach it only through `ToolProvider`.
 */
@Composable
fun AppShell(
    viewModel: WorkspaceViewModel,
    registry: ToolRegistry,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    projectName: String? = null,
    railItems: List<RailItem> = DefaultRailItems,
    /** Logout at the rail's foot; null hides it. The frame confirms before this fires. */
    onSignOut: (() -> Unit)? = null,
    /**
     * Where the Zillit mark leads; null makes it plain text.
     *
     * The phones put the notification list behind the logo in the app bar
     * (Android `BottomNavigationActivity:544` — `imgLogo` starts
     * `NotificationActivity`, with the unread count pinned beside it), and the
     * desktop keeps that: one mark, in the corner every window has.
     */
    notificationsRoute: WorkspaceRoute? = null,
    /** What the mark wears — the global unread count. */
    notificationBadge: Int = 0,
    onSwitchProject: () -> Unit = {},
    /**
     * The connection line in the status bar.
     *
     * Supplied rather than derived: the shell does not know about sockets, and
     * the app maps its connection state to words exactly once.
     */
    statusText: String = "",
    /**
     * A second, clickable status — the sync queue's "3 changes waiting",
     * opening the pending list. Null when there is nothing to say.
     */
    statusAction: StatusAction? = null,
    /**
     * Unread count for a window's tab.
     *
     * A lambda rather than a map so the shell never holds badge state — the
     * counts live in one store and every surface reads through it.
     */
    badgeFor: (WorkspaceRoute) -> Int = { 0 },
) {
    val state by viewModel.state.collectAsState()
    // The rail asks; the frame confirms. A dialog composed inside the rail is
    // laid out inside a 60pt column — see [SignOutDialog].
    var confirmingSignOut by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = ZillitTheme.colors.canvas) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    projectName = projectName,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onSwitchProject = onSwitchProject,
                    onOpenNotifications = notificationsRoute?.let {
                        { viewModel.onEvent(WorkspaceEvent.Open(it)) }
                    },
                    notificationBadge = notificationBadge,
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)

                RailAndWorkspace(
                    viewModel = viewModel,
                    registry = registry,
                    railItems = railItems,
                    onRequestSignOut = onSignOut?.let { { confirmingSignOut = true } },
                    badgeFor = badgeFor,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                HorizontalDivider(color = ZillitTheme.colors.divider)
                StatusBar(statusText = statusText, action = statusAction, unsaved = state.hasDirtyWindows)
            }

            // Above the frame rather than inside the rail: it is a question
            // about the whole session, and the rail is 60pt wide.
            onSignOut?.let { signOut ->
                SignOutDialog(
                    visible = confirmingSignOut,
                    onDismiss = { confirmingSignOut = false },
                    onConfirm = {
                        confirmingSignOut = false
                        signOut()
                    },
                )
            }
        }
    }
}

/**
 * The middle band: the rail, and the workspace beside it.
 *
 * Its own composable so [AppShell] stays a list of the frame's four regions.
 */
@Composable
private fun RailAndWorkspace(
    viewModel: WorkspaceViewModel,
    registry: ToolRegistry,
    railItems: List<RailItem>,
    onRequestSignOut: (() -> Unit)?,
    badgeFor: (WorkspaceRoute) -> Int,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    Row(modifier) {
        NavigationRail(
            items = railItems,
            activePath = state.activeWindow?.route?.path,
            onOpen = { route -> viewModel.onEvent(WorkspaceEvent.Open(route)) },
            onSignOut = onRequestSignOut,
        )
        VerticalDivider(color = ZillitTheme.colors.divider)

        Column(Modifier.weight(1f)) {
            WorkspaceTabStrip(
                state = state,
                onEvent = viewModel::onEvent,
                iconFor = { window -> registry.resolve(window.route)?.iconFor(window.route) ?: ZillitIcons.Tools },
                badgeFor = { window -> badgeFor(window.rootRoute) },
            )
            HorizontalDivider(color = ZillitTheme.colors.divider)
            // Switches between the tab workspace and free-floating cascade
            // windows; a tool cannot tell which it is in.
            Workspace(
                state = state,
                registry = registry,
                onEvent = viewModel::onEvent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TopBar(
    projectName: String?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSwitchProject: () -> Unit,
    /** What the Zillit mark opens — the notification list. Null makes it plain text. */
    onOpenNotifications: (() -> Unit)? = null,
    notificationBadge: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ZillitDimens.topBarHeight)
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            BrandMark(badge = notificationBadge, onOpenNotifications = onOpenNotifications)
            ProjectSwitcher(projectName = projectName, onClick = onSwitchProject)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ThemeToggle(themeMode = themeMode, onChange = onThemeModeChange)
            ZillitIconButton(
                icon = ZillitIcons.Search,
                contentDescription = "Search",
                onClick = { },
            )
            ZillitIconButton(
                icon = ZillitIcons.User,
                contentDescription = "Profile",
                onClick = { },
            )
        }
    }
}

/**
 * The Zillit mark, and the way into the notification list.
 *
 * The phones do exactly this — Android's toolbar logo starts
 * `NotificationActivity` and wears the unread count beside it
 * (`custom_toolbar_main.xml`'s `imgLogo` + `zBadge`) — and it saves the bar an
 * icon: the mark is already in the corner, and it is the one thing on the bar
 * that is about the app rather than the production.
 */
@Composable
private fun BrandMark(badge: Int, onOpenNotifications: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered && onOpenNotifications != null) colors.surfaceHover else Color.Transparent)
            .then(
                if (onOpenNotifications == null) {
                    Modifier
                } else {
                    Modifier
                        .hoverable(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = "Notifications",
                            onClick = onOpenNotifications,
                        )
                },
            )
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "Zillit",
            style = ZillitTheme.typography.titleMedium,
            color = colors.accent,
        )
        // Beside the mark rather than over it: the count is a number people
        // read, and a badge on a wordmark clips its last letter.
        if (badge > 0) ZillitBadge(count = badge)
    }
}

/**
 * Cycles Light → Dark → System.
 *
 * A three-state cycle rather than a two-state switch, because "follow the OS"
 * is a real preference and a binary toggle silently drops it.
 */
/**
 * The open production, and the way out of it.
 *
 * On the production name itself rather than buried in a menu: it is the only
 * thing on the bar that names where you are, so it is where anyone looks first
 * when they want to be somewhere else. The web hides the same action inside the
 * side menu, several clicks from the name it changes.
 */
@Composable
private fun ProjectSwitcher(projectName: String?, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Switch production",
                onClick = onClick,
            )
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = projectName ?: "No production selected",
            style = ZillitTheme.typography.bodySmall,
            color = if (hovered) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        ZillitIcon(
            icon = ZillitIcons.ChevronDown,
            contentDescription = "Switch production",
            tint = if (hovered) colors.textPrimary else colors.textMuted,
            size = SWITCHER_CHEVRON,
        )
    }
}

@Composable
private fun ThemeToggle(themeMode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val (icon, label, next) = when (themeMode) {
        ThemeMode.Light -> Triple(ZillitIcons.Sun, "Light theme", ThemeMode.Dark)
        ThemeMode.Dark -> Triple(ZillitIcons.Moon, "Dark theme", ThemeMode.System)
        ThemeMode.System -> Triple(ZillitIcons.Monitor, "Following system theme", ThemeMode.Light)
    }
    ZillitIconButton(
        icon = icon,
        contentDescription = label,
        onClick = { onChange(next) },
    )
}

/**
 * Connection and sync state.
 *
 * Deliberately does *not* repeat the open-window count — the tab strip already
 * shows it, and the same number in two places is noise the eye has to filter.
 */
@Composable
private fun StatusBar(statusText: String, action: StatusAction?, unsaved: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ZillitDimens.statusBarHeight)
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZillitText(
            text = statusText,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            if (action != null) {
                ZillitText(
                    text = action.text,
                    style = ZillitTheme.typography.labelSmall,
                    color = if (action.attention) ZillitTheme.colors.accent else ZillitTheme.colors.textMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(ZillitTheme.spacing.xs))
                        .clickable(onClick = action.onClick)
                        .padding(horizontal = ZillitTheme.spacing.xs)
                        .testTag("status-action"),
                )
            }
            if (unsaved) {
                ZillitText(
                    text = "Unsaved changes",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.accent,
                )
            }
        }
    }
}

/** A clickable line in the status bar; [attention] paints it in the accent. */
data class StatusAction(
    val text: String,
    val attention: Boolean,
    val onClick: () -> Unit,
)

private val SWITCHER_CHEVRON = 14.dp
