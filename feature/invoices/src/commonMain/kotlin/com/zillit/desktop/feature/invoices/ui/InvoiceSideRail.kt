package com.zillit.desktop.feature.invoices.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The accountant's sidebar — the live web one (`InvoicesModule.jsx`
 * `InvoiceSidebar`), drawn the way the Account Hub draws its own rail.
 *
 * A header card with a bordered back chip and the module's title in
 * extra-bold, then one rounded card per group: a mono uppercase heading, a
 * peach active row with a 3px accent rail on the card's inner edge, and a red
 * badge where a row carries unread work. No period line and no shortcut
 * strip — both belonged to the web's old `Sidebar.jsx`, which nothing renders
 * any more.
 *
 * The back chip matters more than it looks: inside the Account Hub this
 * module is shown full-bleed, without the hub's own sidebar, so this chip is
 * the only way back to the hub.
 *
 * The header stays put and only the groups scroll, as the web's `<nav>` does,
 * so the way back never scrolls off screen.
 */
@Composable
internal fun InvoiceSideRail(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = AccountantPage.visibleTo(state.viewer)
    Column(
        modifier = modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .padding(start = ZillitTheme.spacing.md, top = ZillitTheme.spacing.md, bottom = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Box(Modifier.padding(horizontal = SHADOW_ROOM)) {
            RailHeaderCard(
                // The web's card says "Invoices" — the hub row's longer
                // "Invoices / Accounts Payable" is the hub's label, not this one.
                title = str(S.ah_invoices),
                backLabel = str(S.desktop_hub_back_to_account_hub),
                onBack = onBack,
            )
        }
        // The inset keeps the cards' shadows from being clipped by the scroller.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHADOW_ROOM, vertical = SHADOW_ROOM),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            InvoiceNavGroup.entries.forEach { group ->
                val rows = pages.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                RailCard {
                    group.label?.let { heading ->
                        RailHeading(
                            text = heading,
                            modifier = Modifier.padding(
                                start = ROW_PADDING_H,
                                top = ZillitTheme.spacing.xxs,
                                bottom = ZillitTheme.spacing.xs,
                            ),
                        )
                    }
                    rows.forEach { page ->
                        RailRow(
                            page = page,
                            active = page == state.page,
                            unread = page.badgeKey?.let(state.unread::get) ?: 0,
                            onClick = { onEvent(InvoicesEvent.SelectPage(page)) },
                        )
                    }
                }
            }
        }
    }
}

/** The header card: a bordered 30px back chip beside the title in extra-bold. */
@Composable
private fun RailHeaderCard(title: String, backLabel: String, onBack: () -> Unit) {
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

/** A group's heading — the web's DM Mono, 10px, bold, 0.12em, upper case. */
@Composable
private fun RailHeading(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        ),
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
        maxLines = 1,
    )
}

@Composable
private fun RailRow(page: AccountantPage, active: Boolean, unread: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rail = colors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The web's rail sits on the card's inner edge (`-left-2.5`), not
            // inside the row: drawn before the clip, so it takes no width and
            // every label lines up whether or not its row is lit.
            .drawBehind {
                if (active) {
                    val inset = RAIL_INSET.toPx()
                    drawRoundRect(
                        color = rail,
                        topLeft = Offset(-CARD_PADDING.toPx(), inset),
                        size = Size(RAIL.toPx(), size.height - inset * 2),
                        cornerRadius = CornerRadius(RAIL.toPx(), RAIL.toPx()),
                    )
                }
            }
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
        ZillitIcon(icon = page.icon, tint = tone ?: colors.textMuted, size = NAV_ICON)
        ZillitText(
            text = page.label,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontSize = NAV_TEXT,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            ),
            color = tone ?: colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Unread notifications filed under the page — the web's red antd chip.
        if (unread > 0) {
            ZillitBadge(
                count = unread,
                background = colors.danger,
                contentColor = colors.textOnAccent,
                cap = BADGE_CAP,
            )
        }
    }
}

/** The rail's width: the web's 248px column plus its left gutter. */
private val RAIL_WIDTH = 264.dp
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

/** The web's `overflowCount={99}`. */
private const val BADGE_CAP = 99
