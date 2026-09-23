package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.LinkQuality

/**
 * Three bars, the way every phone draws signal.
 *
 * Borrowed on purpose: link quality is one of the few things in a call UI that
 * already has a universally understood picture, and inventing a new one buys
 * nothing. The word beside it appears only when there is trouble — a label
 * that reads "Excellent" all call is furniture, but silence when the line is
 * failing is the thing users complain about afterwards.
 */
@Composable
fun NetworkPip(quality: LinkQuality, modifier: Modifier = Modifier, showLabel: Boolean = false) {
    val colors = ZillitTheme.colors
    val filled = quality.bars
    val fill = quality.fill(colors)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Canvas(modifier = Modifier.size(PIP_WIDTH, PIP_HEIGHT)) {
            val barWidth = BAR_WIDTH.toPx()
            val gap = BAR_GAP.toPx()
            val heights = listOf(BAR_ONE.toPx(), BAR_TWO.toPx(), BAR_THREE.toPx())
            heights.forEachIndexed { index, height ->
                drawRect(
                    color = if (index < filled) fill else colors.border,
                    topLeft = Offset(index * (barWidth + gap), size.height - height),
                    size = Size(barWidth, height),
                )
            }
            if (quality == LinkQuality.Down) {
                // A struck-through meter reads as "off", where zero bars alone
                // reads as "not measured yet".
                drawLine(
                    color = colors.danger,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                    strokeWidth = STRIKE.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        if (showLabel && quality.isTrouble) {
            ZillitText(
                text = quality.word,
                style = ZillitTheme.typography.labelSmall,
                color = if (quality == LinkQuality.Poor) colors.warning else colors.danger,
            )
        }
    }
}

/**
 * How many bars are lit.
 *
 * Unknown draws full but muted: before the first reading there is no news, and
 * an empty meter would read as a dead line.
 */
private val LinkQuality.bars: Int
    get() = when (this) {
        LinkQuality.Excellent, LinkQuality.Good, LinkQuality.Unknown -> BARS
        LinkQuality.Poor -> TWO_BARS
        LinkQuality.Bad -> ONE_BAR
        LinkQuality.Down -> NO_BARS
    }

private fun LinkQuality.fill(colors: com.zillit.desktop.core.designsystem.ZillitColors) =
    when (this) {
        LinkQuality.Excellent, LinkQuality.Good -> colors.success
        LinkQuality.Poor -> colors.warning
        LinkQuality.Bad, LinkQuality.Down -> colors.danger
        LinkQuality.Unknown -> colors.border
    }

private val LinkQuality.word: String
    get() = when (this) {
        LinkQuality.Poor -> str(S.desktop_link_weak)
        LinkQuality.Bad -> str(S.desktop_link_poor)
        LinkQuality.Down -> str(S.offline)
        else -> ""
    }

/** Purely so a caller can reserve the same width without drawing. */
val PIP_WIDTH = 12.dp
private val PIP_HEIGHT = 10.dp
private val BAR_WIDTH = 2.5.dp
private val BAR_GAP = 2.dp
private val BAR_ONE = 4.dp
private val BAR_TWO = 7.dp
private val BAR_THREE = 10.dp
private val STRIKE = 1.5.dp
private const val BARS = 3
private const val TWO_BARS = 2
private const val ONE_BAR = 1
private const val NO_BARS = 0
