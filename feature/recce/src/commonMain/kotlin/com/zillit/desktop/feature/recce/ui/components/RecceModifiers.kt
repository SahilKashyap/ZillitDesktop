package com.zillit.desktop.feature.recce.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A 1dp dashed outline — the web's `border: 1px dashed` on the add row and the empty map box. */
@Composable
internal fun Modifier.dashedBorder(color: Color, shape: RoundedCornerShape, width: Dp = 1.dp): Modifier {
    val density = LocalDensity.current
    val stroke = with(density) { width.toPx() }
    val dash = with(density) { DASH.toPx() }
    val radius = with(density) { shape.topStart.toPx(Size.Zero, this) }
    return drawBehind {
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
        )
    }
}

private val DASH = 5.dp
