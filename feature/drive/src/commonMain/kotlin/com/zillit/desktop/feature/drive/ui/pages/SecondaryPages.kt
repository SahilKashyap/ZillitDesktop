package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.StorageUsage
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DrivePrompt
import com.zillit.desktop.feature.drive.ui.DriveUiState

/** Starred files and folders, across every folder. */
@Composable
fun FavouritesPage(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    FixedPage {
        ZillitSectionCard(title = "Starred", icon = ZillitIcons.StarFilled, padded = false) {
            ZillitDataTable(
                rows = state.favourites,
                key = { it.id },
                loading = state.loading,
                onRowClick = { onEvent(DriveEvent.OpenItem(it)) },
                columns = listOf(
                    nameColumn(),
                    textColumn("Size", ColumnWidth.Fixed(SIZE_COLUMN.dp), numeric = true) {
                        if (it.isFolder) "—" else formatBytes(it.sizeBytes)
                    },
                    textColumn("Uploaded by", ColumnWidth.Weight(1f), muted = true) {
                        it.uploadedByName.ifBlank { "—" }
                    },
                    TableColumn(
                        header = "",
                        width = ColumnWidth.Fixed(ACTION_COLUMN.dp),
                        cell = { item ->
                            ZillitButton(
                                text = "Unstar",
                                onClick = {
                                    onEvent(DriveEvent.ToggleFavourite(state.refOf(item)))
                                },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                            )
                        },
                    ),
                ),
                emptyTitle = "Nothing starred",
                emptyMessage = "Star a file or folder to keep it here, whichever folder it is in.",
            )
        }
    }
}

/**
 * Soft-deleted items.
 *
 * A regular user sees their own and an admin sees everything — that filtering
 * is the server's (FR-07.2), which is what makes this page safe to show to
 * everyone rather than gating it on posting rights.
 */
