package com.zillit.desktop.feature.callsheet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * The call sheet's checkbox — the web's native one with `accent-color:
 * #fc9404`: an orange fill and a white tick when on. Drawn without its own
 * click so a whole row can own the press, as the web's `<label>` rows do.
 */
@Composable
internal fun SheetCheckbox(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 18.dp) {
    val colors = SheetTheme.colors
    val fill by animateColorAsState(if (checked) colors.accent else Color.Transparent, tween(FADE_MS))
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .border(1.5.dp, if (checked) colors.accent else colors.borderStrong, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(Modifier.size(size * TICK_SCALE)) {
                val tick = Path().apply {
                    moveTo(this@Canvas.size.width * 0.12f, this@Canvas.size.height * 0.52f)
                    lineTo(this@Canvas.size.width * 0.4f, this@Canvas.size.height * 0.8f)
                    lineTo(this@Canvas.size.width * 0.9f, this@Canvas.size.height * 0.22f)
                }
                drawPath(tick, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The single-choice twin: an orange ring with a dot. */
@Composable
internal fun SheetRadio(selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 18.dp) {
    val colors = SheetTheme.colors
    val ring by animateColorAsState(if (selected && enabled) colors.accent else colors.borderStrong, tween(FADE_MS))
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(size / 2).clip(CircleShape).background(colors.accent))
    }
}

private const val FADE_MS = 120
private const val TICK_SCALE = 0.7f
