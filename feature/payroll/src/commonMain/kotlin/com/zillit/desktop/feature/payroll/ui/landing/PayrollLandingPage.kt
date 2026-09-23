package com.zillit.desktop.feature.payroll.ui.landing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollTile
import com.zillit.desktop.feature.payroll.ui.PayrollUiState

/**
 * The payroll landing — the web's `PayrollLandingPage`: a header and a grid of
 * tiles, each an icon in its own accent, a title, a description and its tags.
 * Which tiles show is [PayrollTile.visibleTo]: the accountant grid from the
 * Account Hub; nothing ported for anyone else, which the page says rather than
 * leaving the grid empty.
 */
@Composable
fun PayrollLandingPage(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier = Modifier) {
    val tiles = PayrollTile.visibleTo(state.viewer, state.enteredAsTool)
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitPageHeader(
            eyebrow = str(S.desktop_payroll_management),
            title = str(S.dm_section_payroll),
            description = str(S.desktop_payroll_landing_description),
        )
        if (tiles.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_payroll_no_views_title),
                message = if (state.viewer.canView || !state.viewer.rightsLoaded) {
                    str(S.desktop_payroll_producer_views_message)
                } else {
                    str(S.desktop_payroll_no_access_message)
                },
                icon = ZillitIcons.Lock,
            )
        } else {
            TileGrid(tiles) { onEvent(PayrollEvent.OpenTile(it)) }
        }
    }
}

/** Four across on a wide window, two on a narrow one — the web's `sm:grid-cols-2 lg:grid-cols-4`. */
@Composable
private fun TileGrid(tiles: List<PayrollTile>, onOpen: (PayrollTile) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= WIDE -> FOUR
            maxWidth >= NARROW -> TWO
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            tiles.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    row.forEach { tile ->
                        LandingTile(tile, Modifier.weight(1f).fillMaxHeight()) { onOpen(tile) }
                    }
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One tile. The arrow beside the title is revealed on hover by alpha, never
 * by composing it in — a control composed only on hover loses the press.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LandingTile(tile: PayrollTile, modifier: Modifier, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    val accent = Color(tile.accent)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, if (hovered) colors.accent else colors.border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(ICON_BOX)
                .clip(ZillitTheme.shapes.medium)
                .background(accent.copy(alpha = ICON_FILL))
                .border(1.dp, accent.copy(alpha = ICON_RING), ZillitTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = tile.icon, contentDescription = null, tint = accent, size = ICON)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = tile.title,
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            ZillitIcon(
                icon = ZillitIcons.ArrowRight,
                contentDescription = null,
                tint = colors.accent,
                size = ARROW,
                modifier = Modifier.alpha(if (hovered) 1f else 0f),
            )
        }
        ZillitText(text = tile.description, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            tile.tags.forEach { ZillitTag(label = it, tone = TagTone.Neutral) }
        }
    }
}

private val PayrollTile.icon: ImageVector
    get() = when (this) {
        PayrollTile.Processing -> ZillitIcons.BarChart
        PayrollTile.Run -> ZillitIcons.Calendar
        PayrollTile.History -> ZillitIcons.Ledger
        PayrollTile.EntrySetup -> ZillitIcons.Settings
    }

private val WIDE = 1040.dp
private val NARROW = 560.dp
private const val FOUR = 4
private const val TWO = 2
private val ICON_BOX = 40.dp
private val ICON = 20.dp
private val ARROW = 13.dp
private const val ICON_FILL = 0.08f
private const val ICON_RING = 0.19f
