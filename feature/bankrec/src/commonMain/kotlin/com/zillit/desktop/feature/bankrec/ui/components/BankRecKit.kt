package com.zillit.desktop.feature.bankrec.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText

/** Monospaced figures — every amount, rate and reference, as on the web. */
@Composable
internal fun mono(size: TextUnit = 13.sp, weight: FontWeight = FontWeight.Normal): TextStyle =
    ZillitTheme.typography.numeric.copy(fontSize = size, lineHeight = (size.value + 5).sp, fontWeight = weight)

/** The small uppercase label the web puts above a figure or a field. */
@Composable
internal fun eyebrow(size: TextUnit = 10.5.sp): TextStyle = ZillitTheme.typography.labelSmall.copy(
    fontSize = size,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.06.em,
)

/** A section title — the web sets these in Syne, bold. */
@Composable
internal fun titleStyle(size: TextUnit = 14.sp): TextStyle =
    ZillitTheme.typography.titleSmall.copy(fontSize = size, fontWeight = FontWeight.Bold)

// -- badges -----------------------------------------------------------------

/** A status badge: tinted, square-cornered, bold figures — the web's `StatusBadge`. */
@Composable
internal fun BrBadge(label: String, tone: BrTone, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(tone.bg())
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        ZillitText(
            text = label,
            style = mono(11.sp, FontWeight.Bold),
            color = tone.fg(),
            maxLines = 1,
        )
    }
}

/** A lighter chip, for a category rather than a status — the web's `Tag`. */
@Composable
internal fun BrTag(label: String, tone: BrTone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tone.bg())
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        icon?.let { ZillitIcon(it, tint = tone.fg(), size = 10.dp) }
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.03.em),
            color = tone.fg(),
            maxLines = 1,
        )
    }
}

// -- cards --------------------------------------------------------------------

/**
 * The web's `Card`: a white panel, hairline-edged, with an optional header of
 * icon, title and a trailing slot. [padded] false leaves the body flush, for a
 * table.
 */
@Composable
internal fun BrCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    padded: Boolean = false,
    edge: Color? = null,
    titleRight: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, edge ?: colors.border, ZillitTheme.shapes.large),
    ) {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon?.let { ZillitIcon(it, tint = colors.gold, size = 16.dp) }
                ZillitText(text = title, style = titleStyle(13.5.sp), maxLines = 1, modifier = Modifier.weight(1f))
                titleRight?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = it,
                    )
                }
            }
            ZillitDivider()
        }
        Column(
            modifier = Modifier.fillMaxWidth().then(if (padded) Modifier.padding(16.dp) else Modifier),
            content = content,
        )
    }
}

/**
 * One headline figure — the web's `StatCard`: white, a small uppercase label,
 * the value large and coloured, a qualifier under it. The value steps down a
 * size as it lengthens rather than ellipsing, because "£218,000.…" is not a
 * number.
 */
@Composable
internal fun BrStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val size = when {
        value.length > LONG_VALUE -> 16.sp
        value.length > MEDIUM_VALUE -> 18.sp
        value.length > SHORT_VALUE -> 20.sp
        else -> 23.sp
    }
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitText(
            text = label.uppercase(),
            style = mono(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.06.em),
            color = colors.textMuted,
            maxLines = 1,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleLarge.copy(
                fontSize = size,
                lineHeight = 28.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
            color = valueColor ?: colors.textPrimary,
            maxLines = 1,
        )
        sub?.let {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2)
        }
    }
}

/**
 * Tiles sharing the width equally, wrapping to as many columns as fit.
 *
 * [columns] is a maximum. A row of five at the width the Account Hub leaves a
 * tool clips every label, so the grid drops a column at a time below
 * [minTile] — and pads a short last row so its tiles stay the width of the
 * ones above.
 */
