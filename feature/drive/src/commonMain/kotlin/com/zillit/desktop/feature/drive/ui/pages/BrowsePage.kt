package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveGrouping
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveQuickFilter
import com.zillit.desktop.feature.drive.domain.DriveSort
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveViewMode
import com.zillit.desktop.feature.drive.ui.LocalDriveCompact
import com.zillit.desktop.feature.drive.ui.refs

/**
 * The folder browser.
 *
 * ## The toolbar is the bulk-action bar
 *
 * A selection turns the toolbar into the actions for it, in place, rather than
 * floating a second bar over the rows it acts on — which is what the web does,
 * and which covers exactly the rows you are trying to check.
 *
 * The counts on those actions are what the *server* will accept, not what is
 * selected: bulk operations are permission-checked per item, so "Delete 17 of
 * 20" is the honest label for a mixed selection.
 */
@Composable
fun BrowsePage(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    var newFolderOpen by remember { mutableStateOf(false) }

    val compact = LocalDriveCompact.current
    FixedPage(padding = if (compact) ZillitTheme.spacing.sm else ZillitTheme.spacing.xl) {
        if (compact) {
            CompactToolbar(state, onEvent, onNewFolder = { newFolderOpen = true })
        } else {
            DriveToolbar(state, onEvent, onNewFolder = { newFolderOpen = true })
        }
        Breadcrumb(state, onEvent)
        if (!compact) QuickFilters(state, onEvent)

        when (state.viewMode) {
            DriveViewMode.List -> ListView(state, onEvent)
            DriveViewMode.Grid -> GridView(state, onEvent)
        }
    }

    NewFolderDialog(
        visible = newFolderOpen,
        location = state.locationLabel,
        onDismiss = { newFolderOpen = false },
        onCreate = { name ->
            newFolderOpen = false
            onEvent(DriveEvent.CreateFolder(name))
        },
    )
}

/**
 * The widget's toolbar: search takes the width; sort and grouping are not
 * offered (the window is too narrow for two selects and they are one click
 * away in the main app); view toggle and New folder are icons.
 */
@Composable
private fun CompactToolbar(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    onNewFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(DriveEvent.Search(it)) },
            placeholder = "Search",
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = if (state.viewMode == DriveViewMode.List) ZillitIcons.Grid else ZillitIcons.File,
            contentDescription = "Switch to ${state.viewMode.toggled().label} view",
            onClick = { onEvent(DriveEvent.ToggleViewMode) },
        )
        if (state.viewer.canCreate) {
            ZillitIconButton(icon = ZillitIcons.Add, contentDescription = "New folder", onClick = onNewFolder)
        }
    }
    if (state.selected.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) { BulkActions(state, onEvent) }
    }
}

@Composable
private fun DriveToolbar(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    onNewFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(DriveEvent.Search(it)) },
            placeholder = "Search files and folders",
            modifier = Modifier.width(SEARCH_WIDTH.dp),
        )
        ZillitSelect(
            value = state.sort,
            options = DriveSort.entries,
            onSelect = { onEvent(DriveEvent.SortBy(it)) },
            label = { it.label },
            modifier = Modifier.width(CONTROL_WIDTH.dp),
        )
        ZillitSelect(
            value = state.grouping,
            options = DriveGrouping.entries,
            onSelect = { onEvent(DriveEvent.GroupBy(it)) },
            label = { grouping ->
                if (grouping == DriveGrouping.None) "No grouping" else "Group: ${grouping.label}"
            },
            modifier = Modifier.width(CONTROL_WIDTH.dp),
        )

        if (state.selected.isNotEmpty()) {
            BulkActions(state, onEvent)
        }

        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            ZillitIconButton(
                icon = if (state.viewMode == DriveViewMode.List) ZillitIcons.Grid else ZillitIcons.File,
                contentDescription = "Switch to ${state.viewMode.toggled().label} view",
                onClick = { onEvent(DriveEvent.ToggleViewMode) },
            )
            if (state.viewer.canCreate) {
                ZillitButton(
                    text = "New folder",
                    onClick = onNewFolder,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }
    }
}

