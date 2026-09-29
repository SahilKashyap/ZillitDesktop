package com.zillit.desktop.feature.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLanguageMenu
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.ViewMode
import com.zillit.desktop.core.workspace.WorkspaceEvent
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.workspace.WorkspaceViewModel
import com.zillit.desktop.core.workspace.ui.Workspace
import com.zillit.desktop.core.workspace.ui.WorkspaceTabStrip
import kotlinx.coroutines.delay

/**
 * The application frame (plan §3.3): top bar, left rail, workspace, status bar.
 *
 * The frame owns no content of its own — the workspace hosts whatever windows
 * are open, and features reach it only through `ToolProvider`.
 */
@Composable
// The frame's regions plus its two overlays, each a single call: splitting
// them out would only move the list somewhere else.
@Suppress("LongMethod")
fun AppShell(
    viewModel: WorkspaceViewModel,
    registry: ToolRegistry,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    /**
     * The stored language preference — a code, or blank for "follow the
     * system" — and where a new choice goes. Beside the theme toggle because
     * they are the two things about the app itself that someone changes
     * from wherever they happen to be.
     */
    language: String = "",
    onLanguageChange: (String) -> Unit = {},
    projectName: String? = null,
    railItems: List<RailItem> = DefaultRailItems,
    /** Logout at the rail's foot; null hides it. The frame confirms before this fires. */
    onSignOut: (() -> Unit)? = null,
    /**
     * Where the rail's Classic/Windowed switch sends the new view — the app
     * stores it and feeds it back as `WorkspaceEvent.SetViewMode`. Null hides
     * the switch.
     */
    onViewModeChange: ((ViewMode) -> Unit)? = null,
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
    /**
     * A newer build exists; null renders nothing.
     *
     * A frame-level value rather than a tool, because it is a fact about the
     * application rather than about the production, and because the one place
     * every window already shares is the strip under the top bar. The app maps
     * `core:appupdate`'s `UpdateStatus` onto this — `Unknown` and `UpToDate`
     * both become null.
     */
    updateNotice: UpdateNotice? = null,
    /**
     * Opens the download page. Supplied as a lambda so the URL passes through
     * the app's guarded https-only launcher (`BrowserLauncher.openInBrowser`)
     * rather than anything this module could reach.
     */
    onDownloadUpdate: (String) -> Unit = {},
    /** Starts the in-app download, when [UpdateNotice.install] offers one. */
    onInstallUpdate: () -> Unit = {},
    /** Stops an update download in progress. */
    onCancelUpdate: () -> Unit = {},
    /** Opens a downloaded installer again. */
    onOpenUpdate: () -> Unit = {},
    /** Hands the staged build to the installer and quits; the helper reopens Zillit. */
    onRestartToUpdate: () -> Unit = {},
    /** Quit, offered on [ForceUpdateScreen]; null leaves it off. */
    onQuit: (() -> Unit)? = null,
    /**
     * When this production's scheduled deletion falls due, in epoch millis —
     * `ProjectContext.deletionDueAtMillis`, which is re-read whenever the
     * production's record changes, so a deletion called off elsewhere clears
     * this without a project switch. Null for the overwhelming majority of
     * productions, which are not going anywhere.
     */
    deletionDueAtMillis: Long? = null,
    /** Whether the rail is narrowed to icons. Open by default — see [NavigationRail]. */
    railCollapsed: Boolean = false,
    /** Null hides the rail's collapse arrow. */
    onRailCollapsedChange: ((Boolean) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    // The rail asks; the frame confirms. A dialog composed inside the rail is
    // laid out inside a 60pt column — see [SignOutDialog].
    var confirmingSignOut by remember { mutableStateOf(false) }

    // Dismissal is per version and per session, held here rather than
    // persisted. Per version, because dismissing 1.2.0 must not also silence
    // 1.3.0 — a preference keyed on nothing but "seen" is how an update notice
    // becomes permanently invisible. Per session, because a strip this cheap
    // does not need to survive a restart to be worth showing again.
    var dismissed by remember { mutableStateOf<UpdateDismissal?>(null) }
    val notice = updateNotice?.takeIf { !it.blocking && it.shownAfter(dismissed) }

    Surface(modifier = Modifier.fillMaxSize(), color = ZillitTheme.colors.canvas) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    projectName = projectName,
                    deletionDueAtMillis = deletionDueAtMillis,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    language = language,
                    onLanguageChange = onLanguageChange,
                    onSwitchProject = onSwitchProject,
                    onOpenNotifications = notificationsRoute?.let {
                        { viewModel.onEvent(WorkspaceEvent.Open(it)) }
                    },
                    notificationBadge = notificationBadge,
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)

                // Under the bar and above everything else: it is about the
                // application, not about whichever window happens to be open.
                // It takes a strip of height and blocks nothing.
                notice?.let {
                    UpdateBanner(
                        notice = it,
                        onDownload = onDownloadUpdate,
                        onInstall = onInstallUpdate,
                        onRestart = onRestartToUpdate,
                        onCancel = onCancelUpdate,
                        onOpenDownloaded = onOpenUpdate,
                        onDismiss = { dismissed = UpdateDismissal(it.latestVersion, it.requests) },
                    )
                }

                RailAndWorkspace(
                    viewModel = viewModel,
                    registry = registry,
                    railItems = railItems,
                    onRequestSignOut = onSignOut?.let { { confirmingSignOut = true } },
                    onViewModeChange = onViewModeChange,
                    badgeFor = badgeFor,
                    railCollapsed = railCollapsed,
                    onRailCollapsedChange = onRailCollapsedChange,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                HorizontalDivider(color = ZillitTheme.colors.divider)
                StatusBar(statusText = statusText, action = statusAction, unsaved = state.hasDirtyWindows)
            }

            // Over everything, sign-out included: below the floor nothing in
            // the frame is usable.
            updateNotice?.takeIf { it.blocking }?.let {
                ForceUpdateScreen(
                    notice = it,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                    onRestart = onRestartToUpdate,
                    onCancel = onCancelUpdate,
                    onOpenDownloaded = onOpenUpdate,
                    onQuit = onQuit,
                )
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
    onViewModeChange: ((ViewMode) -> Unit)?,
    badgeFor: (WorkspaceRoute) -> Int,
    railCollapsed: Boolean,
    onRailCollapsedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val classic = state.viewMode == ViewMode.Classic
    Row(modifier) {
        NavigationRail(
            items = railItems,
            activePath = state.activeWindow?.route?.path,
            onOpen = { route -> viewModel.onEvent(WorkspaceEvent.Open(route)) },
            onSignOut = onRequestSignOut,
            classicView = classic.takeIf { onViewModeChange != null },
            onToggleViewMode = {
                onViewModeChange?.invoke(if (classic) ViewMode.Windowed else ViewMode.Classic)
            },
            collapsed = railCollapsed,
            onToggleCollapsed = onRailCollapsedChange?.let { change -> { change(!railCollapsed) } },
        )
        VerticalDivider(color = ZillitTheme.colors.divider)

        Column(Modifier.weight(1f)) {
            // Classic is full-page navigation, as on the web: no tab strip,
            // the rail alone moves between tools.
            if (!classic) {
                WorkspaceTabStrip(
                    state = state,
                    onEvent = viewModel::onEvent,
                    iconFor = { window -> registry.resolve(window.route)?.iconFor(window.route) ?: ZillitIcons.Tools },
                    badgeFor = { window -> badgeFor(window.rootRoute) },
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)
            }
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
    deletionDueAtMillis: Long?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
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
            // The mark is about the app, everything right of it about the
            // production — the rule the bar is divided on.
            VerticalDivider(
                color = ZillitTheme.colors.divider,
                modifier = Modifier.height(BAR_DIVIDER_HEIGHT),
            )
            ProjectSwitcher(projectName = projectName, onClick = onSwitchProject)
            // Beside the name it applies to, not in the corner with the app's
            // own controls: it is a fact about this production.
            deletionDueAtMillis?.let { DeletionCountdown(dueAtMillis = it) }
        }

        // Language and theme, nothing else. Search and Profile stood here
        // doing nothing — a magnifier that searched nothing and a person that
        // opened nobody. Search lives in each tool that has something to
        // search, and the account is in Settings; two dead controls in the
        // app's most-looked-at corner taught people the bar is decorative.
        //
        // Both wear their words: a globe and a monitor side by side were two
        // grey glyphs nobody could tell apart without clicking one.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitLanguageMenu(
                selected = language,
                onSelect = onLanguageChange,
                label = str(S.desktop_switch_language),
            )
            ThemeToggle(themeMode = themeMode, onChange = onThemeModeChange)
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
                            onClickLabel = str(S.notifications),
                            onClick = onOpenNotifications,
                        )
                },
            )
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = str(S.app_name),
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
    val outline by animateColorAsState(
        if (hovered) colors.accent else colors.border,
        label = "projectSwitcherOutline",
    )

    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) colors.surfaceHover else colors.surfaceSunken)
            // Drawn as the field it behaves like. Unbordered it read as a
            // caption someone had left in the corner, and the chevron alone
            // was not enough to say the name could be changed.
            .border(SWITCHER_OUTLINE, outline, ZillitTheme.shapes.medium)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = str(S.desktop_switch_project),
                onClick = onClick,
            )
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SWITCHER_LINE_GAP)) {
            // What the control does, above what it currently holds — so the
            // production's name is never mistaken for a label of its own.
            ZillitText(
                text = str(S.desktop_switch_project),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
            ZillitText(
                text = projectName ?: str(S.desktop_no_project),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.widthIn(max = SWITCHER_NAME_MAX),
            )
        }
        ZillitIcon(
            icon = ZillitIcons.ChevronDown,
            contentDescription = str(S.desktop_switch_project),
            tint = if (hovered) colors.accent else colors.textMuted,
            size = SWITCHER_CHEVRON,
        )
    }
}

