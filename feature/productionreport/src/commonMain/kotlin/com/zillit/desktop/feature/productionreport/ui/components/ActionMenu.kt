package com.zillit.desktop.feature.productionreport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/** The icon tile's tone in a row menu — `TONE_BY_KEY`. */
internal enum class MenuTone { Primary, Info, Neutral, Approve, Danger }

internal sealed interface MenuEntry {
    data class Action(
        val key: String,
        val label: String,
        val icon: ImageVector,
        val tone: MenuTone = MenuTone.Neutral,
        val badge: Int = 0,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : MenuEntry

    data object Divider : MenuEntry
}

/**
 * The row kebab — `ActionMenu.jsx`. Click only; a lone Comment becomes a
 * direct icon button; nothing to offer leaves a disabled kebab that keeps the
 * column's width.
 */
@Composable
internal fun ActionMenu(entries: List<MenuEntry>, modifier: Modifier = Modifier, badge: Int = 0) {
    val actions = entries.filterIsInstance<MenuEntry.Action>()
    val single = actions.singleOrNull()?.takeIf { it.key == "comment" }
    Box(modifier) {
        when {
            single != null -> KebabTrigger(
                icon = ReportIcons.Comment,
                description = "Comments",
                enabled = single.enabled,
                onClick = single.onClick,
            )
            actions.isEmpty() -> KebabTrigger(
                icon = ZillitIcons.MoreHorizontal,
                description = "Actions",
                enabled = false,
                onClick = {},
            )
            else -> MenuWithTrigger(entries)
        }
        if (badge > 0) CornerBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
    }
}

@Composable
private fun MenuWithTrigger(entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        KebabTrigger(
            icon = ZillitIcons.MoreHorizontal,
            description = "Actions",
            enabled = true,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = ReportTheme.colors.surface,
            modifier = Modifier.border(1.dp, ReportTheme.colors.border, RoundedCornerShape(12.dp)),
        ) {
            Column(Modifier.widthIn(min = 200.dp).padding(horizontal = 6.dp)) {
                entries.forEach { entry ->
                    when (entry) {
                        MenuEntry.Divider -> Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .height(1.dp)
                                .background(ReportTheme.colors.border),
                        )
                        is MenuEntry.Action -> MenuRow(entry) {
                            open = false
                            entry.onClick()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(action: MenuEntry.Action, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    val danger = action.tone == MenuTone.Danger
    val bg = when {
        !hovered || !action.enabled -> Color.Transparent
        danger -> colors.redBg
        else -> colors.accentLight
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .alpha(if (action.enabled) 1f else DISABLED_ALPHA)
            .hoverable(source)
            .plainClick(enabled = action.enabled, source = source, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (tileBg, tileFg) = toneColors(action.tone)
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(tileBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = tileFg, modifier = Modifier.size(14.dp))
        }
        Text(
            action.label,
            style = reportText(13.sp, FontWeight.Medium),
            color = if (danger && hovered) colors.red else colors.textPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (action.badge > 0) CornerBadge(action.badge)
    }
}

@Composable
internal fun toneColors(tone: MenuTone): Pair<Color, Color> {
    val c = ReportTheme.colors
    return when (tone) {
        MenuTone.Primary -> c.accentLight to c.accent
        MenuTone.Info -> c.blueBg to c.blue
        MenuTone.Approve -> c.greenBg to c.green
        MenuTone.Danger -> c.redBg to c.red
        MenuTone.Neutral -> c.sunken to c.textSecondary
    }
}

/** The 36 px round trigger. */
@Composable
private fun KebabTrigger(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(description) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (hovered && enabled) colors.border else colors.sunken)
                .border(1.dp, if (hovered && enabled) colors.borderStrong else colors.gridLine, CircleShape)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .hoverable(source)
                .plainClick(enabled = enabled, source = source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
        }
    }
}
