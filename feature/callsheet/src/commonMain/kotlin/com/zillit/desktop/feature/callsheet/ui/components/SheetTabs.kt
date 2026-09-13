// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One option of the top tab bar. */
internal class ModeTab<T>(val value: T, val label: String, val badge: Int = 0)

/**
 * The top tab bar — `csc-mode-segmented`, antd's large Segmented: a tinted
 * track, and an amber thumb with a warm glow that slides to the chosen tab.
 */
@Composable
@Suppress("CyclomaticComplexMethod") // One branch per visual state of the thumb and the labels.
internal fun <T> ModeSegmented(
    tabs: List<ModeTab<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = SheetTheme.colors
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val thumbX = remember { Animatable(0f) }
    val thumbW = remember { Animatable(0f) }
    val index = tabs.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val target = bounds[index]
    LaunchedEffect(target) {
        val (x, w) = target ?: return@LaunchedEffect
        if (thumbW.value == 0f) {
            thumbX.snapTo(x)
            thumbW.snapTo(w)
        } else {
            coroutineScope {
                launch { thumbX.animateTo(x, tween(THUMB_MS)) }
                launch { thumbW.animateTo(w, tween(THUMB_MS)) }
            }
        }
    }
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.dsBgTertiary)
            .padding(6.dp),
    ) {
        if (thumbW.value > 0f) {
            Box(
                Modifier
                    .offset { IntOffset(thumbX.value.roundToInt(), 0) }
                    .width(with(density) { thumbW.value.toDp() })
                    .height(if (compact) 36.dp else 40.dp)
                    .shadow(6.dp, RoundedCornerShape(9.dp), ambientColor = GLOW, spotColor = GLOW)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.dsPrimary),
            )
        }
        Row {
            tabs.forEachIndexed { i, tab ->
                val active = i == index
                val (source, hovered) = rememberHover()
                val ink by animateColorAsState(
                    when {
                        active -> Color.White
                        colors.isDark && hovered -> Color(0xF2FFFFFF)
                        colors.isDark -> Color(0xBFFFFFFF)
                        else -> colors.dsText
                    },
                    tween(INK_MS),
                )
                Row(
                    modifier = Modifier
                        .height(if (compact) 36.dp else 40.dp)
                        .onPlaced { coordinates ->
                            bounds[i] = coordinates.positionInParent().x to coordinates.size.width.toFloat()
                        }
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (hovered && !active) colors.dsBgHover.copy(alpha = 0.6f) else Color.Transparent)
                        .hoverable(source)
                        .plainClick(source = source) { onSelect(tab.value) }
                        .padding(horizontal = if (compact) 10.dp else 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.label,
                        style = sheetText(if (compact) 13.sp else 14.sp, FontWeight.SemiBold),
                        color = ink,
                        maxLines = 1,
                        softWrap = false,
                    )
                    InlineCount(tab.badge)
                }
            }
        }
    }
}

private val GLOW = Color(0x59E8930C)
private const val THUMB_MS = 260
private const val INK_MS = 150

/** antd's inline small Badge: a 16 px red pill beside a label; nothing at zero. */
@Composable
internal fun InlineCount(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .height(16.dp)
            .clip(CircleShape)
            .background(Color(0xFFFF4D4F))
            .padding(horizontal = if (count > 9) 5.dp else 0.dp)
            .then(if (count <= 9) Modifier.width(16.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_COUNT) "99+" else count.toString(),
            style = sheetText(11.sp, FontWeight.Medium, 12.sp),
            color = Color.White,
        )
    }
}

private const val MAX_COUNT = 99

/** One Approvals section tab. */
internal class CardTabItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector,
    val iconTint: Color,
    val badge: Int = 0,
)

/**
 * The Approvals section bar — antd card tabs: tinted tabs with rounded tops on
 * a baseline; the active one takes the card colour, an amber bar along its top
 * edge and amber ink.
 */