/**
 * How long this production has left before the scheduled deletion runs.
 *
 * The phones put the same clock in their toolbar
 * (`PersonalProjectPage.startCountdownTimer`), in the same `HH:MM:SS`, with
 * the hours left uncapped — a three-day window reads `71:59:58` rather than
 * being folded into days, so the number never needs a unit beside it to be
 * read correctly.
 *
 * It renders nothing once the deadline passes. Nothing here evicts anybody:
 * the server stops answering for a production that is gone, and inventing a
 * client-side eviction would throw someone out of work the server would still
 * have accepted.
 */
@Composable
private fun DeletionCountdown(dueAtMillis: Long) {
    var remaining by remember(dueAtMillis) { mutableStateOf(dueAtMillis - nowMillis()) }
    LaunchedEffect(dueAtMillis) {
        // Re-read the clock each tick rather than subtracting a second: a
        // machine that slept would otherwise keep counting from where it
        // dozed off and show a deadline that has long since passed.
        while (true) {
            remaining = dueAtMillis - nowMillis()
            if (remaining <= 0) break
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }
    if (remaining <= 0) return

    val colors = ZillitTheme.colors
    ZillitTooltip(text = str(S.desktop_marked_delete)) {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.medium)
                .background(colors.dangerSoft)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Clock,
                contentDescription = null,
                tint = colors.danger,
                size = SWITCHER_CHEVRON,
            )
            ZillitText(
                text = str(S.desktop_deleted_in, countdownText(remaining)),
                style = ZillitTheme.typography.labelSmall,
                color = colors.danger,
                maxLines = 1,
            )
        }
    }
}

