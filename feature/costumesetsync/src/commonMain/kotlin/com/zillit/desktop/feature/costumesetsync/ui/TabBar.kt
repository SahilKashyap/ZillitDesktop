package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.hoverable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/** One page inside a tab group. [danger] turns its figure red, as the web's menu does for Damage, Missing and Rentals due. */
data class SyncTabItem(val to: String, val label: String, val count: Int = 0, val danger: Boolean = false)

/** A top-level tab; one with [items] opens a menu of pages (the web's `TabGroup`). */
data class SyncTabModel(val id: String, val label: String, val icon: ImageVector, val start: String, val items: List<SyncTabItem> = emptyList())

/**
 * The tool's tab strip as the web draws it: the strip sits on the page colour, each tab an icon and a 15px
 * label with a caret and a dropdown of pages (with live figures) on the groups; the lit tab is raised onto
 * the surface with a 3px accent underline. When the tabs overflow, the edge with more past it fades out and
 * carries a small round arrow that pages the row by about 70% (the web's `TabStrip`). [trailing] is one more
 * tab at the end of the row (Setup).
 */
@Composable
fun SyncTabBar(
    tabs: List<SyncTabModel>,
    activeId: String?,
    activeItem: String,
    onGo: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: SyncTabModel? = null,
    onTrailing: () -> Unit = {},
) {
    val colors = ZillitTheme.colors
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val canLeft = scroll.value > 1
    val canRight = scroll.value < scroll.maxValue - 1
    var viewport by remember { mutableStateOf(0) }
    // Each tab's span inside the scrolled row, so the lit tab is brought clear of the 48px fades (never the hidden one).
    val spans = remember { mutableMapOf<String, IntRange>() }
    var measured by remember { mutableStateOf(0) }
    val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { FADE.roundToPx() }
    LaunchedEffect(activeId, viewport, measured, scroll.maxValue) {
        val span = spans[activeId] ?: return@LaunchedEffect
        if (viewport == 0) return@LaunchedEffect
        when {
            span.first < scroll.value + fadePx -> scroll.scrollTo((span.first - fadePx).coerceAtLeast(0))
            span.last > scroll.value + viewport - fadePx -> scroll.scrollTo((span.last - viewport + fadePx).coerceAtMost(scroll.maxValue))
        }
    }
    Box(modifier.fillMaxWidth().background(colors.canvas).onSizeChanged { viewport = it.width }) {
        Row(
            Modifier
                .fillMaxWidth()
                .fadeEdges(canLeft, canRight)
                .horizontalScroll(scroll)
                .padding(horizontal = STRIP_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEach { tab -> TabCell(tab, active = tab.id == activeId, activeItem = activeItem, onGo = onGo, modifier = Modifier.onGloballyPositioned { spans[tab.id] = it.positionInParent().x.toInt()..(it.positionInParent().x + it.size.width).toInt(); measured = spans.size }) }
            trailing?.let { TabCell(it, active = false, activeItem = activeItem, onGo = { onTrailing() }) }
        }
        if (canLeft) {
            ArrowButton(ZillitIcons.ChevronLeft, Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                scope.launch { scroll.animateScrollTo((scroll.value - viewport * PAGE).toInt().coerceAtLeast(0)) }
            }
        }
        if (canRight) {
            ArrowButton(ZillitIcons.ChevronRight, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                scope.launch { scroll.animateScrollTo((scroll.value + viewport * PAGE).toInt().coerceAtMost(scroll.maxValue)) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** The web's 48px mask on an edge with more tabs past it. */
private fun Modifier.fadeEdges(left: Boolean, right: Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = FADE.toPx().coerceAtMost(size.width / 2)
        if (left) {
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade), size = size, blendMode = BlendMode.DstIn)
        }
        if (right) {
            drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width), size = size, blendMode = BlendMode.DstIn)
        }
    }

@Composable
private fun ArrowButton(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(ARROW)
            .clip(CircleShape)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(1.dp, colors.border, CircleShape)
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(icon, tint = colors.textPrimary, size = 14.dp) }
}

/** A figure beside a tab: amber with dark ink, red for trouble (the web's `.csync-navcount`). */
@Composable
private fun CountPill(n: Int, danger: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 18.dp)
            .background(if (danger) colors.danger else colors.accent, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            n.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp),
            color = if (danger) Color.White else PILL_INK,
            maxLines = 1,
        )
    }
}

@Composable
private fun TabCell(tab: SyncTabModel, active: Boolean, activeItem: String, onGo: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val total = tab.items.sumOf { it.count }
    val red = tab.items.any { it.danger && it.count > 0 }
    Box(modifier) {
        Row(
            Modifier
                .background(if (active) colors.surface else if (hovered) colors.surfaceHover else Color.Transparent)
                .drawBehind { if (active) drawRect(colors.accent, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx())) }
                .hoverable(interaction)
                .clickable { if (tab.items.isEmpty()) onGo(tab.start) else open = true }
                .padding(horizontal = TAB_PAD_X, vertical = TAB_PAD_Y),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val ink = if (active || hovered) colors.textPrimary else colors.textSecondary
            ZillitIcon(tab.icon, tint = ink, size = ICON)
            ZillitText(
                tab.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                color = ink,
                maxLines = 1,
            )
            if (total > 0) CountPill(total, red)
            if (tab.items.isNotEmpty()) ZillitIcon(ZillitIcons.ChevronDown, tint = ink, size = CARET)
        }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = tab.items.map { item ->
                ZillitMenuEntry.Action(
                    label = item.label,
                    tone = when {
                        item.to == activeItem -> ZillitMenuTone.Primary
                        item.danger && item.count > 0 -> ZillitMenuTone.Danger
                        else -> ZillitMenuTone.Neutral
                    },
                    badge = item.count,
                    onClick = { onGo(item.to) },
                )
            },
        )
    }
}

private val ICON = 16.dp
private val CARET = 12.dp
private val ARROW = 28.dp
private val FADE = 48.dp
private val STRIP_PAD = 12.dp
private val TAB_PAD_X = 22.dp
private val TAB_PAD_Y = 13.dp
private const val PAGE = 0.7f
private val PILL_INK = Color(0xFF1F1A10)
