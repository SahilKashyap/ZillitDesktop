// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("CyclomaticComplexMethod")

package com.zillit.desktop.feature.productionreport.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/** The app font at a web size — every report surface sets its own size and weight. */
@Composable
internal fun reportText(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
): TextStyle =
    ZillitTheme.typography.bodyMedium.copy(
        fontSize = size,
        fontWeight = weight,
        lineHeight = if (lineHeight == TextUnit.Unspecified) (size.value * LINE_FACTOR).sp else lineHeight,
    )

private const val LINE_FACTOR = 1.45f

/** Hover state for any surface, without a ripple. */
@Composable
internal fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

/** A click with a pointer cursor and no ripple. */
internal fun Modifier.plainClick(
    enabled: Boolean = true,
    source: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier =
    this.pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(
            enabled = enabled,
            interactionSource = source,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )

/** `StatusBadge` — `.wa-status-badge`: a 11 px pill in the status's colours. */
@Composable
internal fun StatusBadge(status: ReportStatus, label: String = status.label, modifier: Modifier = Modifier) {
    val (bg, fg) = ReportTheme.colors.status(status)
    Text(
        text = label,
        style = reportText(11.sp, FontWeight.Medium, 14.sp),
        color = fg,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** `TabBadge` — red in light, orange in dark; nothing at zero; 99+ above 99. */
@Composable
internal fun TabBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(ReportTheme.colors.tabBadge)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_BADGE) "99+" else count.toString(),
            style = reportText(11.sp, FontWeight.SemiBold, 12.sp),
            color = Color.White,
        )
    }
}

/** antd's small row badge: a red count riding a control's corner. */
@Composable
internal fun CornerBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp)
            .height(16.dp)
            .clip(CircleShape)
            .background(Color(0xFFFF4D4F))
            .border(1.dp, ReportTheme.colors.surface, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_BADGE) "99+" else count.toString(),
            style = reportText(10.sp, FontWeight.SemiBold, 11.sp),
            color = Color.White,
        )
    }
}

private const val MAX_BADGE = 99

/**
 * An underline tab. [strong] is the Manage strip (ink text, 4 px inset bar);
 * otherwise the Approvals section row (orange text, 3 px bar).
 */
@Composable
internal fun UnderlineTab(label: String, active: Boolean, badge: Int, strong: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    val textColor by animateColorAsState(
        when {
            active && strong -> colors.textPrimary
            active -> colors.accent
            hovered -> colors.textSecondary
            else -> colors.textMuted
        },
        tween(TAB_FADE_MS),
    )
    val barHeight by animateDpAsState(if (active) (if (strong) 4.dp else 3.dp) else 0.dp, tween(TAB_FADE_MS))
    Row(
        modifier = Modifier
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .drawBehind {
                if (barHeight > 0.dp) {
                    val h = barHeight.toPx()
                    drawRect(
                        colors.accent,
                        topLeft = Offset(0f, size.height - h),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                    )
                }
            }
            .padding(horizontal = 20.dp, vertical = if (strong) 12.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = reportText(14.sp, FontWeight.Medium, 20.sp), color = textColor)
        TabBadge(badge)
    }
}

private const val TAB_FADE_MS = 150

/** Button looks the web uses, by role. */
internal enum class ButtonKind { Accent, Secondary, Outline, Navy, Warning, Ghost, Danger, DangerOutline, Approve }

