@file:Suppress("MatchingDeclarationName") // The menu and its item type, together.

package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.menuToneColors
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * One item of a [RowActionMenu] (`RowActionMenu.jsx:29`): a label, the line
 * under it, the tile's icon and tone, and whether it is the destructive one.
 */
data class RowAction(
    val label: String,
    val onClick: () -> Unit,
    val description: String? = null,
    val icon: ImageVector? = null,
    val tone: ZillitMenuTone = ZillitMenuTone.Neutral,
    val danger: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * A table row's "⋯" menu (`ui/RowActionMenu.jsx`) — the row's actions behind
 * one trigger, because more than one inline button distorts a row.
 *
 * The web's chrome: a bordered chip that turns amber while open, and a panel
 * headed [title] whose items are a toned tile, a label and a description.
 * Draws nothing when [items] is empty, as the web's does. The trigger's click
 * is its own — it never reaches the row's click, which opens the detail.
 */
@Composable
fun RowActionMenu(
    items: List<RowAction>,
    modifier: Modifier = Modifier,
    title: String = str(S.dd_actions),
) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val colors = ZillitTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(TRIGGER_RADIUS)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(TRIGGER)
                .clip(shape)
                .background(
                    when {
                        open -> colors.accentSoft
                        hovered -> colors.surfaceHover
                        else -> Color.Transparent
                    },
                )
                .border(1.dp, if (open) colors.accent else colors.borderStrong, shape)
                .hoverable(source)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open }
                .semantics { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                ZillitIcons.MoreHorizontal,
                size = GLYPH,
                tint = if (open) colors.accent else colors.textSecondary,
            )
        }
        ZillitMenuSurface(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, MENU_GAP),
        ) {
            Column(modifier = Modifier.width(PANEL_WIDTH)) {
                ZillitText(
                    text = title.uppercase(),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                )
                items.forEach { item ->
                    RowActionItem(item) {
                        open = false
                        item.onClick()
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod") // One item's chrome: tile, label and description.
@Composable
private fun RowActionItem(item: RowAction, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val (tileBg, tileFg) = menuToneColors(if (item.danger) ZillitMenuTone.Danger else item.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xs)
            .clip(RoundedCornerShape(ROW_RADIUS))
            .background(
                when {
                    !hovered || !item.enabled -> Color.Transparent
                    item.danger -> colors.dangerSoft
                    else -> colors.surfaceHover
                },
            )
            .alpha(if (item.enabled) 1f else DISABLED_ALPHA)
            .hoverable(source)
            .pointerHoverIcon(if (item.enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                enabled = item.enabled,
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tile carries the icon, or the label's initial when there is none.
        Box(
            modifier = Modifier.size(TILE).clip(RoundedCornerShape(TILE_RADIUS)).background(tileBg),
            contentAlignment = Alignment.Center,
        ) {
            val icon = item.icon
            if (icon != null) {
                ZillitIcon(icon, size = TILE_GLYPH, tint = tileFg)
            } else {
                ZillitText(
                    text = item.label.trim().take(1).uppercase(),
                    style = ZillitTheme.typography.label,
                    color = tileFg,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = item.label,
                style = ZillitTheme.typography.titleSmall,
                color = if (item.danger) colors.danger else colors.textPrimary,
                maxLines = 1,
            )
            item.description?.let { line ->
                ZillitText(
                    text = line,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

private val TRIGGER = 28.dp
private val TRIGGER_RADIUS = 8.dp
private val GLYPH = 17.dp
private val MENU_GAP = 8.dp
private val PANEL_WIDTH = 320.dp
private val ROW_RADIUS = 10.dp
private val TILE = 38.dp
private val TILE_RADIUS = 10.dp
private val TILE_GLYPH = 18.dp
private const val DISABLED_ALPHA = 0.5f