@Composable
private fun BulkActions(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val selected = state.selected.size
    ZillitText(
        text = "$selected selected",
        style = ZillitTheme.typography.label,
        color = ZillitTheme.colors.textSecondary,
    )
    ZillitButton(
        text = "Clear",
        onClick = { onEvent(DriveEvent.ClearSelection) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
    if (state.downloadableCount > 0) {
        ZillitButton(
            text = bulkLabel("Download", state.downloadableCount, selected),
            onClick = { onEvent(DriveEvent.DownloadSelection) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
        )
    }
    if (state.deletableCount > 0) {
        ZillitButton(
            text = bulkLabel("Delete", state.deletableCount, selected),
            onClick = { onEvent(DriveEvent.RequestDelete(state.selectedItems.refs())) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
        )
    }
}

/** "Delete 17 of 20" when some rows are not the viewer's to touch. */
private fun bulkLabel(verb: String, eligible: Int, selected: Int): String =
    if (eligible == selected) "$verb $selected" else "$verb $eligible of $selected"

@Composable
private fun Breadcrumb(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = "Drive",
            onClick = { onEvent(DriveEvent.OpenFolder(null)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Home,
        )
        state.breadcrumb.forEach { crumb ->
            ZillitText(text = "/", color = ZillitTheme.colors.textMuted)
            ZillitButton(
                text = crumb.name,
                onClick = { onEvent(DriveEvent.OpenFolder(crumb.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFilters(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        DriveQuickFilter.entries.forEach { filter ->
            ZillitButton(
                text = filter.label,
                onClick = { onEvent(DriveEvent.Filter(filter)) },
                variant = if (state.quickFilter == filter) {
                    ButtonVariant.Secondary
                } else {
                    ButtonVariant.Tertiary
                },
                size = ButtonSize.Small,
            )
        }
        state.tags.forEach { tag ->
            ZillitButton(
                text = "#${tag.name}",
                onClick = {
                    onEvent(
                        DriveEvent.FilterByTag(if (state.tagFilterId == tag.id) null else tag.id),
                    )
                },
                variant = if (state.tagFilterId == tag.id) {
                    ButtonVariant.Secondary
                } else {
                    ButtonVariant.Tertiary
                },
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ColumnScope.ListView(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    ZillitSectionCard(
        title = listingTitle(state),
        icon = ZillitIcons.Drive,
        padded = false,
        modifier = Modifier.weight(1f),
        action = {
            if (state.hasMore) {
                ZillitButton(
                    text = if (state.loadingMore) "Loading…" else "Load more",
                    onClick = { onEvent(DriveEvent.LoadMore) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    loading = state.loadingMore,
                )
            }
        },
    ) {
        ZillitDataTable(
            rows = state.items.sortedByDescending { it.isFolder },
            key = { it.id },
            loading = state.loading,
            columns = driveColumns(state, onEvent, compact = LocalDriveCompact.current),
            onRowClick = { onEvent(DriveEvent.OpenItem(it)) },
            isSelected = { it.id in state.selected || it.id == state.details.item?.id },
            emptyTitle = emptyTitle(state),
            emptyMessage = emptyMessage(state),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.GridView(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    ZillitSectionCard(
        title = listingTitle(state),
        icon = ZillitIcons.Grid,
        modifier = Modifier.weight(1f),
    ) {
        if (state.items.isEmpty()) {
            com.zillit.desktop.core.designsystem.component.ZillitEmptyState(
                title = emptyTitle(state),
                message = emptyMessage(state),
                icon = ZillitIcons.Drive,
            )
            return@ZillitSectionCard
        }
        // A FlowRow rather than a LazyVerticalGrid: the page is already bounded
        // and a lazy grid inside it would be measured against an infinite
        // constraint, which Compose refuses outright. Card counts here are one
        // page, not the whole drive.
        Column(modifier = Modifier.fillMaxSize().zillitVerticalScroll()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                state.items.sortedByDescending { it.isFolder }.forEach { item ->
                    GridCard(item, state, onEvent)
                }
            }
        }
    }
}

@Composable
private fun GridCard(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    ZillitSectionCard(
        modifier = Modifier.width(CARD_WIDTH.dp),
        title = item.name,
        icon = if (item.isFolder) ZillitIcons.Grid else ZillitIcons.File,
        meta = if (item.isFolder) {
            item.itemCount?.let { "$it items" } ?: "Folder"
        } else {
            formatBytes(item.sizeBytes)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!item.isFolder) ZillitFileBadge(fileName = item.name)
            ZillitText(
                text = item.uploadedByName.ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            StarButton(item, state, onEvent)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitButton(
                text = "Open",
                onClick = { onEvent(DriveEvent.OpenItem(item)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Details",
                onClick = { onEvent(DriveEvent.ShowDetails(item)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun StarButton(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val starred = state.isFavourite(item)
    ZillitIconButton(
        icon = if (starred) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
        contentDescription = if (starred) "Unstar ${item.name}" else "Star ${item.name}",
        onClick = { onEvent(DriveEvent.ToggleFavourite(state.refOf(item))) },
        tint = if (starred) ZillitTheme.colors.gold else null,
    )
}

private fun listingTitle(state: DriveUiState): String {
    val loaded = state.items.size
    return if (state.total > loaded) "Files · $loaded of ${state.total}" else "Files · $loaded"
}

private fun emptyTitle(state: DriveUiState): String = when {
    state.search.isNotBlank() -> "No results for \"${state.search}\""
    state.quickFilter != DriveQuickFilter.All -> "Nothing matches ${state.quickFilter.label}"
    else -> "This folder is empty"
}

private fun emptyMessage(state: DriveUiState): String? = when {
    state.search.isNotBlank() || state.quickFilter != DriveQuickFilter.All -> null
    state.viewer.canCreate -> "Upload files, or create a folder to organise them."
    else -> null
}

@Suppress("LongMethod") // A table of columns; splitting it separates each from its width.
private fun driveColumns(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    /** The widget's width has room for name, size and the actions — not who or when. */
    compact: Boolean = false,
): List<TableColumn<DriveItem>> = buildList {
    add(
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(CHECK_COLUMN.dp),
            cell = { item ->
                ZillitCheckbox(
                    checked = item.id in state.selected,
                    onCheckedChange = { onEvent(DriveEvent.ToggleSelection(item.id)) },
                )
            },
        ),
    )
    add(
        TableColumn(
            header = "Name",
            width = ColumnWidth.Weight(NAME_WEIGHT),
            cell = { item ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.isFolder) {
                        ZillitIcon(icon = ZillitIcons.Grid, tint = ZillitTheme.colors.accent)
                    } else {
                        ZillitFileBadge(fileName = item.name)
                    }
                    ZillitText(text = item.name, maxLines = 1)
                    if (item.isShared) {
                        ZillitStatusPill(label = "Shared", tone = StatusTone.Progress)
                    }
                }
            },
        ),
    )
    add(
        textColumn("Size", ColumnWidth.Fixed(SIZE_COLUMN.dp), numeric = true) { item ->
            // A folder's size is the sum of what is inside it, which the server
            // only sometimes computes. An item count is the honest alternative
            // to a zero that reads as "this folder is empty".
            if (item.isFolder) item.itemCount?.let { "$it items" } ?: "—" else formatBytes(item.sizeBytes)
        },
    )
    if (!compact) {
        add(textColumn("Uploaded by", ColumnWidth.Weight(1f), muted = true) {
            it.uploadedByName.ifBlank { "—" }
        })
        add(textColumn("Modified", ColumnWidth.Fixed(DATE_COLUMN.dp), muted = true) {
            EpochDate.date(it.updatedAt ?: it.createdAt).ifBlank { "—" }
        })
    }
    add(
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(ACTIONS_COLUMN.dp),
            cell = { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    StarButton(item, state, onEvent)
                    ZillitIconButton(
                        icon = ZillitIcons.Info,
                        contentDescription = "Details for ${item.name}",
                        onClick = { onEvent(DriveEvent.ShowDetails(item)) },
                    )
                    if (state.viewer.may(DriveAction.Download, item) && !item.isFolder) {
                        ZillitIconButton(
                            icon = ZillitIcons.Download,
                            contentDescription = "Download ${item.name}",
                            onClick = { onEvent(DriveEvent.Download(item)) },
                        )
                    }
                    if (state.viewer.may(DriveAction.Delete, item)) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Delete ${item.name}",
                            onClick = {
                                onEvent(DriveEvent.RequestDelete(listOf(state.refOf(item))))
                            },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            },
        ),
    )
}

@Composable
private fun NewFolderDialog(
    visible: Boolean,
    location: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember(visible) { mutableStateOf("") }

    ZillitDialogShell(
        title = "New folder",
        subtitle = "In $location",
        visible = visible,
        onDismiss = onDismiss,
        icon = ZillitIcons.Add,
    ) {
        ZillitTextField(
            value = name,
            onValueChange = { name = it },
            label = "Folder name",
            placeholder = "Camera reports",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "Create",
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            )
        }
    }
}

/** Progress for uploads still running, above the listing. */
@Composable
fun UploadStrip(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        state.activeUploads.forEach { upload ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = upload.fileName,
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.width(UPLOAD_NAME_WIDTH.dp),
                )
                com.zillit.desktop.core.designsystem.component.ZillitProgressBar(
                    fraction = upload.fraction,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = "${(upload.fraction * PERCENT).toInt()}%",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = "Cancel upload of ${upload.fileName}",
                    onClick = { onEvent(DriveEvent.CancelUpload(upload.id)) },
                )
            }
        }
    }
}

/** The name column takes three shares of what the fixed columns leave. */
private const val NAME_WEIGHT = 3f
private const val SEARCH_WIDTH = 260
private const val CONTROL_WIDTH = 170
private const val SIZE_COLUMN = 110
private const val DATE_COLUMN = 130
private const val ACTIONS_COLUMN = 150
private const val CHECK_COLUMN = 44
private const val CARD_WIDTH = 220
private const val UPLOAD_NAME_WIDTH = 220
private const val PERCENT = 100
