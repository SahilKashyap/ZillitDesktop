package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveViewMode
import com.zillit.desktop.feature.drive.ui.LocalDriveNow

/**
 * The trash, in place of the listing — `TrashView.jsx`: a header with the
 * count, Empty trash and Back to Drive; then the deleted items with Restore
 * and Delete forever, as a list or as cards to match the view mode.
 *
 * A regular user sees their own and an admin sees everything — that
 * filtering is the server's (FR-07.2), which is what makes this safe to show
 * to everyone rather than gating it on posting rights.
 */
@Composable
@Suppress("LongMethod") // The header and the body switch, together.
internal fun TrashView(state: DriveUiState, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    val trash = state.trash
    val colors = ZillitTheme.colors
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.dangerSoft)
                    .padding(ZillitTheme.spacing.sm),
            ) {
                ZillitIcon(icon = ZillitIcons.Trash, tint = colors.danger, size = ZillitTheme.spacing.lg)
            }
            ZillitText(text = "Trash (${trash.items.size})", style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = "Items here can be restored to where they were. Permanently deleting cannot be undone.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.sm),
            )
            if (trash.items.isNotEmpty()) {
                ZillitButton(
                    text = "Empty trash",
                    onClick = { onEvent(DriveEvent.RequestEmptyTrash) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
            ZillitButton(
                text = "Back to Drive",
                onClick = { onEvent(DriveEvent.ShowTrash(false)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
        }
        ZillitDivider()
        when {
            trash.loading && trash.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZillitSpinner()
            }

            trash.items.isEmpty() -> ZillitEmptyState(
                title = "Trash is empty",
                message = "Deleted files and folders wait here until you restore or permanently delete them.",
                icon = ZillitIcons.Trash,
            )

            state.viewMode == DriveViewMode.Grid -> TrashGrid(trash.items, onEvent)
            else -> TrashList(trash.items, onEvent)
        }
    }
}

@Composable
private fun TrashList(items: List<DriveItem>, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceSunken)
                        .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    HeaderText("Name", Modifier.weight(1f))
                    HeaderText("Size", Modifier.width(SIZE_WIDTH))
                    HeaderText("Deleted", Modifier.width(DELETED_WIDTH))
                    Box(Modifier.width(ACTIONS_WIDTH))
                }
                ZillitDivider()
            }
            items(items, key = { "${it.kind.wire}-${it.id}" }) { item ->
                TrashRow(item, onEvent)
                ZillitDivider()
            }
        }
        ZillitScrollRail(listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun HeaderText(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textSecondary,
        modifier = modifier,
    )
}

@Composable
@Suppress("LongMethod") // One row's cells; splitting them separates each from its width.
private fun TrashRow(item: DriveItem, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interaction)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isFolder) FolderGlyph(item) else ExtBadge(item.extension, size = ROW_BADGE)
            ZillitText(
                text = item.name,
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            ZillitStatusPill(
                label = item.kind.wire,
                tone = if (item.isFolder) StatusTone.Progress else StatusTone.Neutral,
            )
        }
        ZillitText(
            text = if (item.isFolder) "—" else formatBytes(item.sizeBytes),
            style = ZillitTheme.typography.numeric,
            color = colors.textSecondary,
            modifier = Modifier.width(SIZE_WIDTH),
        )
        ZillitTooltip(text = exactStamp(item.deletedAt)) {
            Row(
                modifier = Modifier.width(DELETED_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(icon = ZillitIcons.Clock, tint = colors.textMuted, size = ZillitTheme.spacing.md)
                ZillitText(
                    text = modifiedLabel(item.deletedAt, LocalDriveNow.current()),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier.width(ACTIONS_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        ) {
            ZillitTooltip(text = "Restore to original location") {
                ZillitButton(
                    text = "Restore",
                    onClick = { onEvent(DriveEvent.Restore(item.ref)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                )
            }
            ZillitTooltip(text = "Permanently delete — cannot be undone") {
                ZillitButton(
                    text = "Delete",
                    onClick = { onEvent(DriveEvent.RequestPurge(item)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod") // One card; preview, name, meta and actions belong together.
private fun TrashGrid(items: List<DriveItem>, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(items, key = { "${it.kind.wire}-${it.id}" }) { item ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Column(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.surface)
                    .border(1.dp, if (hovered) colors.borderStrong else colors.border, ZillitTheme.shapes.large)
                    .hoverable(interaction),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(PREVIEW_RATIO).background(colors.surfaceSunken),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.isFolder) FolderGlyph(item, size = CARD_GLYPH) else ExtBadge(
                        item.extension,
                        size = CARD_GLYPH,
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(ZillitTheme.spacing.xs)
                            .alpha(if (hovered) 1f else 0f)
                            .clip(ZillitTheme.shapes.medium)
                            .background(colors.surfaceRaised),
                    ) {
                        ZillitIconButton(
                            icon = ZillitIcons.Reload,
                            contentDescription = "Restore ${item.name}",
                            onClick = { onEvent(DriveEvent.Restore(item.ref)) },
                            tint = colors.success,
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Delete ${item.name} forever",
                            onClick = { onEvent(DriveEvent.RequestPurge(item)) },
                            tint = colors.danger,
                        )
                    }
                }
                Column(
                    modifier = Modifier.padding(ZillitTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(text = item.name, style = ZillitTheme.typography.label, maxLines = 1)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitIcon(icon = ZillitIcons.Clock, tint = colors.textMuted, size = ZillitTheme.spacing.md)
                        ZillitText(
                            text = modifiedLabel(item.deletedAt, LocalDriveNow.current()),
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                        if (item.sizeBytes > 0) {
                            ZillitText(
                                text = "· ${formatBytes(item.sizeBytes)}",
                                style = ZillitTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val SIZE_WIDTH = 90.dp
private val DELETED_WIDTH = 150.dp
private val ACTIONS_WIDTH = 200.dp
private val ROW_BADGE = 30.dp
private val CARD_MIN = 200.dp
private val CARD_GLYPH = 56.dp
private const val PREVIEW_RATIO = 4f / 3f
