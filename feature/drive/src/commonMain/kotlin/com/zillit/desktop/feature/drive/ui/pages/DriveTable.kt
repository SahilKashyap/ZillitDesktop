package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveSortColumn
import com.zillit.desktop.feature.drive.domain.formatBytes
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.LocalDriveCompact
import com.zillit.desktop.feature.drive.ui.LocalDriveNow

/**
 * The list view — `DriveTable.jsx`.
 *
 * Its own table rather than `ZillitDataTable`: rows here take a
 * right-click, a double-click, a drag, and reveal their actions on hover,
 * none of which the shared table offers. The visual language is the
 * same — sunken header, hover wash, selected wash.
 */
@Composable
internal fun DriveTable(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    drag: DragToFolderState,
    modifier: Modifier = Modifier,
) {
    val rows = state.rows
    val compact = LocalDriveCompact.current
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            item(key = "header") {
                TableHeader(state, onEvent, rows, compact)
                ZillitDivider()
            }
            if (state.loading && rows.isEmpty()) {
                items(SKELETON_ROWS) { SkeletonRow() }
            }
            items(rows, key = { it.id }) { item ->
                TableRow(item, state, onEvent, drag, compact)
                ZillitDivider()
            }
        }
        ZillitScrollRail(listState, modifier = Modifier.align(Alignment.CenterEnd))
        if (rows.isEmpty() && !state.loading) DriveEmptyState(state, onEvent)
    }
}

@Composable
private fun TableHeader(
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    rows: List<DriveItem>,
    compact: Boolean,
) {
    val colors = ZillitTheme.colors
    val allSelected = rows.isNotEmpty() && rows.all { it.id in state.selected }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHECK_WIDTH), contentAlignment = Alignment.Center) {
            ZillitCheckbox(
                checked = allSelected,
                onCheckedChange = { onEvent(DriveEvent.SelectAll(!allSelected)) },
                enabled = rows.isNotEmpty(),
            )
        }
        SortableHeader("Name", DriveSortColumn.Name, state, onEvent, Modifier.weight(NAME_WEIGHT))
        if (!compact) {
            SortableHeader("Date modified", DriveSortColumn.Modified, state, onEvent, Modifier.width(DATE_WIDTH))
            HeaderLabel("Description", Modifier.weight(DESCRIPTION_WEIGHT))
            HeaderLabel("Sharing", Modifier.width(SHARING_WIDTH))
        }
        SortableHeader("Size", DriveSortColumn.Size, state, onEvent, Modifier.width(SIZE_WIDTH))
        Box(Modifier.width(if (compact) ACTIONS_WIDTH_COMPACT else ACTIONS_WIDTH))
    }
}

@Composable
private fun HeaderLabel(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A header that sorts on click and shows which way — antd's column sorters. */
@Composable
private fun SortableHeader(
    text: String,
    column: DriveSortColumn,
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    modifier: Modifier,
) {
    val active = state.sort.column == column
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier.combinedClickable(onClick = { onEvent(DriveEvent.SortBy(column)) }),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.columnHeader,
            color = if (active) colors.accentText else colors.textSecondary,
            maxLines = 1,
        )
        if (active) {
            ZillitIcon(
                icon = if (state.sort.ascending) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                tint = colors.accent,
                size = ZillitTheme.spacing.md,
            )
        }
    }
}

@Composable
private fun SkeletonRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHECK_WIDTH))
        ZillitSkeletonBar(Modifier.weight(NAME_WEIGHT))
        ZillitSkeletonBar(Modifier.width(DATE_WIDTH))
        ZillitSkeletonBar(Modifier.weight(DESCRIPTION_WEIGHT))
        ZillitSkeletonBar(Modifier.width(SHARING_WIDTH))
        ZillitSkeletonBar(Modifier.width(SIZE_WIDTH))
    }
}

