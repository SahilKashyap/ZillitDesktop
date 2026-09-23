package com.zillit.desktop.feature.accounthub.ui.components

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/** One row of a [HubSideRail]. */
data class RailRow(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val active: Boolean = false,
    /** Unread work under the row — the web's red antd badge, capped. */
    val count: Int = 0,
)

/** A titled card of [RailRow]s; a blank title draws no heading. */
data class RailSection(val title: String, val rows: List<RailRow>)

/**
 * The Account Hub's side rail — the card-stack sidebar the web draws for the
 * hub (`AccountHubSidebar.jsx`) and repeats for the modules that render
 * full-bleed with their own navigation (`PeriodCloseModule.jsx`, the card and
 * invoice modules): a header card with a bordered back chip and the title in
 * extra-bold, then one rounded card per group with a mono uppercase heading, a
 * peach active row with a 3px rail on the card's edge, and a red badge where a
 * row carries unread work.
 *
 * The header stays put and only the groups scroll, as the web's `<nav>` does —
 * scrolling the whole column carried the way back off screen with it — and the
 * groups scroll without a rail, because the web hides that scrollbar and a
 * rail here sits on top of the cards.
 */
@Composable
fun HubSideRail(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    sections: List<RailSection>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    badgeCap: Int = DEFAULT_BADGE_CAP,
) {
    Column(
        modifier = modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .padding(start = ZillitTheme.spacing.md, top = ZillitTheme.spacing.md, bottom = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Box(Modifier.padding(horizontal = SHADOW_ROOM)) {
            RailHeaderCard(title = title, backLabel = backLabel, onBack = onBack)
        }
        // The inset keeps the cards' shadows from being clipped by the scroller.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .zillitVerticalScroll()
                .padding(horizontal = SHADOW_ROOM, vertical = SHADOW_ROOM),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            sections.forEach { section ->
                RailCard {
                    if (section.title.isNotBlank()) {
                        MonoLabel(
                            text = section.title,
                            modifier = Modifier.padding(
                                start = ROW_PADDING_H,
                                top = ZillitTheme.spacing.xs,
                                bottom = ZillitTheme.spacing.xs,
                            ),
                        )
                    }
                    section.rows.forEach { row ->
                        RailRowView(row = row, badgeCap = badgeCap, onClick = { onSelect(row.id) })
                    }
                }
            }
        }
    }
}

/** The header card on its own: a bordered 30px back chip beside the title in extra-bold. */
@Composable
fun RailHeaderCard(title: String, backLabel: String, onBack: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(HEADER_ELEVATION, ZillitTheme.shapes.large, clip = false)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_PADDING_H),
    ) {
        Box(
            modifier = Modifier
                .size(BACK_CHIP)
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = backLabel, onClick = onBack)
        }
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            maxLines = 1,
        )
    }
}

@Composable
private fun RailCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(CARD_ELEVATION, ZillitTheme.shapes.large, clip = false)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        content = content,
    )
}

@Composable
private fun RailRowView(row: RailRow, badgeCap: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = row.active
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .activeRail(active, colors.accent)
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    active -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = ROW_PADDING_H, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_PADDING_H),
    ) {
        val tone = when {
            active -> colors.accentText
            hovered -> colors.textPrimary
            else -> null
        }
        ZillitIcon(icon = row.icon, tint = tone ?: colors.textMuted, size = NAV_ICON)
        ZillitText(
            text = row.label,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontSize = NAV_TEXT,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            ),
            color = tone ?: colors.textSecondary,
            // "Invoices / Accounts Payable" does not fit a rail on one line at
            // this size, and a clipped navigation label reads as a different
            // screen.
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (row.count > 0) {
            ZillitBadge(
                count = row.count,
                background = colors.danger,
                contentColor = colors.textOnAccent,
                cap = badgeCap,
            )
        }
    }
}

/**
 * The web's 3px rail on a lit row. It sits on the card's inner edge
 * (`-left-2.5`), not inside the row: drawn before the row's clip, so it takes
 * no width and every label lines up whether or not its row is lit.
 */
private fun Modifier.activeRail(active: Boolean, color: Color): Modifier = drawBehind {
    if (active) {
        val inset = RAIL_INSET.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(-CARD_PADDING.toPx(), inset),
            size = Size(RAIL.toPx(), size.height - inset * 2),
            cornerRadius = CornerRadius(RAIL.toPx(), RAIL.toPx()),
        )
    }
}

/** The rail's width: the web's 248px column plus its left gutter. */
val RAIL_WIDTH = 264.dp
private val GAP = 10.dp
private val SHADOW_ROOM = 4.dp
private val CARD_PADDING = 10.dp
private val ROW_PADDING_H = 10.dp
private val BACK_CHIP = 30.dp
private val NAV_ICON = 18.dp
private val NAV_TEXT = 13.5.sp
private val RAIL = 3.dp
private val RAIL_INSET = 6.dp
private val HEADER_ELEVATION = 3.dp
private val CARD_ELEVATION = 1.dp
private const val DEFAULT_BADGE_CAP = 99