/** `HH:MM:SS`, hours uncapped — the phones' own format. */
internal fun countdownText(remainingMillis: Long): String {
    val total = (remainingMillis / MILLIS_PER_SECOND).coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return listOf(hours, minutes, seconds).joinToString(":") { it.toString().padStart(2, '0') }
}

private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

@Composable
private fun ThemeToggle(themeMode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val (icon, mode, next) = when (themeMode) {
        ThemeMode.Light -> Triple(ZillitIcons.Sun, str(S.desktop_theme_light), ThemeMode.Dark)
        ThemeMode.Dark -> Triple(ZillitIcons.Moon, str(S.desktop_theme_dark), ThemeMode.System)
        ThemeMode.System -> Triple(ZillitIcons.Monitor, str(S.desktop_theme_system), ThemeMode.Light)
    }
    // The label says what the button does; the icon says which theme is on.
    // A reader who cannot see the icon would be left with a button that never
    // changes, so the mode is the button's state: the tooltip names it in
    // words on hover, and `stateDescription` announces it.
    ZillitTooltip(text = str(S.theme_mode) + " · " + mode) {
        ZillitButton(
            text = str(S.desktop_switch_theme),
            onClick = { onChange(next) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = icon,
            modifier = Modifier.semantics { stateDescription = mode },
        )
    }
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
                    text = str(S.desktop_unsaved_changes),
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

/**
 * What the frame is told about a newer build.
 *
 * Deliberately *not* `core:appupdate`'s `UpdateStatus`. The shell renders a
 * frame; it has no business knowing about Firebase, and `core:appupdate` has no
 * business depending on Compose. The app maps one onto the other in one place,
 * which also collapses the two silent states (`Unknown`, `UpToDate`) into the
 * single thing the frame cares about: null, meaning "render nothing".
 *
 * @param latestVersion shown verbatim, so a build called `1.2.0-rc3` reads as
 *   `1.2.0-rc3` and not as whatever this module thought it should be called.
 * @param mandatory the installed build is below `desktop_min_version`. Removes
 *   the dismiss control and changes the wording — see [UpdateBanner].
 * @param downloadUrl null when nothing published a usable https URL. The banner
 *   still appears; it simply has no button, because "a newer version exists" is
 *   worth knowing even when we cannot say where to get it.
 * @param installedVersion what this build is, named in the strip so "1.0.3 is
 *   available" is read next to "you have 1.0.2" — the two numbers are the
 *   whole message. Null leaves it out.
 * @param install where the in-app update has got to; null when this build
 *   cannot install itself (no installer published, Linux, or a Gradle run),
 *   and the strip then offers [downloadUrl] as before.
 */
data class UpdateNotice(
    val latestVersion: String,
    val mandatory: Boolean,
    val downloadUrl: String?,
    val installedVersion: String? = null,
    val install: UpdateInstall? = null,
    /**
     * True when [install] is the download-and-open path: the file is fetched
     * inside the app with a progress bar and handed over, rather than
     * installed. The strip then offers "Download", not "Update now".
     */
    val manual: Boolean = false,
    /**
     * How many times the person has asked for this update from elsewhere —
     * Settings' Download. Each ask re-shows a strip they dismissed: it is
     * where the download's progress and its result appear.
     */
    val requests: Int = 0,
    /**
     * Covers the frame with [ForceUpdateScreen] instead of showing the strip.
     * The app sets it for a mandatory update on a packaged build; a Gradle
     * run keeps the strip, so a raised floor never locks a developer out.
     */
    val blocking: Boolean = false,
)

/** The strip the person closed: which version, and how many asks for it had been made by then. */
internal data class UpdateDismissal(val version: String, val requests: Int)

/**
 * Whether the strip shows, given what the person last dismissed.
 *
 * Dismissed stays dismissed until they ask again, from Settings — whatever
 * step the update is at then: a download that fails at once, or a staged
 * build waiting for "Restart now", is as much the answer to the ask as a
 * progress bar. Nor does a running download ever hide: it could not be
 * cancelled. Nor a restart that is coming on its own: Zillit closing with no
 * warning would look like a crash. Every other step can be dismissed: its X
 * must work.
 */
internal fun UpdateNotice.shownAfter(dismissed: UpdateDismissal?): Boolean =
    mandatory || dismissed == null || latestVersion != dismissed.version || requests != dismissed.requests ||
        install is UpdateInstall.Downloading || (install as? UpdateInstall.Ready)?.automatic == true

/** The in-app update's progress, as the strip words it. */
sealed interface UpdateInstall {
    /** Nothing started yet: the strip offers "Update now". */
    data object Offer : UpdateInstall

    /** @param percent null when the server sent no length. */
    data class Downloading(val percent: Int?) : UpdateInstall

    /** Signature check and staging. */
    data object Preparing : UpdateInstall

    /**
     * Staged; a restart installs it.
     *
     * The app restarts on its own: [restartIn] counts the seconds down, or,
     * with [afterCall], the restart waits for the call to end. With neither,
     * "Restart now" is the only way — an automatic restart already ran for
     * this version and Zillit came back unchanged (a declined password
     * prompt, say), and running it again would loop.
     */
    data class Ready(val restartIn: Int? = null, val afterCall: Boolean = false) : UpdateInstall {
        val automatic: Boolean get() = restartIn != null || afterCall
    }

    /** Saved to Downloads and shown to the person to install. Nothing was installed. */
    data object Downloaded : UpdateInstall

    /**
     * Stopped. [retryable] for a broken download, where trying again may
     * work; a file that failed its checksum or signature will fail again, so
     * the strip sends the reader to the download page instead.
     */
    data class Failed(val retryable: Boolean, val verification: Boolean) : UpdateInstall
}

private val SWITCHER_CHEVRON = 14.dp

/** The switcher's outline, and how far its two lines sit apart. */
private val SWITCHER_OUTLINE = 1.dp
private val SWITCHER_LINE_GAP = 1.dp

/** A long production name is cut rather than pushing the bar's right half off. */
private val SWITCHER_NAME_MAX = 260.dp

/** The rule between the app's mark and the production's name. */
private val BAR_DIVIDER_HEIGHT = 24.dp

private const val COUNTDOWN_TICK_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 60L * 60L