/** The web's `wa-btn` family: 32 px, radius 8, 12–14 px text. */
@Composable
internal fun ReportButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Accent,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 34.dp,
    fontSize: TextUnit = 13.sp,
    radius: Dp = 8.dp,
    horizontalPadding: Dp = 14.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val (source, hovered) = rememberHover()
    val (bg, fg, border) = buttonColors(kind, hovered && enabled)
    val animatedBg by animateColorAsState(bg, tween(BUTTON_FADE_MS))
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(radius))
            .background(animatedBg)
            .then(
                if (border != Color.Transparent) Modifier.border(
                    1.dp,
                    border,
                    RoundedCornerShape(radius),
                ) else Modifier,
            )
            .hoverable(source)
            .plainClick(enabled = enabled, source = source, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp)) }
        Text(
            text,
            style = reportText(fontSize, FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

@Composable
private fun buttonColors(kind: ButtonKind, hovered: Boolean): Triple<Color, Color, Color> {
    val c = ReportTheme.colors
    return when (kind) {
        ButtonKind.Accent -> Triple(if (hovered) c.accentHover else c.accent, Color.White, Color.Transparent)
        ButtonKind.Secondary -> Triple(if (hovered) c.border else c.sunken, c.textPrimary, Color.Transparent)
        ButtonKind.Outline -> Triple(
            if (hovered) c.hover else c.surface,
            c.textPrimary,
            if (hovered) c.textMuted else c.borderStrong,
        )
        ButtonKind.Navy -> Triple(if (hovered) c.navyHover else c.navy, Color.White, Color.Transparent)
        ButtonKind.Warning -> Triple(if (hovered) c.accentLight else Color.Transparent, c.accent, c.accent)
        ButtonKind.Ghost -> Triple(
            if (hovered) c.hover else Color.Transparent,
            if (hovered) c.textPrimary else c.textSecondary,
            Color.Transparent,
        )
        ButtonKind.Danger -> Triple(
            if (hovered) Color(0xFFB42318) else Color(0xFFD92D20),
            Color.White,
            Color.Transparent,
        )
        ButtonKind.DangerOutline -> Triple(if (hovered) c.redBg else Color.Transparent, c.red, c.redBorder)
        ButtonKind.Approve -> Triple(
            if (hovered) Color(0xFF055F3A) else Color(0xFF067647),
            Color.White,
            Color.Transparent,
        )
    }
}

private const val BUTTON_FADE_MS = 120
internal const val DISABLED_ALPHA = 0.5f

/** A 28 px square icon button — close X, remove, zoom. */
@Composable
internal fun ReportIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    iconSize: Dp = 14.dp,
    tint: Color = ReportTheme.colors.textMuted,
    hoverTint: Color = ReportTheme.colors.textSecondary,
    enabled: Boolean = true,
) {
    val (source, hovered) = rememberHover()
    com.zillit.desktop.core.designsystem.component.ZillitTooltip(description) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered && enabled) ReportTheme.colors.sunken else Color.Transparent)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .hoverable(source)
                .plainClick(enabled = enabled, source = source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (hovered) hoverTint else tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** The report switch: 36 × 20, orange when on. */
@Composable
internal fun ReportSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReportTheme.colors
    val track by animateColorAsState(if (checked) colors.accent else colors.borderStrong, tween(BUTTON_FADE_MS))
    val knob by animateDpAsState(if (checked) 18.dp else 2.dp, tween(BUTTON_FADE_MS))
    Box(
        modifier = modifier
            .width(36.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(track)
            .plainClick { onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** A small-caps eyebrow label. */
@Composable
internal fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ReportTheme.colors.textMuted,
    strong: Boolean = false,
) {
    Text(
        text = text.uppercase(),
        style = reportText(if (strong) 10.sp else 10.sp, if (strong) FontWeight.SemiBold else FontWeight.Medium, 14.sp)
            .copy(letterSpacing = if (strong) 1.2.sp else 0.5.sp),
        color = color,
        modifier = modifier,
    )
}

/** A two-option segmented control — Table | Cards, Horizontal | Vertical. */
@Composable
internal fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<T, ImageVector> = emptyMap(),
    solidSelection: Boolean = true,
) {
    val colors = ReportTheme.colors
    Row(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(colors.segmentTrack).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val (source, hovered) = rememberHover()
            val bg = when {
                active && solidSelection -> colors.accent
                active -> colors.surface
                hovered && solidSelection -> colors.accent.copy(alpha = 0.67f)
                else -> Color.Transparent
            }
            val fg = when {
                active && solidSelection -> Color.White
                active -> colors.accent
                hovered -> colors.textPrimary
                else -> colors.textTertiary
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .hoverable(source)
                    .plainClick(source = source) { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons[value]?.let { Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp)) }
                Text(label, style = reportText(13.sp, FontWeight.Medium), color = fg)
            }
        }
    }
}