@Composable
internal fun BrTileGrid(
    columns: Int,
    tiles: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
    minTile: Dp = 176.dp,
    gap: Dp = 14.dp,
) {
    if (tiles.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fits = ((maxWidth + gap) / (minTile + gap)).toInt().coerceAtLeast(1)
        val across = columns.coerceIn(1, fits)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            tiles.chunked(across).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A tinted notice with an icon, a bold line and a quieter one — the web's
 * red, amber and teal banners.
 */
@Composable
internal fun BrBanner(
    tone: BrTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    message: String? = null,
    /** False sizes the banner to its words — a callout in a row, not a strip across the page. */
    fill: Boolean = true,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(tone.bg())
            .border(1.dp, tone.edge(), ZillitTheme.shapes.large)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.let { ZillitIcon(it, tint = tone.fg(), size = 18.dp) }
        Column(if (fill) Modifier.weight(1f) else Modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            title?.let {
                ZillitText(
                    it,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = tone.fg(),
                )
            }
            message?.let {
                ZillitText(it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
            }
        }
        action?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
    }
}

/** A centred empty state: a glyph on a soft tile, a title, a sentence. */
@Composable
internal fun BrEmpty(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    tone: BrTone = BrTone.Gray,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(tone.bg()),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    it,
                    tint = if (tone == BrTone.Gray) ZillitTheme.colors.textMuted else tone.fg(),
                    size = 24.dp,
                )
            }
        }
        ZillitText(title, style = titleStyle(15.sp), textAlign = TextAlign.Center)
        message?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(420.dp),
            )
        }
        action?.let {
            Spacer(Modifier.height(4.dp))
            it()
        }
    }
}

// -- figures and bars ---------------------------------------------------------

/**
 * A bar of segments — matched, suggested, unmatched, fraud — each as wide as
 * its share. [gap] separates them into pills, the portal's style; zero runs
 * them together, the overview's.
 */
@Composable
internal fun BrSegmentBar(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    gap: Dp = 0.dp,
) {
    if (gap > 0.dp) {
        // Separate pills: a zero share is left out, or its gap would still show.
        val shown = segments.filter { it.first > 0f }
        val total = shown.sumOf { it.first.toDouble() }.toFloat()
        Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
            shown.forEach { (share, color) ->
                Box(Modifier.weight(share).fillMaxHeight().clip(CircleShape).background(color))
            }
            val rest = 1f - total
            if (rest > MIN_WEIGHT) Spacer(Modifier.weight(rest))
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(ZillitTheme.colors.surfaceHover),
    ) {
        // Every segment keeps its slot, so each animates from its own last
        // width when the counts move rather than jumping to a neighbour's.
        val shares = segments.map { (share, _) -> animateFloatAsState(share, animationSpec = tween(BAR_MILLIS)).value }
        segments.forEachIndexed { index, (_, color) ->
            Box(Modifier.weight(shares[index].coerceAtLeast(MIN_WEIGHT)).fillMaxHeight().background(color))
        }
        val rest = 1f - shares.sum()
        if (rest > MIN_WEIGHT) Spacer(Modifier.weight(rest))
    }
}

/** A small filled bar with its percentage beside it — a history row's progress. */
@Composable
internal fun BrMiniProgress(percent: Int, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.width(56.dp).height(6.dp).clip(CircleShape).background(colors.surfaceHover)) {
            Box(
                Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).fillMaxHeight().clip(CircleShape)
                    .background(if (percent >= 100) colors.success else colors.warning),
            )
        }
        ZillitText("$percent%", style = mono(11.sp), color = colors.textMuted)
    }
}

/** A legend entry: a dot, a label, a count. */
@Composable
internal fun BrLegendDot(
    color: Color,
    label: String,
    count: Int?,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BrDot(color, pulse = pulse)
        ZillitText(label, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
        count?.let {
            ZillitText(it.toString(), style = mono(12.sp, FontWeight.Medium), color = ZillitTheme.colors.textPrimary)
        }
    }
}

/** A dot, optionally breathing — the web pulses what needs attention. */
@Composable
internal fun BrDot(color: Color, size: Dp = 8.dp, pulse: Boolean = false) {
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition()
        transition.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_LOW,
            animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        ).value
    } else {
        1f
    }
    Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

/** A glyph on a small tinted tile — an exception's type, a bank's initial. */
@Composable
internal fun BrIconTile(icon: ImageVector, tone: BrTone, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)).background(tone.bg()),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, tint = tone.fg(), size = size * ICON_SHARE)
    }
}

/** A bank's first letter on the web's blue square. */
@Composable
internal fun BrInitialTile(name: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(6.dp)).background(colors.info),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = name.trim().take(1).uppercase().ifBlank { "B" },
            style = titleStyle((size.value * INITIAL_SHARE).sp),
            color = colors.textOnAccent,
        )
    }
}

