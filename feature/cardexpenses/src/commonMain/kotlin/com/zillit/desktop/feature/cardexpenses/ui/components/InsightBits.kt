package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitText

/**
 * Small pieces the Top-Up, Analytics, Smart Alerts and Fund Requests pages
 * share: the web's uppercase eyebrow labels, its figure blocks, its tinted
 * tags and its filter chips.
 */

/** An uppercase caption over a figure — the web's `text-[10px] uppercase tracking-wider`. */
@Composable
fun InsightEyebrow(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = color ?: ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A caption and its figure, stacked. */
@Composable
fun InsightFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    style: TextStyle? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        InsightEyebrow(label)
        ZillitText(
            text = value,
            style = (style ?: ZillitTheme.typography.numeric).copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
        )
    }
}

/** A tinted tag: soft background, strong text, an optional dot. */
@Composable
fun InsightTag(text: String, background: Color, content: Color, modifier: Modifier = Modifier, dot: Boolean = false) {
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (dot) Box(Modifier.size(DOT).clip(CircleShape).background(content))
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = content,
            maxLines = 1,
        )
    }
}

/** A thin filled bar; [fraction] is clamped. */
@Composable
fun InsightBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = BAR,
) {
    val filled = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(ZillitTheme.shapes.pill)
            .background(ZillitTheme.colors.surfaceHover),
    ) {
        if (filled > 0f) {
            Box(
                Modifier.fillMaxWidth(filled).height(height).clip(ZillitTheme.shapes.pill).background(color),
            )
        }
    }
}

/** The web's `QuickFilters`: one chip per option, no counts. */
@Composable
fun <T> InsightChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        options.forEach { option ->
            ZillitChoiceChip(label = label(option), selected = option == selected, onClick = { onSelect(option) })
        }
    }
}

/** A bordered, rounded block — the web's inset cards. */
fun Modifier.insightCard(background: Color, border: Color): Modifier =
    this.clip(CARD_SHAPE).background(background).border(1.dp, border, CARD_SHAPE)

private val CARD_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
private val DOT = 6.dp
private val BAR = 4.dp