@Composable
fun TrashPage(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    FixedPage {
        if (state.trashItems.isNotEmpty()) {
            ZillitNotice(
                text = "Items here can be restored to where they were. Permanently deleting " +
                    "removes the stored file and cannot be undone.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }

        ZillitSectionCard(
            title = "Trash · ${state.trashItems.size}",
            icon = ZillitIcons.Trash,
            padded = false,
            action = {
                if (state.trashItems.isNotEmpty()) {
                    ZillitButton(
                        text = "Empty trash",
                        onClick = { onEvent(DriveEvent.RequestEmptyTrash) },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Trash,
                    )
                }
            },
        ) {
            ZillitDataTable(
                rows = state.trashItems,
                key = { it.id },
                loading = state.loading,
                columns = trashColumns(state, onEvent),
                emptyTitle = "The trash is empty",
            )
        }
    }
}

private fun trashColumns(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
): List<TableColumn<DriveItem>> = listOf(
    nameColumn(),
    textColumn("Size", ColumnWidth.Fixed(SIZE_COLUMN.dp), numeric = true) {
        if (it.isFolder) "—" else formatBytes(it.sizeBytes)
    },
    // No "deleted by" column: the trash route sends neither a name nor an id
    // for whoever deleted the row, and the web's TrashView shows none for the
    // same reason. `docs/Drive_Requirements.md` FR-07.10 claims otherwise —
    // it is wrong here, as it is about the five route paths.
    textColumn("Deleted", ColumnWidth.Fixed(DATE_COLUMN.dp), muted = true) {
        EpochDate.dateTime(it.deletedAt).ifBlank { "—" }
    },
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(TRASH_ACTIONS.dp),
        cell = { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitButton(
                    text = "Restore",
                    onClick = { onEvent(DriveEvent.Restore(state.refOf(item))) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Delete",
                    onClick = { onEvent(DriveEvent.Purge(state.refOf(item))) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
            }
        },
    ),
)

/** The audit trail across the whole drive. */
@Composable
fun ActivityPage(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    FixedPage {
        ZillitSectionCard(
            title = "Activity",
            icon = ZillitIcons.Clock,
            padded = false,
            action = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(DriveEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
            },
        ) {
            ZillitDataTable(
                rows = state.activity,
                key = { it.id },
                loading = state.loading,
                columns = activityColumns(),
                emptyTitle = "No activity yet",
                emptyMessage = "Uploads, edits, moves and deletions across the drive appear here.",
            )
        }
    }
}

private fun activityColumns(): List<TableColumn<DriveActivity>> = listOf(
    textColumn("When", ColumnWidth.Fixed(WHEN_COLUMN.dp), muted = true) {
        EpochDate.dateTime(it.at).ifBlank { "—" }
    },
    textColumn("Who", ColumnWidth.Weight(1f)) { it.displayName },
    textColumn("Did", ColumnWidth.Weight(1f)) { it.label },
    textColumn("To", ColumnWidth.Weight(2f), muted = true) { it.itemName.ifBlank { "—" } },
)

/** What the production is using, and against what allowance. */
@Composable
fun StoragePage(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val usage = state.storage

    ScrollingPage {
        StorageTiles(usage)
        AllowanceMeter(usage)
        TypeBreakdown(usage)

        ZillitButton(
            text = "Refresh",
            onClick = { onEvent(DriveEvent.Refresh) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Reload,
        )
    }
}

@Composable
private fun StorageTiles(usage: StorageUsage?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitStatTile(
            label = "Used",
            value = formatBytes(usage?.usedBytes ?: 0),
            icon = ZillitIcons.Drive,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Files",
            value = (usage?.fileCount ?: 0).toString(),
            icon = ZillitIcons.File,
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "In trash",
            value = formatBytes(usage?.trashBytes ?: 0),
            icon = ZillitIcons.Trash,
            // Trash still costs storage. Naming that is what stops "we're out
            // of space" ending in a support ticket rather than an Empty Trash
            // click.
            sub = "Still counts towards usage",
            modifier = Modifier.weight(1f),
        )
    }
}

/** Only drawn where the production has an allowance — see [StorageUsage.fraction]. */
@Composable
private fun AllowanceMeter(usage: StorageUsage?) {
    val fraction = usage?.fraction ?: return
    ZillitSectionCard(title = "Allowance", icon = ZillitIcons.BarChart) {
        ZillitMeter(
            fraction = fraction,
            tone = when {
                fraction >= NEARLY_FULL -> StatusTone.Rejected
                fraction >= GETTING_FULL -> StatusTone.Pending
                else -> StatusTone.Progress
            },
        )
        ZillitText(
            text = "${formatBytes(usage.usedBytes)} of ${formatBytes(usage.quotaBytes ?: 0)} used",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun TypeBreakdown(usage: StorageUsage?) {
    val byType = usage?.byType.orEmpty()
    if (byType.isEmpty()) return
    ZillitSectionCard(title = "By type", icon = ZillitIcons.Filter) {
        byType.entries.sortedByDescending { it.value }.forEach { (kind, bytes) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(text = kind.replaceFirstChar { it.uppercase() })
                ZillitText(
                    text = formatBytes(bytes),
                    style = ZillitTheme.typography.numeric,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * The confirmation for anything that cannot be undone.
 *
 * One dialog driven by [DrivePrompt] rather than one per action: the copy
 * differs, the shape never does, and a dialog per action is how two of them end
 * up with different button orders and someone empties the trash by muscle
 * memory.
 */
@Composable
fun DrivePromptDialog(prompt: DrivePrompt?, onEvent: (DriveEvent) -> Unit) {
    ZillitDialogShell(
        title = prompt?.title.orEmpty(),
        visible = prompt != null,
        onDismiss = { onEvent(DriveEvent.DismissPrompt) },
        icon = ZillitIcons.Warning,
    ) {
        ZillitText(text = prompt?.message.orEmpty())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DriveEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = prompt?.confirmLabel.orEmpty(),
                onClick = { onEvent(DriveEvent.ConfirmPrompt) },
                variant = ButtonVariant.Danger,
            )
        }
    }
}

/** The name cell, shared by every secondary listing. */
private fun nameColumn(): TableColumn<DriveItem> = TableColumn(
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
        }
    },
)

/** The name column takes three shares of what the fixed columns leave. */
private const val NAME_WEIGHT = 3f
private const val SIZE_COLUMN = 110
private const val DATE_COLUMN = 170
private const val WHEN_COLUMN = 190
private const val ACTION_COLUMN = 110
private const val TRASH_ACTIONS = 190
private const val NEARLY_FULL = 0.9f
private const val GETTING_FULL = 0.75f
