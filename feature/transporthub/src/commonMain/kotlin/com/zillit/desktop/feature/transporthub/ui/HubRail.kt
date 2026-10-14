// The sidebar and the row it is built from; one file, as the web keeps them.
@file:Suppress("MatchingDeclarationName")

package com.zillit.desktop.feature.transporthub.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubText
import com.zillit.desktop.feature.transporthub.ui.kit.hub

/** One destination in the hub's rail. */
internal data class HubRailRow(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val badge: Int = 0,
    val active: Boolean = false,
)

/**
 * The hub's sidebar — the web's `transportationHub/common/Layout.jsx`.
 *
 * A white column with a rule on its right: the title (with the TEMP DRIVER
 * tag for anyone a coordinator made one) and the role's destinations with
 * amber active rows. The web's Film Tools button and profile card are left
 * out on purpose — the desktop window has its own way out, and the app shows
 * who is signed in. It collapses to a 72dp strip through a round handle that sits
 * astride its right edge, and every row keeps a tooltip while collapsed —
 * the only thing that tells an icon apart then.
 */
@Composable
internal fun HubRail(
    rows: List<HubRailRow>,
    onSelect: (String) -> Unit,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    isTempDriver: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = hub
    val width by animateDpAsState(
        if (collapsed) COLLAPSED_WIDTH else EXPANDED_WIDTH,
        animationSpec = tween(durationMillis = SLIDE_MS),
    )
    // Drawn above the page so the handle's outer half is not covered by it.
    Box(modifier.fillMaxHeight().width(width).zIndex(1f)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .background(p.surface)
                .drawBehind {
                    drawRect(
                        p.border,
                        topLeft = Offset(size.width - 1.dp.toPx(), 0f),
                        size = size.copy(width = 1.dp.toPx()),
                    )
                },
        ) {
            RailHeader(collapsed = collapsed, isTempDriver = isTempDriver)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .zillitVerticalScroll()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEach { row ->
                    RailItem(row = row, collapsed = collapsed, onClick = { onSelect(row.id) })
                }
            }
        }
        CollapseHandle(
            collapsed = collapsed,
            onToggle = onToggleCollapse,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = HANDLE / 2, y = 28.dp),
        )
    }
}

/** The "• TEMP DRIVER" line under the hub's name, with why on hover. */
@Composable
private fun TempDriverBadge() {
    val p = hub
    ZillitTooltip(text = str(S.desktop_hub_temp_driver_hint)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The dot wears a 3px halo, as the web's box-shadow ring.
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.2f)),
                Alignment.Center,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(p.accent))
            }
            HubText(
                str(S.desktop_hub_temp_driver).uppercase(),
                10.sp,
                p.accentDark,
                weight = FontWeight.Bold,
                spacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun RailHeader(collapsed: Boolean, isTempDriver: Boolean) {
    val p = hub
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    p.borderLight,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = size.copy(height = 1.dp.toPx()),
                )
            }
            .padding(
                start = if (collapsed) 10.dp else 16.dp,
                end = if (collapsed) 10.dp else 16.dp,
                top = if (collapsed) 14.dp else 16.dp,
                bottom = 14.dp,
            ),
        horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (!collapsed) {
            HubText(
                str(S.desktop_hub_title),
                18.sp,
                p.textPrimary,
                weight = FontWeight.Bold,
                spacing = (-0.2).sp,
                modifier = Modifier.padding(top = 4.dp, bottom = if (isTempDriver) 2.dp else 0.dp),
            )
            // The only thing on screen that explains why the driver rows
            // appeared in this person's sidebar.
            if (isTempDriver) TempDriverBadge()
        }
    }
}

@Composable
private fun RailItem(row: HubRailRow, collapsed: Boolean, onClick: () -> Unit) {
    val p = hub
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fg = if (row.active || hovered) p.menuActiveText else p.textPrimary
    val body = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        row.active -> p.menuActiveBg
                        hovered -> p.menuHoverBg
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = if (collapsed) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) {
                Arrangement.Center
            } else {
                Arrangement.spacedBy(10.dp)
            },
        ) {
            ZillitIcon(row.icon, tint = fg, size = 18.dp)
            if (!collapsed) {
                HubText(row.label, 14.sp, fg, modifier = Modifier.weight(1f))
                if (row.badge > 0) Badge(row.badge)
            }
        }
    }
    // Collapsed, the icon is all there is — so the label and its count move
    // into the tooltip, exactly as the web's collapsed menu does.
    if (collapsed) {
        val label = if (row.badge > 0) "${row.label} (${row.badge})" else row.label
        ZillitTooltip(text = label) { body() }
    } else {
        body()
    }
}

/** antd's small badge in the hub's accent, capped at 99+. */
@Composable
private fun Badge(count: Int) {
    HubText(
        if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString(),
        10.sp,
        Color.White,
        weight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(hub.accent).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The round handle astride the rail's right edge. */
@Composable
private fun CollapseHandle(collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val p = hub
    val label = if (collapsed) str(S.desktop_hub_expand_menu) else str(S.desktop_hub_collapse_menu)
    Box(modifier) {
        ZillitTooltip(text = label) {
            Box(
                modifier = Modifier
                    .size(HANDLE)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(p.surface)
                    .border(1.5.dp, p.accent, CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    if (collapsed) HubGlyphs.CaretRight else HubGlyphs.CaretLeft,
                    tint = p.accent,
                    size = 14.dp,
                    contentDescription = label,
                )
            }
        }
    }
}

private val EXPANDED_WIDTH = 208.dp
private val COLLAPSED_WIDTH = 72.dp
private val HANDLE = 28.dp
private const val SLIDE_MS = 320
private const val MAX_BADGE = 99
