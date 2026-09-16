@file:Suppress("LongMethod") // The drag state and the chips it moves are one unit.

package com.zillit.desktop.feature.sides.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Drag-to-reorder scene chips — the web's `DraggableSceneOrder`.
 *
 * Each chip carries its position, its number and a remove cross; dragging
 * one over another moves it into that slot, and the chips the order has
 * dropped wait in an "Add back" row below. The dragged chip follows the
 * pointer through a graphics-layer offset, so the layout underneath can
 * re-flow on every move without the chip jumping out from under the hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DraggableSceneOrder(
    order: List<String>,
    available: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val latestOrder by rememberUpdatedState(order)
    val latestChange by rememberUpdatedState(onChange)
    // Chip bounds in the track's own coordinates, keyed by scene number.
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    val remaining = available.filter { it !in order }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (order.isEmpty()) {
                ZillitText(
                    text = "Drag chips to reorder — or add scenes back below.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            order.forEachIndexed { index, scene ->
                val isDragging = dragging == scene
                val origin = bounds[scene]?.topLeft ?: Offset.Zero
                val shift = if (isDragging) pointer - origin - grab else Offset.Zero
                OrderChip(
                    position = index + 1,
                    scene = scene,
                    lifted = isDragging,
                    onRemove = { latestChange(latestOrder - scene) },
                    modifier = Modifier
                        .onGloballyPositioned { bounds[scene] = Rect(it.positionInParent(), it.size.toSize()) }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationX = shift.x; translationY = shift.y }
                        .pointerInput(scene) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragging = scene
                                    grab = start
                                    pointer = (bounds[scene]?.topLeft ?: Offset.Zero) + start
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    pointer += delta
                                    val over = bounds.entries
                                        .firstOrNull { (key, rect) -> key != scene && rect.contains(pointer) }
                                        ?.key
                                    val current = latestOrder
                                    if (over != null && over in current) {
                                        val next = current.toMutableList()
                                        next.remove(scene)
                                        next.add(current.indexOf(over).coerceAtMost(next.size), scene)
                                        if (next != current) latestChange(next)
                                    }
                                },
                                onDragEnd = { dragging = null },
                                onDragCancel = { dragging = null },
                            )
                        },
                )
            }
        }
        if (remaining.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitText(
                    text = "Add back",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(vertical = 5.dp),
                )
                remaining.forEach { scene ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                            .clickable { latestChange(latestOrder + scene) }
                            .hand()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        ZillitText("+ $scene", style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderChip(
    position: Int,
    scene: String,
    lifted: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (lifted) colors.accentSoft else colors.surface)
            .border(1.dp, if (lifted) colors.accent else colors.borderStrong, RoundedCornerShape(8.dp))
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText("⠿", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        Box(
            modifier = Modifier.size(18.dp).clip(CircleShape).background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = position.toString(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        ZillitText(scene, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .hand(),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Close, tint = colors.textMuted, size = 10.dp)
        }
    }
}
