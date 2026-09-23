package com.zillit.desktop.feature.drive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.ui.pages.ActivityLogSheet
import com.zillit.desktop.feature.drive.ui.pages.DetailsPanel
import com.zillit.desktop.feature.drive.ui.pages.DragOverlay
import com.zillit.desktop.feature.drive.ui.pages.DragToFolderState
import com.zillit.desktop.feature.drive.ui.pages.DriveGrid
import com.zillit.desktop.feature.drive.ui.pages.DriveHeaderPanel
import com.zillit.desktop.feature.drive.ui.pages.DrivePromptDialog
import com.zillit.desktop.feature.drive.ui.pages.DriveTable
import com.zillit.desktop.feature.drive.ui.pages.DropPermissionsDialog
import com.zillit.desktop.feature.drive.ui.pages.EditItemSheet
import com.zillit.desktop.feature.drive.ui.pages.FileRequestDialog
import com.zillit.desktop.feature.drive.ui.pages.InnerTabs
import com.zillit.desktop.feature.drive.ui.pages.ItemMenuHost
import com.zillit.desktop.feature.drive.ui.pages.MoveToDialog
import com.zillit.desktop.feature.drive.ui.pages.NewFolderSheet
import com.zillit.desktop.feature.drive.ui.pages.OperationsPanel
import com.zillit.desktop.feature.drive.ui.pages.PreviewDialog
import com.zillit.desktop.feature.drive.ui.pages.ShareSheet
import com.zillit.desktop.feature.drive.ui.pages.TrashView
import com.zillit.desktop.feature.drive.ui.pages.UploadSheet
import com.zillit.desktop.feature.drive.ui.pages.externalFileDrop
import com.zillit.desktop.feature.drive.ui.pages.CompactHeader
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Drive — `DriveManagement.jsx`.
 *
 * ## Chrome, then one surface, then the details panel
 *
 * The header panel (section tabs, breadcrumb, actions, bulk bar) is
 * constant; the body is the listing or the trash; the details panel docks
 * beside it rather than over it. Every drawer and dialog floats above the
 * whole, and the operations panel sits bottom-right while uploads run.
 * The page itself is a drop target: files from the OS land in the open
 * folder after the permissions step.
 */
@Composable
fun DriveScreen(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The widget's layout: a narrow always-on-top window rather than a full
     * tool page. The header shrinks to the section tabs and two buttons, the
     * listing drops its secondary columns, and the details panel lays over
     * the list instead of beside it. Everything the wide layout does still
     * works.
     */
    compact: Boolean = false,
    /**
     * Opens the desktop widget — the same drive in a small always-on-top
     * window. Offered in the wide header when the host has one; the widget
     * itself passes nothing here.
     */
    onOpenWidget: (() -> Unit)? = null,
    /** The clock behind "3 days ago"; zero falls back to absolute dates. */
    now: () -> Long = { 0L },
) {
    CompositionLocalProvider(LocalDriveCompact provides compact, LocalDriveNow provides now) {
        DriveScreenBody(state, onEvent, modifier, onOpenWidget)
    }
}

/** Whether the Drive is drawn for the widget's narrow window. See [DriveScreen]. */
val LocalDriveCompact = staticCompositionLocalOf { false }

/** The wall clock, for relative stamps. See [DriveScreen]. */
val LocalDriveNow = staticCompositionLocalOf<() -> Long> { { 0L } }

@Composable
@Suppress("LongMethod", "ComplexCondition") // The frame: header, body, panel and every overlay, in one place.
private fun DriveScreenBody(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    modifier: Modifier,
    onOpenWidget: (() -> Unit)?,
) {
    val compact = LocalDriveCompact.current
    val drag = remember { DragToFolderState() }
    var dropHover by remember { mutableStateOf(false) }
    val acceptsDrops = state.canCreateHere && !state.showTrash && !state.hasOverlay

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .externalFileDrop(
                enabled = acceptsDrops,
                onHover = { dropHover = it },
                onFiles = { onEvent(DriveEvent.DropFiles(it)) },
            ),
    ) {
        if (state.viewer.isBlocked) {
            ZillitEmptyState(
                title = str(S.desktop_drive_no_access_title),
                message = str(S.desktop_drive_no_access_message),
                icon = ZillitIcons.Shield,
            )
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (compact) CompactHeader(state, onEvent) else DriveHeaderPanel(state, onEvent, onOpenWidget)
            ZillitDivider()
            if (!state.showTrash && !state.isSearching && state.folderId == null && !compact) {
                InnerTabs(state, onEvent)
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    DriveBody(state, onEvent, drag)
                    DragOverlay(drag)
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
                    // listing to nothing.
                    ZillitVerticalDivider()
                    Box(modifier = Modifier.width(DETAILS_WIDTH).fillMaxHeight()) {
                        DetailsPanel(state, onEvent)
                    }
                }
            }
        }

        DropOverlay(visible = dropHover && acceptsDrops, folderName = state.currentFolderName)

        OperationsPanel(
            state = state,
            onEvent = onEvent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(ZillitTheme.spacing.lg),
        )

        ItemMenuHost(state, onEvent)

        UploadSheet(state, onEvent)
        NewFolderSheet(state, onEvent)
        EditItemSheet(state, onEvent)
        ShareSheet(state, onEvent)
        ActivityLogSheet(state, onEvent)
        MoveToDialog(state, onEvent)
        DropPermissionsDialog(state, onEvent)
        PreviewDialog(state, onEvent)
        FileRequestDialog(state, onEvent)
        DrivePromptDialog(state.prompt, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(DriveEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Composable
private fun DriveBody(state: DriveUiState, onEvent: (DriveEvent) -> Unit, drag: DragToFolderState) {
    val failure = state.error
    if (failure != null && !state.loading) {
        ZillitErrorState(message = failure, onRetry = { onEvent(DriveEvent.Refresh) })
        return
    }
    when {
        state.showTrash -> TrashView(state, onEvent)
        state.viewMode == DriveViewMode.Grid -> DriveGrid(state, onEvent, drag)
        else -> DriveTable(state, onEvent, drag)
    }
}

/** "Drop files to upload" over the page while an OS drag hovers — `drive-drop-zone-overlay`. */
@Composable
private fun DropOverlay(visible: Boolean, folderName: String) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val colors = ZillitTheme.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = OVERLAY_SCRIM))
                .padding(ZillitTheme.spacing.xl)
                .border(2.dp, colors.accent, ZillitTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                modifier = Modifier
                    .background(colors.surfaceRaised, ZillitTheme.shapes.large)
                    .padding(ZillitTheme.spacing.xxl),
            ) {
                ZillitIcon(icon = ZillitIcons.Upload, tint = colors.accent, size = OVERLAY_ICON)
                ZillitText(text = str(S.dd_drop_title), style = ZillitTheme.typography.titleLarge)
                ZillitText(
                    text = str(S.desktop_drive_upload_to_folder, folderName),
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                ZillitText(
                    text = str(S.desktop_drive_accepted_types),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

private val DETAILS_WIDTH = 380.dp
private val OVERLAY_ICON = 48.dp
private const val OVERLAY_SCRIM = 0.35f
