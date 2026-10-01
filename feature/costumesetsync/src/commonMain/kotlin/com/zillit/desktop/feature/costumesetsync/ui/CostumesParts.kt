package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.Tone

/*
 * Pieces drawn to the web's SyncOnset.css numbers (px to dp 1:1) that more than one screen needs: the
 * status badge, the readiness dot with its pulse, the ink button, the tinted notice, the 130dp-label
 * key/value list and the blue link text. Colours come from the theme's own tokens, never a literal.
 */

/** A tone's text colour, tint and drawn edge — the web's `.csync-badge--*` rules. */
internal class ToneColors(val fg: Color, val bg: Color, val edge: Color)

@Composable
internal fun toneColors(tone: Tone): ToneColors {
    val c = ZillitTheme.colors
    return when (tone) {
        Tone.Ok -> ToneColors(c.success, c.successSoft, Color.Transparent)
        Tone.Info -> ToneColors(c.info, c.infoSoft, Color.Transparent)
        Tone.Warn -> ToneColors(c.warning, c.warningSoft, Color.Transparent)
        Tone.Danger -> ToneColors(c.danger, c.dangerSoft, Color.Transparent)
        Tone.Accent -> ToneColors(c.warning, c.warningSoft, c.warning.copy(alpha = ACCENT_EDGE_ALPHA))
        Tone.Muted -> ToneColors(c.textSecondary, c.surfaceHover, c.border)
    }
}

private const val ACCENT_EDGE_ALPHA = 0.3f

/** The web's `.csync-badge`: a 12sp semibold pill on a soft tint (`large`: 12sp, 3/10 padding), no marker dot. */
@Composable
internal fun TonePill(label: String, tone: Tone, modifier: Modifier = Modifier, large: Boolean = false) {
    val colors = toneColors(tone)
    Box(
        modifier
            .clip(CircleShape)
            .background(colors.bg)
            .then(if (colors.edge != Color.Transparent) Modifier.border(1.dp, colors.edge, CircleShape) else Modifier)
            .padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 3.dp else 2.dp),
    ) {
        ZillitText(
            label,
            style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            color = colors.fg,
            maxLines = 1,
        )
    }
}

/** The web's `.csync-dot` (10dp); [pulse] adds the red ring that swells out and fades. */
@Composable
internal fun ToneDot(tone: Tone, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val colors = ZillitTheme.colors
    val fill = when (tone) {
        Tone.Ok -> colors.success
        Tone.Warn -> colors.warning
        Tone.Danger -> colors.danger
        Tone.Info -> colors.info
        Tone.Accent -> colors.accent
        Tone.Muted -> colors.textMuted
    }
    val ring = colors.danger
    val spread by if (pulse) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Restart),
            label = "pulseSpread",
        )
    } else {
        remember { androidx.compose.runtime.mutableStateOf(0f) }
    }
    Box(
        modifier
            .size(10.dp)
            .then(
                if (pulse) {
                    Modifier.drawBehind {
                        drawCircle(ring.copy(alpha = PULSE_ALPHA * (1f - spread)), radius = size.minDimension / 2 + 8.dp.toPx() * spread)
                    }
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(fill),
    )
}

private const val PULSE_MS = 1400
private const val PULSE_ALPHA = 0.6f

/**
 * The web's `.csync-btn-ink`: a button filled with the ink colour (dark on light, light on dark) for the primary
 * action of a row of actions. Sized as the app's own buttons are.
 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val small = size == ButtonSize.Small
    Row(
        modifier
            .defaultMinSize(minHeight = if (small) ZillitDimens.controlHeightSmall else ZillitDimens.controlHeight)
            .clip(ZillitTheme.shapes.medium)
            .background(if (enabled) colors.textPrimary.copy(alpha = if (hovered) INK_HOVER_ALPHA else 1f) else colors.surfaceHover)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) ZillitTheme.spacing.sm else INK_PADDING),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) colors.surface else colors.textDisabled
        if (icon != null) ZillitIcon(icon, tint = ink, size = if (small) ZillitDimens.iconSmall else ZillitDimens.icon)
        ZillitText(text, style = ZillitTheme.typography.button, color = ink, maxLines = 1)
    }
}

private const val INK_HOVER_ALPHA = 0.88f
private val INK_PADDING = 12.dp

/** How a [Notice] is tinted: the web's `.csync-notice` and its `--info`, `--ok`, `--warn`, `--danger` modifiers. */
internal enum class NoticeTone { Plain, Info, Ok, Warn, Danger }

/** The web's `.csync-notice`: a 10/14 padded, 10dp rounded box with a thin edge, 14sp text. */
@Composable
internal fun Notice(modifier: Modifier = Modifier, tone: NoticeTone = NoticeTone.Plain, content: @Composable ColumnScope.() -> Unit) {
    val c = ZillitTheme.colors
    val (bg, edge) = when (tone) {
        NoticeTone.Plain -> c.surfaceHover to Color.Transparent
        NoticeTone.Info -> c.infoSoft to c.info.copy(alpha = NOTICE_EDGE_ALPHA)
        NoticeTone.Ok -> c.successSoft to c.success.copy(alpha = NOTICE_EDGE_ALPHA)
        NoticeTone.Warn -> c.warningSoft to c.warning.copy(alpha = NOTICE_EDGE_ALPHA)
        NoticeTone.Danger -> c.dangerSoft to c.danger
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, edge, shape).padding(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

private const val NOTICE_EDGE_ALPHA = 0.25f

/** Blue link text (`.csync-linkbtn`): inherits the size, underlines on hover. */
@Composable
internal fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, bold: Boolean = false, style: TextStyle? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = style ?: ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp)
    ZillitText(
        text,
        modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        style = base.copy(fontWeight = if (bold) FontWeight.SemiBold else base.fontWeight, textDecoration = if (hovered) TextDecoration.Underline else null),
        color = ZillitTheme.colors.info,
        maxLines = 2,
    )
}

/** The web's `dl.csync-kv`: 130dp muted labels beside 14sp values, 6dp between rows. */
@Composable
internal fun KvList(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, labelWidth: Dp = 130.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ZillitText(label, Modifier.width(labelWidth), style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp), color = ZillitTheme.colors.textMuted)
                ZillitText(value, Modifier.weight(1f), style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp), maxLines = 4)
            }
        }
    }
}

