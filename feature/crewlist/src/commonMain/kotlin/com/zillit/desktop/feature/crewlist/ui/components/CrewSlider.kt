package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.math.roundToInt

/**
 * A slim stepped slider — a 4-point track, the accent up to a round thumb.
 * [onChange] follows the thumb (the label updates as it moves); [onCommit]
 * fires once, on release or tap, which is when the web's `onChangeComplete`
 * writes the value into the layout.
 */
@Composable
@Suppress("LongMethod") // One control: its gestures, track and thumb share the same geometry.
internal fun CrewSlider(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The value under the pointer as last seen, so a release commits exactly that.
    val lastSeen = remember { intArrayOf(value) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(THUMB + 8.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val thumbPx = with(density) { THUMB.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        fun valueAt(x: Float): Int {
            val usable = (widthPx - thumbPx).coerceAtLeast(1f)
            val fraction = ((x - thumbPx / 2) / usable).coerceIn(0f, 1f)
            val raw = min + fraction * (max - min)
            return ((raw / step).roundToInt() * step).coerceIn(min, max)
        }
        val fraction = if (max > min) (value - min).toFloat() / (max - min) else 0f
        val thumbOffset = (maxWidth - THUMB) * fraction

        Box(
            Modifier
                .fillMaxWidth()
                .pointerInput(min, max, step, widthPx) {
                    detectTapGestures { offset ->
                        val picked = valueAt(offset.x)
                        lastSeen[0] = picked
                        onChange(picked)
                        onCommit(picked)
                    }
                }
                .pointerInput(min, max, step, widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            lastSeen[0] = valueAt(offset.x)
                            onChange(lastSeen[0])
                        },
                        onDragEnd = { onCommit(lastSeen[0]) },
                        onDragCancel = { onCommit(lastSeen[0]) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val picked = valueAt(change.position.x)
                            if (picked != lastSeen[0]) {
                                lastSeen[0] = picked
                                onChange(picked)
                            }
                        },
                    )
                }
                .height(THUMB + 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = THUMB / 2)
                    .height(TRACK)
                    .clip(RoundedCornerShape(TRACK))
                    .background(colors.borderStrong.copy(alpha = 0.6f)),
            )
            Box(
                Modifier
                    .padding(start = THUMB / 2)
                    .width(thumbOffset.coerceAtLeast(0.dp))
                    .height(TRACK)
                    .clip(RoundedCornerShape(TRACK))
                    .background(colors.accent),
            )
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(THUMB)
                    .shadow(if (hovered) 4.dp else 2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, colors.accent, CircleShape),
            )
        }
    }
}

private val THUMB = 16.dp
private val TRACK = 4.dp
