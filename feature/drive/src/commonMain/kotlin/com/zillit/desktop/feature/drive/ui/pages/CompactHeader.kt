package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveViewMode

/**
 * The widget's header: the two sections as a segmented control, Upload and
 * Refresh as icons, then search, a back arrow inside a folder, the view
 * toggle and the trash. Sort, tag and favourites filters stay in the main
 * app — a 500 px window has no room for them.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // The widget's whole chrome, one control per row.
internal fun CompactHeader(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitSegmented(
                options = DriveSection.entries.map {
                    ZillitTab(it.name, if (it == DriveSection.MyDrive) "My Drive" else "Shared")
                },
                activeId = state.section.name,
                onSelect = { id ->
                    DriveSection.entries.firstOrNull { it.name == id }?.let { onEvent(DriveEvent.OpenSection(it)) }
                },
                modifier = Modifier.weight(1f),
            )
            if (state.canCreateHere) {
                // The phones' upload sheet: Photo, Video, Document — Android's
                // `ZillitDriveActivity` offers camera, gallery, video and document.
                com.zillit.desktop.core.media.AttachMenu(
                    kinds = DRIVE_UPLOAD_KINDS,
                    icon = ZillitIcons.Upload,
                    contentDescription = "Upload",
                    onPick = { kind -> onEvent(DriveEvent.PickFilesOf(kind)) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.FolderPlus,
                    contentDescription = "New folder",
                    onClick = { onEvent(DriveEvent.OpenNewFolder) },
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Reload,
                contentDescription = "Refresh",
                onClick = { onEvent(DriveEvent.Refresh) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            if (state.breadcrumb.isNotEmpty() && !state.showTrash) {
                ZillitIconButton(
                    icon = ZillitIcons.ArrowLeft,
                    contentDescription = "Back",
                    onClick = { onEvent(DriveEvent.GoBack) },
                )
            }
            ZillitSearchField(
                value = state.searchInput,
                onValueChange = { onEvent(DriveEvent.SearchInput(it)) },
                placeholder = if (state.showTrash) "Trash" else state.currentFolderName,
                enabled = !state.showTrash,
                modifier = Modifier.weight(1f),
            )
            ZillitIconButton(
                icon = if (state.viewMode == DriveViewMode.List) ZillitIcons.Grid else ZillitIcons.Ledger,
                contentDescription = "Switch view",
                onClick = {
                    onEvent(
                        DriveEvent.SetViewMode(
                            if (state.viewMode == DriveViewMode.List) DriveViewMode.Grid else DriveViewMode.List,
                        ),
                    )
                },
            )
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = if (state.showTrash) "Close trash" else "Trash",
                onClick = { onEvent(DriveEvent.ShowTrash(!state.showTrash)) },
                tint = if (state.showTrash) ZillitTheme.colors.danger else null,
            )
        }
        if (state.selected.isNotEmpty() && !state.showTrash) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    ZillitButton(
                        text = "${state.selected.size} selected · Clear",
                        onClick = { onEvent(DriveEvent.ClearSelection) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                if (state.movableCount > 0) {
                    ZillitIconButton(
                        icon = ZillitIcons.ArrowRight,
                        contentDescription = "Move selection",
                        onClick = { onEvent(DriveEvent.OpenMoveSelection) },
                    )
                }
                if (state.downloadableCount > 0) {
                    ZillitIconButton(
                        icon = ZillitIcons.Download,
                        contentDescription = "Download selection",
                        onClick = { onEvent(DriveEvent.DownloadSelection) },
                    )
                }
                if (state.deletableCount > 0) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Delete selection",
                        onClick = { onEvent(DriveEvent.RequestDelete(state.selectedItems.map { it.ref })) },
                        tint = ZillitTheme.colors.danger,
                    )
                }
            }
        }
    }
}

/** What Drive's upload sheet offers — the phones' set, minus the camera. */
private val DRIVE_UPLOAD_KINDS = listOf(
    com.zillit.desktop.core.media.PreviewKind.Image,
    com.zillit.desktop.core.media.PreviewKind.Video,
    com.zillit.desktop.core.media.PreviewKind.Document,
)
