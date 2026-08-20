package com.zillit.desktop.core.workspace.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.component.zillitHorizontalScroll
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.ToolWindow
import com.zillit.desktop.core.workspace.WindowState
import com.zillit.desktop.core.workspace.WorkspaceEvent
import com.zillit.desktop.core.workspace.WorkspaceState

/**
 * The tab strip: one chip per open window, plus the Close-all control.
 *
 * Ported from the web `ToolTabStrip.jsx`, including the "N open" counter and the
 * per-tab badge — so the two clients read as the same product. The layout-mode
 * toggle is keyboard-only (see `WorkspaceShortcuts`) rather than a strip button.
 */
@Composable
fun WorkspaceTabStrip(
    state: WorkspaceState,
    onEvent: (WorkspaceEvent) -> Unit,
    modifier: Modifier = Modifier,
    iconFor: (ToolWindow) -> ImageVector = { ZillitIcons.Tools },
    badgeFor: (ToolWindow) -> Int = { 0 },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ZillitDimens.tabStripHeight)
            .background(ZillitTheme.colors.tabBar)
            .padding(horizontal = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TabList(
            state = state,
            onEvent = onEvent,
            iconFor = iconFor,
            badgeFor = badgeFor,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        LayoutControls(state = state, onEvent = onEvent)
    }
}

@Composable
private fun TabList(
    state: WorkspaceState,
    onEvent: (WorkspaceEvent) -> Unit,
    iconFor: (ToolWindow) -> ImageVector,
    badgeFor: (ToolWindow) -> Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "${state.windows.size} open",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(end = ZillitTheme.spacing.xs),
        )

        ReorderableTabs(
            state = state,
            onEvent = onEvent,
            iconFor = iconFor,
            badgeFor = badgeFor,
            modifier = Modifier.weight(1f, fill = false).zillitHorizontalScroll(),
        )
    }
}

/**
 * The tabs themselves, with drag-to-reorder.
 *
 * Drag distance accumulates locally and converts to index steps, so the reducer
 * sees one reorder per tab actually crossed rather than one per pointer frame.
 */
@Composable
private fun ReorderableTabs(
    state: WorkspaceState,
    onEvent: (WorkspaceEvent) -> Unit,
    iconFor: (ToolWindow) -> ImageVector,
    badgeFor: (ToolWindow) -> Int,
    modifier: Modifier = Modifier,
) {
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragAccumulator by remember { mutableStateOf(0f) }
    val tabStep = with(LocalDensity.current) { ZillitDimens.tabMinWidth.toPx() }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        state.orderedWindows.forEachIndexed { index, window ->
            WorkspaceTab(
                window = window,
                isActive = window.id == state.activeId,
                icon = iconFor(window),
                badge = badgeFor(window),
                isDragging = draggingIndex == index,
                onSelect = { onEvent(WorkspaceEvent.Restore(window.id)) },
                onClose = { onEvent(WorkspaceEvent.Close(window.id)) },
                onDragStart = {
                    draggingIndex = index
                    dragAccumulator = 0f
                },
                onDrag = { delta ->
                    dragAccumulator += delta
                    val steps = (dragAccumulator / tabStep).toInt()
                    if (steps == 0) return@WorkspaceTab
                    val from = draggingIndex
                    val to = (from + steps).coerceIn(0, state.orderedWindows.lastIndex)
                    if (to != from) {
                        onEvent(WorkspaceEvent.Reorder(from, to))
                        draggingIndex = to
                        dragAccumulator -= steps * tabStep
                    }
                },
                onDragEnd = {
                    draggingIndex = -1
                    dragAccumulator = 0f
                },
            )
        }
    }
}

@Composable
private fun LayoutControls(state: WorkspaceState, onEvent: (WorkspaceEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close all tools",
            onClick = { onEvent(WorkspaceEvent.CloseAll) },
            enabled = state.windows.any { !it.isPinned },
        )
    }
}

/**
 * One tab.
 *
 * A pinned tab collapses to its icon, a minimized tab dims and shows a marker,
 * and the close affordance only appears on hover or when active — so a strip of
 * ten tabs stays readable instead of becoming a row of × buttons.
 */
@Composable
@Suppress("LongParameterList")
private fun WorkspaceTab(
    window: ToolWindow,
    isActive: Boolean,
    icon: ImageVector,
    badge: Int,
    isDragging: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val minimized = window.state == WindowState.Minimized

    val background by animateColorAsState(
        when {
            isDragging -> colors.surfaceSelected
            isActive -> colors.tabActive
            hovered -> colors.tabHover
            else -> colors.tabInactive
        },
        label = "tabBackground",
    )
    val contentColor = when {
        minimized -> colors.textMuted
        isActive -> colors.textPrimary
        else -> colors.textSecondary
    }

    Box {
        Row(
            modifier = Modifier
                .widthIn(
                    min = if (window.isPinned) ZillitDimens.controlHeight else ZillitDimens.tabMinWidth,
                    max = ZillitDimens.tabMaxWidth,
                )
                .height(ZillitDimens.tabStripHeight - TAB_INSET)
                .clip(ZillitTheme.shapes.tab)
                .background(background)
                .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
                .tabDragGestures(window, onDragStart, onDrag, onDragEnd)
                .padding(horizontal = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = if (minimized) ZillitIcons.Minimize else icon,
                tint = if (isActive) colors.accent else contentColor,
                size = ZillitDimens.iconSmall,
            )
            TabBody(
                window = window,
                badge = badge,
                contentColor = contentColor,
                showClose = hovered || isActive,
                onClose = onClose,
            )
        }

        // Active indicator on the bottom edge, so the tab reads as attached to
        // the workspace below it.
        if (isActive) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(INDICATOR_HEIGHT)
                    .background(colors.tabIndicator),
            )
        }
    }
}

/** Drag-to-reorder gestures, extracted so the tab composable stays readable. */
private fun Modifier.tabDragGestures(
    window: ToolWindow,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = pointerInput(window.id) {
    detectHorizontalDragGestures(
        onDragStart = { onDragStart() },
        onDragEnd = onDragEnd,
        onDragCancel = onDragEnd,
    ) { change, delta ->
        change.consume()
        onDrag(delta)
    }
}

@Composable
private fun RowScope.TabBody(
    window: ToolWindow,
    badge: Int,
    contentColor: androidx.compose.ui.graphics.Color,
    showClose: Boolean,
    onClose: () -> Unit,
) {
    if (window.isPinned) {
        ZillitIcon(ZillitIcons.Pin, tint = ZillitTheme.colors.textMuted, size = PIN_SIZE)
        return
    }

    ZillitText(
        text = window.title,
        modifier = Modifier.weight(1f),
        style = ZillitTheme.typography.label,
        color = contentColor,
        maxLines = 1,
    )

    ZillitBadge(count = badge)

    // Unsaved-changes marker — a dot rather than a word, so it does not eat the
    // tab's limited width.
    if (window.isDirty) {
        Box(
            Modifier
                .size(DIRTY_DOT)
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.accent),
        )
    }

    if (showClose) {
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close ${window.title}",
            onClick = onClose,
            size = CLOSE_SIZE,
        )
    }
}

private val TAB_INSET = 8.dp
private val INDICATOR_HEIGHT = 2.dp
private val PIN_SIZE = 10.dp
private val DIRTY_DOT = 7.dp
private val CLOSE_SIZE = 18.dp
