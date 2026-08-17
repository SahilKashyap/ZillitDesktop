package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.ZillitTheme

/** One destination in a [ZillitSideNav]. */
data class SideNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val count: Int = 0,
)

/** A titled group of [SideNavItem]s. A null [title] renders ungrouped. */
data class SideNavSection(
    val title: String?,
    val items: List<SideNavItem>,
)

/**
 * The left navigation for a tool with more destinations than a tab strip can
 * hold.
 *
 * Card Expenses has fourteen accountant surfaces; as tabs they neither fit nor
 * group, and the web ships a sidebar for exactly that reason. Sections are
 * rendered as separate cards — the web's own arrangement — so the eye lands on
 * a group of three rather than a list of fourteen.
 */
@Composable
fun ZillitSideNav(
    sections: List<SideNavSection>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val navState = rememberLazyListState()
    LazyColumn(
        state = navState,
        modifier = modifier
            .width(SIDE_NAV_WIDTH)
            .padding(ZillitTheme.spacing.md)
            .then(rememberWheelScroll(navState)),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        header?.let { item { it() } }
        items(sections) { section ->
            NavSection(section = section, activeId = activeId, onSelect = onSelect)
        }
    }
}

@Composable
private fun NavSection(section: SideNavSection, activeId: String?, onSelect: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(CARD_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        section.title?.let {
            ZillitSectionLabel(
                text = it,
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.sm,
                    top = ZillitTheme.spacing.xs,
                    bottom = ZillitTheme.spacing.xs,
                ),
            )
        }
        section.items.forEach { item ->
            NavRow(item = item, active = item.id == activeId, onClick = { onSelect(item.id) })
        }
    }
}

@Composable
private fun NavRow(item: SideNavItem, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        active -> colors.railActive
        hovered -> colors.surfaceHover
        else -> Color.Transparent
    }
    val content = if (active) colors.accentText else colors.textSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(item.icon, tint = content, size = ZillitDimens.iconSmall)
        ZillitText(
            text = item.label,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (active) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (item.count > 0) {
            ZillitBadge(
                count = item.count,
                background = colors.warningSoft,
                contentColor = colors.warning,
            )
        } else {
            Spacer(Modifier)
        }
    }
}

val SIDE_NAV_WIDTH: Dp = 236.dp
