@file:Suppress("TooManyFunctions") // The diary's small shared pieces, one composable each.

package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

// Colour -------------------------------------------------------------------

/** `#rrggbb` to a Compose colour; null for anything else. */
internal fun hexColor(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.length != HEX_DIGITS) return null
    val value = digits.toLongOrNull(HEX_RADIX) ?: return null
    return Color(OPAQUE or value)
}

/** A schedule type's or an entry's colour, the accent when it has none. */
@Composable
internal fun swatchColor(hex: String?): Color = hexColor(hex) ?: ZillitTheme.colors.accent

/** The web's `${color}14`-style tints: the colour at a fraction of its strength. */
internal fun Color.tint(alpha: Float): Color = copy(alpha = alpha)

internal val EVENT_BLUE = Color(0xFF3498DB)

// Type ---------------------------------------------------------------------

/** The masthead and titles' Georgia. */
@Composable
internal fun serif(size: TextUnit, weight: FontWeight = FontWeight.Bold, spacing: TextUnit = 0.sp): TextStyle =
    ZillitTheme.typography.titleMedium.copy(
        fontFamily = FontFamily.Serif,
        fontSize = size,
        lineHeight = size * LINE_HEIGHT,
        fontWeight = weight,
        letterSpacing = spacing,
    )

/** A small upper-case caption — the web's `letterSpacing: 1.2px, textTransform: uppercase`. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        color = color ?: ZillitTheme.colors.textMuted,
        modifier = modifier,
    )
}

/** A form field's label, with the red star the web puts on a required one. */
@Composable
internal fun FieldLabel(text: String, required: Boolean = false, note: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
            color = ZillitTheme.colors.textSecondary,
        )
        if (required) ZillitText("*", style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger)
        note?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall.copy(fontStyle = FontStyle.Normal),
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** A field's error, under it. */
@Composable
internal fun FieldError(text: String?) {
    if (text == null) return
    ZillitText(text = text, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger)
}

// Shapes -------------------------------------------------------------------

/** A small filled dot — a type or an event colour. */
@Composable
internal fun Dot(color: Color, size: Dp = 8.dp, square: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(if (square) RoundedCornerShape(2.dp) else CircleShape)
            .background(color),
    )
}

/** A schedule type's name on its own tint — the list's TYPE cell and the drawer's header chips. */
@Composable
internal fun TypeChip(name: String, hex: String, modifier: Modifier = Modifier, upper: Boolean = false) {
    val color = swatchColor(hex)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.tint(TINT))
            .border(1.dp, color.tint(TINT_BORDER), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color, size = 8.dp, square = true)
        ZillitText(
            text = if (upper) name.uppercase() else name,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** A count on a small pill, beside a section's heading. */
@Composable
internal fun CountPill(count: Int) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** A quiet bordered button in a row's corner — the web's Edit / Remove / View. */
@Composable
internal fun RowAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = ZillitTheme.colors
    val tint = when {
        danger && hovered -> colors.danger
        hovered -> colors.textPrimary
        else -> colors.textMuted
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(
                1.dp,
                if (hovered && danger) colors.danger.tint(HOVER_BORDER) else colors.border,
                RoundedCornerShape(5.dp),
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIcon(icon = icon, tint = tint, size = 11.dp)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = tint,
        )
    }
}

/**
 * A card-shaped radio — the scope prompts, the conflict choices and the
 * one-day edit's Replace / Extend / Overlap.
 */
@Composable
internal fun OptionCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    accent: Color = ZillitTheme.colors.accent,
    badge: String? = null,
    extra: String? = null,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.tint(SELECTED_BG) else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else colors.border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioMark(selected, accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(title, style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
                badge?.let {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent.tint(BADGE_BG))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        ZillitText(it, style = ZillitTheme.typography.labelSmall, color = accent)
                    }
                }
            }
            ZillitText(description, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            extra?.let {
                ZillitText(
                    it,
                    style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** The ring of a radio button, filled when chosen. */
@Composable
internal fun RadioMark(selected: Boolean, accent: Color = ZillitTheme.colors.accent) {
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(16.dp)
            .clip(CircleShape)
            .border(
                if (selected) 5.dp else 2.dp,
                if (selected) accent else ZillitTheme.colors.borderStrong,
                CircleShape,
            ),
    )
}

/** A choice chip in the web's pill style — the Filter dialog's Show and Schedule Type rows. */
@Composable
internal fun PillChoice(label: String, selected: Boolean, onClick: () -> Unit, dot: Color? = null) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.textPrimary else colors.surface)
            .border(1.dp, if (selected) colors.textPrimary else colors.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        dot?.let { Dot(it) }
        ZillitText(
            label,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) colors.surface else colors.textSecondary,
        )
    }
}