/** The web's `.csync-avatar`: a 38dp rounded (10dp) square, never a circle. */
@Composable
internal fun GlyphAvatar(glyph: String, modifier: Modifier = Modifier) {
    val c = ZillitTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(38.dp).clip(shape).background(c.surfaceHover).border(1.dp, c.border, shape), contentAlignment = Alignment.Center) {
        ZillitText(glyph, style = ZillitTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = c.textMuted, maxLines = 1)
    }
}

internal val MONO_FAMILY: FontFamily = FontFamily.Monospace

/** The width the list card may take before it scrolls inside itself: the web's `max-height: 62vh`. */
internal const val LIST_MAX_VIEWPORT = 0.62f

/** A page's column: the web's `.csync-page` (16dp between its blocks). */
@Composable
internal fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

/**
 * The web's "Select, then a box under it to type anything else" inside ONE labelled field (a costume's Type and
 * Location, a movement's To location): the label once, the pick list, then the text box with 10dp between them.
 */
@Composable
fun StackedPick(
    value: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    text: String,
    onText: (String) -> Unit,
    label: String,
    modifier: Modifier = FormCell,
    placeholder: String = "",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PickInput(value, options, onPick, label, Modifier.fillMaxWidth(), placeholder)
        com.zillit.desktop.core.designsystem.component.ZillitTextField(text, onText, Modifier.fillMaxWidth())
    }
}

/**
 * The web's `display: grid; grid-template-columns: repeat(auto-fill, minmax(min, 1fr)); gap`: as many columns as fit,
 * each stretched to share the row, every cell as tall as the tallest in its row. [item] draws cell `i` with the
 * width and height modifier it must wear.
 */
@Composable
internal fun AutoFillGrid(
    count: Int,
    min: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    rowGap: Dp = gap,
    stretch: Boolean = true,
    item: @Composable (index: Int, cell: Modifier) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + gap) / (min + gap)).toInt())
        val cell = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
            (0 until count).chunked(columns).forEach { rowItems ->
                Row(if (stretch) Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min) else Modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { i -> item(i, if (stretch) Modifier.width(cell).fillMaxHeight() else Modifier.width(cell)) }
                }
            }
        }
    }
}

