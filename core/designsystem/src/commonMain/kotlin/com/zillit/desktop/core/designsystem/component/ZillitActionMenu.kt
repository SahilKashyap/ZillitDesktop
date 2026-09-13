package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme

/** The icon tile's tone in a menu row — the call sheet's `TONE_BY_KEY`. */
enum class ZillitMenuTone { Primary, Info, Neutral, Approve, Danger }

/** One line of a [ZillitActionMenu]: an action, or the rule between two groups. */
sealed interface ZillitMenuEntry {
    data class Action(
        val label: String,
        val icon: ImageVector? = null,
        val tone: ZillitMenuTone = ZillitMenuTone.Neutral,
        val badge: Int = 0,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : ZillitMenuEntry

    data object Divider : ZillitMenuEntry
}

/**
 * The popup every action menu hangs from — the call sheet's row menu
 * (`ActionMenu.jsx`, ported as the call sheet's `ActionMenu`), lifted into the
 * design system so the boards, chat and mail open the same one: a 12 px
 * card on the raised surface, rimmed by a hairline, 4 px under its anchor.
 *
 * [content] is whatever the menu holds. Lists of actions go through
 * [ZillitActionMenu]; palettes and pickers put their own content here.
 */
@Composable
fun ZillitMenuSurface(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, MENU_DROP),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(MENU_RADIUS)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        properties = properties,
        shape = shape,
        containerColor = colors.surfaceRaised,
        modifier = modifier.border(1.dp, colors.border, shape),
        content = content,
    )
}

/**
 * A menu of actions in the call sheet's style: every row a toned icon tile
 * and a medium label, a wash of accent under the pointer (red for a
 * [ZillitMenuTone.Danger] row), rules between groups. A row without an icon
 * keeps the label's alignment by leaving the tile's space empty.
 */
@Composable
fun ZillitActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<ZillitMenuEntry>,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, MENU_DROP),
    properties: PopupProperties = PopupProperties(focusable = true),
) {
    ZillitMenuSurface(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        properties = properties,
    ) {
        ZillitMenuEntries(entries = entries, onDismiss = onDismissRequest)
    }
}

/**
 * The rows of a menu, for a [ZillitMenuSurface] that holds something else
 * above them — chat's quick reactions, a palette's "Default". Each action
 * closes the menu before it runs.
 */
@Composable
fun ZillitMenuEntries(entries: List<ZillitMenuEntry>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(min = MENU_MIN_WIDTH).padding(horizontal = MENU_INSET)) {
        entries.forEach { entry ->
            when (entry) {
                ZillitMenuEntry.Divider -> ZillitMenuDivider()
                is ZillitMenuEntry.Action -> ZillitMenuRow(entry) {
                    onDismiss()
                    entry.onClick()
                }
            }
        }
    }
}

/** The rule between two groups of rows. */
@Composable
fun ZillitMenuDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = MENU_INSET, vertical = ZillitTheme.spacing.xxs)
            .height(1.dp)
            .background(ZillitTheme.colors.border),
    )
}

/** One row: the tile, the label, and a count badge when the action carries one. */
@Composable
fun ZillitMenuRow(action: ZillitMenuEntry.Action, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val danger = action.tone == ZillitMenuTone.Danger
    val bg = when {
        !hovered || !action.enabled -> Color.Transparent
        danger -> colors.dangerSoft
        else -> colors.accentSoft
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_RADIUS))
            .background(bg)
            .alpha(if (action.enabled) 1f else DISABLED_ALPHA)
            .hoverable(source)
            .pointerHoverIcon(if (action.enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                enabled = action.enabled,
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = ROW_PADDING_X, vertical = ROW_PADDING_Y),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (tileBg, tileFg) = menuToneColors(action.tone)
        Box(
            Modifier
                .size(TILE)
                .clip(RoundedCornerShape(ROW_RADIUS))
                .background(if (action.icon != null) tileBg else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            action.icon?.let { icon ->
                Icon(icon, contentDescription = null, tint = tileFg, modifier = Modifier.size(TILE_GLYPH))
            }
        }
        ZillitText(
            text = action.label,
            style = ZillitTheme.typography.bodyMedium.copy(fontSize = LABEL_SIZE, fontWeight = FontWeight.Medium),
            color = if (danger && hovered) colors.danger else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (action.badge > 0) ZillitBadge(count = action.badge)
    }
}

/** The tile's fill and glyph for a tone. */
@Composable
fun menuToneColors(tone: ZillitMenuTone): Pair<Color, Color> {
    val c = ZillitTheme.colors
    return when (tone) {
        ZillitMenuTone.Primary -> c.accentSoft to c.accent
        ZillitMenuTone.Info -> c.infoSoft to c.info
        ZillitMenuTone.Approve -> c.successSoft to c.success
        ZillitMenuTone.Danger -> c.dangerSoft to c.danger
        ZillitMenuTone.Neutral -> c.surfaceSunken to c.textSecondary
    }
}

private val MENU_RADIUS = 12.dp
private val MENU_DROP = 4.dp
private val MENU_MIN_WIDTH = 200.dp
private val MENU_INSET = 6.dp
private val ROW_RADIUS = 8.dp
private val ROW_PADDING_X = 10.dp
private val ROW_PADDING_Y = 6.dp
private val ROW_GAP = 10.dp
private val TILE = 28.dp
private val TILE_GLYPH = 14.dp
private val LABEL_SIZE = 13.sp
private const val DISABLED_ALPHA = 0.5f
