package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.LocalDriveCompact
import com.zillit.desktop.feature.drive.ui.LocalDriveNow

/** The grid view — `DriveGridView.jsx`: a card per item, thumbnails where the file has one. */
@Composable
internal fun DriveGrid(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    drag: DragToFolderState,
    modifier: Modifier = Modifier,
) {
    val rows = state.rows
    val compact = LocalDriveCompact.current
    val gridState = rememberLazyGridState()
    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZillitSpinner()
            }

            rows.isEmpty() -> DriveEmptyState(state, onEvent)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (compact) CARD_MIN_COMPACT else CARD_MIN),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(ZillitTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                items(rows, key = { it.id }) { item -> GridCard(item, state, onEvent, drag) }
            }
        }
        ZillitScrollRail(gridState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // One card; preview, name, meta and actions belong together.
private fun GridCard(
    item: DriveItem,
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    drag: DragToFolderState,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = item.id in state.selected
    val dropHover = drag.hoverFolderId == item.id
    val beingDragged = drag.dragging?.id == item.id
    val canDrag = state.viewer.may(DriveAction.Edit, item) && !state.isSearching
    var origin by remember { mutableStateOf(Offset.Zero) }
    val starred = state.isFavourite(item)

    Column(
        modifier = Modifier
            .alpha(if (beingDragged) DRAGGED_ALPHA else 1f)
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.surfaceSelected else colors.surface)
            .border(
                width = if (dropHover || selected) 2.dp else 1.dp,
                color = when {
                    dropHover -> colors.accent
                    selected -> colors.accent.copy(alpha = SELECTED_BORDER)
                    hovered -> colors.borderStrong
                    else -> colors.border
                },
                shape = ZillitTheme.shapes.large,
            )
            .hoverable(interaction)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .then(if (item.isFolder) Modifier.dropTarget(drag, item.id) else Modifier)
            .dragSource(drag, item, canDrag) { dragged, folderId ->
                onEvent(DriveEvent.MoveTo(listOf(dragged.ref), folderId))
            }
            .onRightClick { at -> onEvent(DriveEvent.OpenMenu(item, origin.x + at.x, origin.y + at.y)) }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onEvent(DriveEvent.ToggleSelection(item.id)) },
                onDoubleClick = { openItem(item, state, onEvent, origin) },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_RATIO)
                .background(colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            CardPreview(item, state, onEvent)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ZillitTheme.spacing.xs)
                    .alpha(if (hovered || selected) 1f else 0f),
            ) {
                ZillitCheckbox(checked = selected, onCheckedChange = { onEvent(DriveEvent.SetSelected(item.id, it)) })
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(ZillitTheme.spacing.xs)
                    .alpha(if (hovered) 1f else 0f)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.surfaceRaised.copy(alpha = ACTIONS_WASH)),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                if (!item.isFolder) {
                    ZillitIconButton(
                        icon = ZillitIcons.Eye,
                        contentDescription = "Open ${item.name}",
                        onClick = { onEvent(DriveEvent.Preview(item)) },
                    )
                }
                if (state.viewer.may(DriveAction.Delete, item)) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Delete ${item.name}",
                        onClick = { onEvent(DriveEvent.RequestDelete(listOf(item.ref))) },
                        tint = colors.danger,
                    )
                }
                var anchor by remember { mutableStateOf(Offset.Zero) }
                ZillitIconButton(
                    icon = ZillitIcons.MoreHorizontal,
                    contentDescription = "More actions for ${item.name}",
                    onClick = { onEvent(DriveEvent.OpenMenu(item, anchor.x, anchor.y + MENU_DROP)) },
                    modifier = Modifier.onGloballyPositioned { anchor = it.positionInRoot() },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTooltip(text = item.name) {
                    ZillitText(
                        text = item.name,
                        style = ZillitTheme.typography.label,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { openItem(item, state, onEvent, origin) },
                            ),
                    )
                }
                if (starred) ZillitIcon(
                    icon = ZillitIcons.StarFilled,
                    tint = colors.gold,
                    size = ZillitTheme.spacing.md,
                )
                if (state.viewer.isSharedWithMe(item)) SharedWithYouMark()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.viewer.isSharedWithMe(item)) {
                    ZillitTooltip(text = "Owned by ${item.uploadedByName.ifBlank { "Unknown" }}") {
                        ZillitAvatar(name = item.uploadedByName.ifBlank { "?" }, size = TINY_AVATAR)
                    }
                }
                ZillitText(
                    text = modifiedLabel(item.modifiedAt, LocalDriveNow.current()),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
                if (item.sizeBytes > 0) {
                    ZillitText(
                        text = "· ${formatBytes(item.sizeBytes)}",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The card's picture: a big folder glyph, the image itself, or the extension badge. */
@Composable
private fun CardPreview(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    if (item.isFolder) {
        FolderGlyph(item, size = FOLDER_GLYPH)
        return
    }
    val bytes = state.thumbnails[item.id]
    if (bytes == null && item.previewKind == PreviewKind.Image) {
        LaunchedEffect(item.id) { onEvent(DriveEvent.WantThumbnail(item)) }
    }
    val bitmap = remember(bytes) { bytes?.let(::decodeImageBitmap) }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        ExtBadge(item.extension, size = CARD_BADGE)
    }
}

private val CARD_MIN = 200.dp
private val CARD_MIN_COMPACT = 150.dp
private val CARD_BADGE = 56.dp
private val FOLDER_GLYPH = 56.dp
private val TINY_AVATAR = 22.dp
private const val PREVIEW_RATIO = 4f / 3f
private const val DRAGGED_ALPHA = 0.4f
private const val SELECTED_BORDER = 0.7f
private const val ACTIONS_WASH = 0.92f
private const val MENU_DROP = 28f