@Composable
internal fun <T> CardTabs(items: List<CardTabItem<T>>, selected: T, onSelect: (T) -> Unit) {
    val colors = SheetTheme.colors
    Box(Modifier.padding(bottom = 14.dp).fillMaxWidth()) {
        Box(
            Modifier.matchParentSize().drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(colors.dsBorder, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEach { item ->
                val active = item.value == selected
                val (source, hovered) = rememberHover()
                val fill by animateColorAsState(
                    when {
                        active -> colors.dsCard
                        hovered -> if (colors.isDark) colors.dsBgHover else colors.dsBgHover
                        else -> colors.dsBgSecondary
                    },
                    tween(CARD_MS),
                )
                val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(fill)
                        .border(1.dp, colors.dsBorder, shape)
                        .drawBehind {
                            if (active) {
                                drawRect(colors.dsPrimary, size = size.copy(height = 3.dp.toPx()))
                                // The active tab merges into the panel below.
                                val y = size.height - 1.dp.toPx()
                                drawLine(fill, Offset(1.dp.toPx(), y), Offset(size.width - 1.dp.toPx(), y), 2.dp.toPx())
                            }
                        }
                        .hoverable(source)
                        .plainClick(source = source) { onSelect(item.value) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = null, tint = item.iconTint, modifier = Modifier.size(14.dp))
                    Text(
                        item.label,
                        style = sheetText(13.5.sp, if (active) FontWeight.Bold else FontWeight.SemiBold),
                        color = if (active) colors.dsPrimary else colors.dsTextMuted,
                    )
                    InlineCount(item.badge)
                }
            }
        }
    }
}

private const val CARD_MS = 180

/** `.csc-filter-chip`: a pill with its live count; pressed turns hub amber. */
@Composable
internal fun FilterChip(label: String, count: Int, pressed: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val fill by animateColorAsState(if (pressed) colors.dsPrimary else colors.dsCard, tween(CHIP_MS))
    val rim by animateColorAsState(
        when {
            pressed || hovered -> colors.dsPrimary
            else -> colors.dsBorder
        },
        tween(CHIP_MS),
    )
    Row(
        modifier = Modifier
            .then(if (pressed) Modifier.shadow(4.dp, CircleShape, ambientColor = GLOW, spotColor = GLOW) else Modifier)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, rim, CircleShape)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = sheetText(12.5.sp, FontWeight.Bold),
            color = if (pressed) Color.White else colors.dsTextSecondary,
        )
        Box(
            Modifier
                .defaultMinSize(minWidth = 20.dp)
                .height(20.dp)
                .clip(CircleShape)
                .background(if (pressed) Color(0x40FFFFFF) else colors.dsInfoBg)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$count",
                style = sheetText(11.sp, FontWeight.ExtraBold, 12.sp),
                color = if (pressed) Color.White else colors.dsInfo,
            )
        }
    }
}

private const val CHIP_MS = 180

/**
 * `.csc-icon-btn`: a 26 px rounded square on the secondary tint — the
 * template delete, the Sent comment chip. [danger] reddens it on hover.
 */
@Composable
internal fun CscIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    badge: Int = 0,
) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val rim = when {
        hovered && danger -> colors.dsError
        hovered -> colors.dsPrimary
        else -> colors.dsBorder
    }
    val ink = when {
        hovered && danger -> colors.dsError
        hovered -> colors.dsPrimary
        else -> colors.dsTextMuted
    }
    ZillitTooltip(description) {
        Box(modifier) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            hovered && danger -> colors.dsErrorBg
                            hovered -> colors.dsBgHover
                            else -> colors.dsBgSecondary
                        },
                    )
                    .border(1.dp, rim, RoundedCornerShape(8.dp))
                    .hoverable(source)
                    .plainClick(source = source, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = description, tint = ink, modifier = Modifier.size(13.dp))
            }
            if (badge > 0) {
                CornerBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
            }
        }
    }
}

/** The info banner — `.csc-banner--info`. */
@Composable
internal fun InfoBanner(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = SheetTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.dsInfoBg)
            .border(1.5.dp, colors.dsInfoBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = colors.dsInfo, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(text, style = sheetText(13.sp, FontWeight.SemiBold), color = colors.dsInfo)
    }
}

/** A labelled strip header — `.csc-strip-label`. */
@Composable
internal fun StripLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = sheetText(11.sp, FontWeight.Bold).copy(letterSpacing = 1.1.sp),
        color = SheetTheme.colors.dsTextMuted,
        modifier = modifier,
    )
}

/** Stacks its children with the call sheet's 12 px rhythm. */
@Composable
internal fun Stack(modifier: Modifier = Modifier, gap: Int = 12, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap.dp)) { content() }
}