/** A segmented pair or triple — Calendar/List, Month/Week/Day, By Date/By Schedule. */
@Composable
internal fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (active) Modifier.shadow(1.dp, RoundedCornerShape(6.dp)) else Modifier)
                    .background(if (active) colors.surface else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = label(option),
                    style = ZillitTheme.typography.label.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (active) colors.textPrimary else colors.textMuted,
                )
            }
        }
    }
}

/** A dashed "+N more" chip. */
@Composable
internal fun MoreChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .border(1.dp, if (hovered) colors.textPrimary else colors.borderStrong, RoundedCornerShape(999.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ZillitText(
            text,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (hovered) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
    }
}

// Side sheet ---------------------------------------------------------------

/**
 * A drawer from the right — the web's antd `Drawer`: a header with the title
 * and a close, a scrolling body, and an optional pinned footer. It slides in
 * over a scrim that closes it.
 */
@Composable
internal fun DiarySheet(
    title: String,
    onDismiss: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(SHEET_MS)), exit = fadeOut(tween(SHEET_MS))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM_ALPHA))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInHorizontally(tween(SHEET_MS)) { it / SLIDE_FRACTION } + fadeIn(tween(SHEET_MS)),
            exit = slideOutHorizontally(tween(SHEET_MS)) { it / SLIDE_FRACTION } + fadeOut(tween(SHEET_MS)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            BoxWithConstraints(Modifier.fillMaxHeight()) {
                SheetCard(
                    title = title,
                    onDismiss = onDismiss,
                    width = if (maxWidth < width + SHEET_GUTTER) maxWidth - SHEET_GUTTER / 2 else width,
                    subtitle = subtitle,
                    headerTrailing = headerTrailing,
                    footer = footer,
                    padded = padded,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SheetCard(
    title: String,
    onDismiss: () -> Unit,
    width: Dp,
    subtitle: (@Composable () -> Unit)?,
    headerTrailing: (@Composable RowScope.() -> Unit)?,
    footer: (@Composable RowScope.() -> Unit)?,
    padded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .width(width.coerceAtLeast(MIN_SHEET))
            .fillMaxHeight()
            .shadow(SHEET_ELEVATION)
            .background(colors.canvas)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(
                    text = title,
                    style = ZillitTheme.typography.titleMedium.copy(letterSpacing = 1.sp),
                    color = colors.textPrimary,
                )
                subtitle?.invoke()
            }
            headerTrailing?.invoke(this)
            ZillitIconButton(icon = ZillitIcons.Close, contentDescription = "Close", onClick = onDismiss)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zillitVerticalScroll(scroll)
                    .then(if (padded) Modifier.padding(horizontal = 20.dp, vertical = 16.dp) else Modifier)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(if (padded) 14.dp else 0.dp),
                content = content,
            )
            ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
        }
        footer?.let { actions ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(
                Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** The bounded width a centred dialog's body keeps. */
internal fun Modifier.dialogWidth(max: Dp): Modifier = widthIn(max = max).fillMaxWidth()

/** Hairlines along a cell's right and bottom edges — a grid without doubled borders. */
internal fun Modifier.gridLines(color: Color, right: Boolean, bottom: Boolean = true): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    if (right) {
        drawLine(color, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
    }
    if (bottom) {
        drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
    }
}

private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L
private const val LINE_HEIGHT = 1.3f
private const val TINT = 0.08f
private const val TINT_BORDER = 0.19f
private const val HOVER_BORDER = 0.4f
private const val SELECTED_BG = 0.06f
private const val BADGE_BG = 0.14f
private const val SCRIM_ALPHA = 0.32f
private const val SHEET_MS = 180
private const val SLIDE_FRACTION = 3
private val SHEET_GUTTER = 48.dp
private val MIN_SHEET = 320.dp
private val SHEET_ELEVATION = 24.dp
