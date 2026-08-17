package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * A single headline figure — "£48,200 outstanding", "12 awaiting approval".
 *
 * The tile is deliberately tinted by [tone] rather than left white: a row of
 * six identical tiles is a row nobody reads, and the tint is what lets an
 * accountant find the one number they opened the tool for. The web does the
 * same (`AdminStatTile` mixes 5% of the tone into the surface); this uses the
 * theme's soft status colours so it works in dark mode, which the web's
 * `color-mix` against `#fff` does not.
 */
@Composable
fun ZillitStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    tone: StatusTone? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val accent: Color = tone?.content() ?: colors.textPrimary
    val surface = tone?.background() ?: colors.surface

    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(surface)
            .border(CARD_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = label.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            icon?.let {
                Spacer(Modifier.weight(1f))
                ZillitIcon(it, tint = accent, size = ZillitDimens.iconSmall)
            }
        }
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(
                fontSize = STAT_VALUE_SIZE,
                lineHeight = STAT_VALUE_LINE,
                fontWeight = FontWeight.Bold,
            ),
            color = accent,
            maxLines = 1,
        )
        sub?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * A horizontal fill bar — float consumed, card limit used, budget burn.
 *
 * Clamped rather than allowed to overflow: an overspent float reads as a full
 * bar with the overspend named beside it, which is honest. A bar drawn past
 * its track just looks like a rendering bug.
 */
@Composable
fun ZillitMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Progress,
) {
    val colors = ZillitTheme.colors
    val filled = fraction.coerceIn(0f, 1f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.pill)
            .background(colors.surfaceHover),
    ) {
        if (filled > 0f) {
            Spacer(
                modifier = Modifier
                    .weight(filled)
                    .height(METER_HEIGHT)
                    .clip(ZillitTheme.shapes.pill)
                    .background(tone.content()),
            )
        }
        if (filled < 1f) Spacer(Modifier.weight(1f - filled).height(METER_HEIGHT))
    }
}

private val STAT_VALUE_SIZE = 24.sp
private val STAT_VALUE_LINE = 30.sp
private val METER_HEIGHT = 7.dp