/**
 * One row. Click selects, double-click opens, right-click and the ⋮ open
 * the menu, and the row can be dragged onto a folder. Actions stay
 * composed and fade in on hover — a control composed only on hover never
 * receives the press that revealed it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongMethod") // One row's columns; splitting them separates each from its width.
private fun TableRow(
    item: DriveItem,
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    drag: DragToFolderState,
    compact: Boolean,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = item.id in state.selected
    val inspected = state.details.item?.id == item.id
    val dropHover = drag.hoverFolderId == item.id
    val beingDragged = drag.dragging?.id == item.id
    val canDrag = state.viewer.may(DriveAction.Edit, item) && !state.isSearching
    var origin by remember { mutableStateOf(Offset.Zero) }

    val background = when {
        dropHover -> colors.accentSoft
        selected || inspected -> colors.surfaceSelected
        hovered -> colors.surfaceHover
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .alpha(if (beingDragged) DRAGGED_ALPHA else 1f)
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
            )
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHECK_WIDTH), contentAlignment = Alignment.Center) {
            ZillitCheckbox(
                checked = selected,
                onCheckedChange = { onEvent(DriveEvent.SetSelected(item.id, it)) },
            )
        }
        NameCell(item, state, onEvent, Modifier.weight(NAME_WEIGHT), origin)
        if (!compact) {
            ZillitTooltip(text = exactStamp(item.modifiedAt)) {
                ZillitText(
                    text = modifiedLabel(item.modifiedAt, LocalDriveNow.current()),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.width(DATE_WIDTH),
                )
            }
            ZillitText(
                text = item.description.ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = if (item.description.isBlank()) colors.textMuted else colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(DESCRIPTION_WEIGHT),
            )
            Box(Modifier.width(SHARING_WIDTH)) { SharingCell(item, state) }
        }
        SizeCell(item, Modifier.width(SIZE_WIDTH))
        RowActions(item, state, onEvent, hovered || selected, compact)
    }
}

@Composable
private fun NameCell(
    item: DriveItem,
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    modifier: Modifier,
    origin: Offset,
) {
    val colors = ZillitTheme.colors
    val starred = state.isFavourite(item)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemGlyph(item, state, onEvent)
        ZillitTooltip(text = item.name) {
            ZillitText(
                text = item.name,
                style = ZillitTheme.typography.bodyMedium,
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
        if (state.viewer.isSharedWithMe(item)) SharedWithYouMark()
        ZillitIconButton(
            icon = if (starred) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
            contentDescription = if (starred) "Remove from favourites" else "Add to favourites",
            onClick = { onEvent(DriveEvent.ToggleFavourite(item.ref)) },
            tint = if (starred) colors.gold else colors.textMuted,
            size = STAR_SIZE,
        )
    }
}

@Composable
private fun SizeCell(item: DriveItem, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val text = when {
        item.isFolder && item.sizeBytes <= 0 -> "—"
        else -> formatBytes(item.sizeBytes)
    }
    val hint = if (item.isFolder) {
        val count = item.itemCount ?: 0
        "$count file${if (count == 1) "" else "s"}"
    } else {
        text
    }
    ZillitTooltip(text = hint) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.numeric,
            color = if (text == "—") colors.textMuted else colors.textSecondary,
            maxLines = 1,
            modifier = modifier,
        )
    }
}

/** Preview (files), delete (when allowed) and the ⋮ — `drive-row-actions`. */
@Composable
private fun RowScope.RowActions(
    item: DriveItem,
    state: DriveUiState,
    onEvent: (DriveEvent) -> Unit,
    revealed: Boolean,
    compact: Boolean,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .width(if (compact) ACTIONS_WIDTH_COMPACT else ACTIONS_WIDTH)
            .alpha(if (revealed) 1f else HIDDEN_ACTIONS_ALPHA),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!item.isFolder && !compact) {
            ZillitTooltip(text = "Open") {
                ZillitIconButton(
                    icon = ZillitIcons.Eye,
                    contentDescription = "Open ${item.name}",
                    onClick = { onEvent(DriveEvent.Preview(item)) },
                )
            }
        }
        if (state.viewer.may(DriveAction.Delete, item) && !compact) {
            ZillitTooltip(text = "Delete") {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete ${item.name}",
                    onClick = { onEvent(DriveEvent.RequestDelete(listOf(item.ref))) },
                    tint = colors.danger,
                )
            }
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

/**
 * Opening a row: folders navigate, files preview — except editable
 * documents, which surface the full action list at the pointer so the user
 * picks Preview or Edit rather than landing in the read-only editor
 * (ZL-21229).
 */
internal fun openItem(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit, at: Offset) {
    when {
        item.isFolder -> onEvent(DriveEvent.OpenFolder(item.id))
        item.isEditableDocument && !state.viewer.isViewOnly(item) ->
            onEvent(DriveEvent.OpenMenu(item, at.x + MENU_INSET, at.y + MENU_INSET))
        else -> onEvent(DriveEvent.Preview(item))
    }
}

/**
 * Fires with the press position, in the modified node's own coordinates, on a
 * secondary-button press.
 *
 * Reads raw events rather than `awaitFirstDown`: on desktop that helper answers
 * only the primary button (`firstDownRefersToPrimaryMouseButtonOnly`), so a
 * detector built on it never sees a right click at all.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.onRightClick(onClick: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val secondary = event.button == PointerButton.Secondary || event.buttons.isSecondaryPressed
            val press = event.type == PointerEventType.Press && secondary
            val down = event.changes.firstOrNull()?.takeIf { press && !it.isConsumed }
            if (down != null) {
                down.consume()
                onClick(down.position)
            }
        }
    }
}

private const val NAME_WEIGHT = 3f
private const val DESCRIPTION_WEIGHT = 1.6f
private const val SKELETON_ROWS = 6
private const val DRAGGED_ALPHA = 0.4f
private const val HIDDEN_ACTIONS_ALPHA = 0.18f
private const val MENU_DROP = 28f
private const val MENU_INSET = 24f
private val CHECK_WIDTH: Dp = 28.dp
private val DATE_WIDTH: Dp = 130.dp
private val SHARING_WIDTH: Dp = 150.dp
private val SIZE_WIDTH: Dp = 90.dp
private val ACTIONS_WIDTH: Dp = 104.dp
private val ACTIONS_WIDTH_COMPACT: Dp = 36.dp
private val STAR_SIZE: Dp = 22.dp
