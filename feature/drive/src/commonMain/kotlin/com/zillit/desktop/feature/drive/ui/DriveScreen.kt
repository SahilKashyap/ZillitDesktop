package com.zillit.desktop.feature.drive.ui

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.ui.pages.ActivityPage
import com.zillit.desktop.feature.drive.ui.pages.BrowsePage
import com.zillit.desktop.feature.drive.ui.pages.DetailsPanel
import com.zillit.desktop.feature.drive.ui.pages.DrivePromptDialog
import com.zillit.desktop.feature.drive.ui.pages.FavouritesPage
import com.zillit.desktop.feature.drive.ui.pages.StoragePage
import com.zillit.desktop.feature.drive.ui.pages.TrashPage
import com.zillit.desktop.feature.drive.ui.pages.UploadStrip

/**
 * The Drive.
 *
 * ## Chrome, then one page, then the details panel
 *
 * The frame is constant — header, tabs, upload progress — the body is whichever
 * [DriveDestination] is open, and the details panel docks beside it rather than
 * over it. Docking matters: inspecting a file is something people do *while*
 * browsing, and a modal makes them close it to click the next row.
 */
@Composable
fun DriveScreen(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The widget's layout: a narrow always-on-top window rather than a full
     * tool page. The header shrinks to tabs and two buttons, the listing
     * drops its secondary columns, and the details panel lays over the list
     * instead of beside it. Everything the wide layout does still works.
     */
    compact: Boolean = false,
    /**
     * Opens the desktop widget — the same drive in a small always-on-top
     * window. Offered in the wide header when the host has one; the widget
     * itself passes nothing here.
     */
    onOpenWidget: (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalDriveCompact provides compact) {
        DriveScreenBody(state, onEvent, modifier, onOpenWidget)
    }
}

/** Whether the Drive is drawn for the widget's narrow window. See [DriveScreen]. */
val LocalDriveCompact = staticCompositionLocalOf { false }

@Composable
private fun DriveScreenBody(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    modifier: Modifier,
    onOpenWidget: (() -> Unit)?,
) {
    val compact = LocalDriveCompact.current
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (state.viewer.isBlocked) {
            ZillitEmptyState(
                title = "No access to Drive",
                message = "An administrator has not granted you view rights for the Drive on " +
                    "this production.",
                icon = ZillitIcons.Shield,
            )
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (compact) CompactHeader(state, onEvent) else DriveHeader(state, onEvent, onOpenWidget)
            ZillitDivider()

            if (state.activeUploads.isNotEmpty()) {
                UploadStrip(state, onEvent)
                ZillitDivider()
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    DriveBody(state, onEvent)
                    // Narrow window: the details take the whole width, over
                    // the listing, and their own Close brings the list back.
                    if (compact && state.details.open) {
                        Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
                            DetailsPanel(state, onEvent)
                        }
                    }
                }
                if (!compact && state.details.open) {
                    // Vertical, not `ZillitDivider` — that one fills its width
                    // and inside a Row it takes the whole thing, collapsing the
                    // listing to nothing. See ZillitVerticalDivider.
                    ZillitVerticalDivider()
                    Box(modifier = Modifier.width(DETAILS_WIDTH.dp).fillMaxHeight()) {
                        DetailsPanel(state, onEvent)
                    }
                }
            }
        }

        DrivePromptDialog(state.prompt, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(DriveEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Composable
private fun DriveHeader(state: DriveUiState, onEvent: (DriveEvent) -> Unit, onOpenWidget: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            eyebrow = "Admin",
            title = "Drive",
            description = "The production's shared files — upload, organise, share and " +
                "version them.",
            actions = {
                if (state.viewer.canCreate) {
                    ZillitButton(
                        text = "Upload",
                        onClick = { onEvent(DriveEvent.PickFiles) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Upload,
                    )
                }
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(DriveEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
                if (onOpenWidget != null) {
                    ZillitButton(
                        text = "Widget",
                        onClick = onOpenWidget,
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Detach,
                    )
                }
            },
        )

        ZillitTabStrip(
            tabs = state.destinations.map { ZillitTab(it.slug, it.label) },
            activeId = state.destination.slug,
            onSelect = { slug ->
                DriveDestination.fromSlug(slug)?.let { onEvent(DriveEvent.Open(it)) }
            },
            size = TabStripSize.Primary,
        )

        // Shown rather than silently hiding Upload: someone whose Upload button
        // is simply absent has no way to learn it is a rights question.
        if (state.viewer.ready && !state.viewer.canCreate) {
            ZillitNotice(
                text = "You can browse this drive but cannot upload to it or create folders. " +
                    "Ask an administrator for posting rights on the Drive.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }
    }
}

/**
 * The widget's header: the tabs a small window can use, Upload and Refresh
 * as icons. Activity and Storage are wide tables and stay in the main app.
 */
@Composable
private fun CompactHeader(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(Modifier.weight(1f)) {
            ZillitTabStrip(
                tabs = state.destinations.filter { it in COMPACT_TABS }.map { ZillitTab(it.slug, it.label) },
                activeId = state.destination.slug,
                onSelect = { slug -> DriveDestination.fromSlug(slug)?.let { onEvent(DriveEvent.Open(it)) } },
                size = TabStripSize.Primary,
            )
        }
        if (state.viewer.canCreate) {
            ZillitIconButton(
                icon = ZillitIcons.Upload,
                contentDescription = "Upload",
                onClick = { onEvent(DriveEvent.PickFiles) },
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Reload,
            contentDescription = "Refresh",
            onClick = { onEvent(DriveEvent.Refresh) },
        )
    }
}

private val COMPACT_TABS = setOf(DriveDestination.Browse, DriveDestination.Favourites, DriveDestination.Trash)

@Composable
private fun DriveBody(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val failure = state.error
    if (failure != null && !state.loading) {
        ZillitErrorState(message = failure, onRetry = { onEvent(DriveEvent.Refresh) })
        return
    }

    when (state.destination) {
        DriveDestination.Browse -> BrowsePage(state, onEvent)
        DriveDestination.Favourites -> FavouritesPage(state, onEvent)
        DriveDestination.Trash -> TrashPage(state, onEvent)
        DriveDestination.Activity -> ActivityPage(state, onEvent)
        DriveDestination.Storage -> StoragePage(state, onEvent)
    }
}

private const val DETAILS_WIDTH = 360
