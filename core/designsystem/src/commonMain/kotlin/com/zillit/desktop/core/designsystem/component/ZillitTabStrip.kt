package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.zillitHorizontalScroll

/** One destination in a [ZillitTabStrip]. */
data class ZillitTab(
    val id: String,
    val label: String,
    /** Unread work waiting on this tab. Zero renders no chip. */
    val count: Int = 0,
)

/** How prominent a strip is — the module switcher, or the page switcher under it. */
enum class TabStripSize { Primary, Secondary }

/**
 * Underline tabs.
 *
 * The Account Hub modules stack two of these: a primary strip choosing the
 * section (Petty Cash / Out of Pocket) and a secondary one choosing the page
 * within it. Rendering both at the same weight made the hierarchy unreadable,
 * which is why size is a parameter rather than two components.
 *
 * The strip scrolls horizontally rather than wrapping: a wrapped second row of
 * tabs moves the content down the page as the user's rights change, and a tool
 * whose layout shifts between two people looking at it is hard to talk about.
 */
@Composable
fun ZillitTabStrip(
    tabs: List<ZillitTab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: TabStripSize = TabStripSize.Secondary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    if (tabs.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).zillitHorizontalScroll(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                UnderlineTab(
                    tab = tab,
                    active = tab.id == activeId,
                    size = size,
                    onClick = { onSelect(tab.id) },
                )
            }
        }
        trailing?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                content = it,
            )
        }
    }
}

@Composable
private fun UnderlineTab(
    tab: ZillitTab,
    active: Boolean,
    size: TabStripSize,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val content by animateColorAsState(
        when {
            active -> colors.accentText
            hovered -> colors.textPrimary
            else -> colors.textSecondary
        },
        label = "tabContent",
    )
    val horizontal = if (size == TabStripSize.Primary) ZillitTheme.spacing.lg else ZillitTheme.spacing.md
    val vertical = if (size == TabStripSize.Primary) ZillitTheme.spacing.md else ZillitTheme.spacing.sm

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = horizontal, vertical = vertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = tab.label,
                style = (
                    if (size == TabStripSize.Primary) {
                        ZillitTheme.typography.bodyLarge
                    } else {
                        ZillitTheme.typography.bodyMedium
                    }
                    ).copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                color = content,
                maxLines = 1,
            )
            ZillitBadge(count = tab.count)
        }
        Spacer(
            modifier = Modifier
                .padding(horizontal = horizontal)
                .fillMaxWidth()
                .height(INDICATOR_HEIGHT)
                .clip(ZillitTheme.shapes.pill)
                .background(if (active) colors.tabIndicator else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

/**
 * Segmented control — a small, closed set of mutually exclusive filters.
 *
 * Used where an underline strip would be too heavy: "All / Mine / Flagged"
 * above a table, currency selection on a multi-currency register.
 */
@Composable
fun ZillitSegmented(
    options: List<ZillitTab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(SEGMENT_INSET),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_INSET),
    ) {
        options.forEach { option ->
            val active = option.id == activeId
            Box(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (active) colors.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(option.id) }
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = option.label,
                    style = ZillitTheme.typography.label,
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

private val INDICATOR_HEIGHT: Dp = 2.dp
private val SEGMENT_INSET: Dp = 3.dp