/**
 * A bank as a read-only block: its name, then `Sort:` and `Acc:` on their
 * own lines — the web's `BankIdentity`, which every Account Hub module shares.
 */
@Composable
internal fun BrBankIdentity(
    name: String,
    modifier: Modifier = Modifier,
    sortCode: String = "",
    accountNumber: String = "",
    compact: Boolean = false,
) {
    val colors = ZillitTheme.colors
    if (name.isBlank() && sortCode.isBlank() && accountNumber.isBlank()) {
        ZillitText("—", style = ZillitTheme.typography.bodySmall, color = colors.textMuted, modifier = modifier)
        return
    }
    Column(modifier) {
        if (name.isNotBlank()) {
            ZillitText(
                name,
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontWeight = if (compact) FontWeight.Normal else FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
        val sort = com.zillit.desktop.feature.bankrec.domain.BankRecFormat.sortCode(sortCode)
        if (sort.isNotBlank()) ZillitText("Sort: $sort", style = mono(11.5.sp), color = colors.textMuted, maxLines = 1)
        if (accountNumber.isNotBlank()) {
            ZillitText("Acc: $accountNumber", style = mono(11.5.sp), color = colors.textMuted, maxLines = 1)
        }
    }
}

/** A label above a field, in the web's small caps. */
@Composable
internal fun BrFieldLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text.uppercase(), style = eyebrow(), color = ZillitTheme.colors.textSecondary)
        trailing?.let {
            Spacer(Modifier.width(4.dp))
            ZillitText(it, style = eyebrow(), color = ZillitTheme.colors.danger)
        }
    }
}

/** A labelled figure — the portal's grids and the detail dialogs. */
@Composable
internal fun BrFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueStyle: TextStyle? = null,
    valueColor: Color? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(label.uppercase(), style = eyebrow(10.sp), color = ZillitTheme.colors.textMuted, maxLines = 1)
        ZillitText(
            value,
            style = valueStyle ?: ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor ?: ZillitTheme.colors.textPrimary,
            maxLines = 2,
        )
        sub?.let {
            ZillitText(it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted, maxLines = 2)
        }
    }
}

/** A text button in the web's mono, for the actions on a suggestion bar. */
@Composable
internal fun BrLinkButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shown by animateColorAsState(if (hovered) ZillitTheme.colors.textPrimary else color)
    ZillitText(
        text = text,
        style = mono(11.sp, FontWeight.SemiBold),
        color = shown,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * A tinted action — the web's purple "Investigate" and teal "View FX": a
 * button that says what kind of thing it opens before it is read.
 */
@Composable
internal fun BrToneButton(
    text: String,
    tone: BrTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered && enabled) tone.fg().copy(alpha = HOVER_FILL) else tone.bg())
    val shape = ZillitTheme.shapes.medium
    Row(
        modifier = modifier
            .height(ZillitDimens.controlHeightSmall)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(fill)
            .border(1.dp, tone.edge(), shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon?.let { ZillitIcon(it, tint = tone.fg(), size = 13.dp) }
        ZillitText(text, style = ZillitTheme.typography.button.copy(fontSize = 12.sp), color = tone.fg(), maxLines = 1)
    }
}

/** Grey placeholder rows in the shape of what is coming — never a spinner that flickers. */
@Composable
internal fun BrSkeletonRows(count: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        repeat(count) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitSkeletonBar(Modifier.width(80.dp))
                ZillitSkeletonBar(Modifier.weight(1f))
                ZillitSkeletonBar(Modifier.width(64.dp))
                ZillitSkeletonBar(Modifier.width(96.dp))
            }
            ZillitDivider()
        }
    }
}

private const val SHORT_VALUE = 8
private const val MEDIUM_VALUE = 11
private const val LONG_VALUE = 14
private const val BAR_MILLIS = 450
private const val MIN_WEIGHT = 0.0001f
private const val PULSE_LOW = 0.35f
private const val PULSE_MILLIS = 900
private const val ICON_SHARE = 0.46f
private const val INITIAL_SHARE = 0.42f
private const val HOVER_FILL = 0.2f
private const val DISABLED_ALPHA = 0.5f
