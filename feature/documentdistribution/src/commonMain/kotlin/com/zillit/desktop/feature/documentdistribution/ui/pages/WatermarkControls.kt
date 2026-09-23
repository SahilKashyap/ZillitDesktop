package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSize
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import kotlin.math.roundToInt

/**
 * Size / colour / opacity — the web's `WatermarkStyleControls`, shared by
 * the composer's wizard, the single download and the batch zip.
 */
@Composable
internal fun WatermarkStyleControls(value: WatermarkStyle, onChange: (WatermarkStyle) -> Unit) {
    val c = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel(str(S.dd_watermark_size))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                WatermarkSize.entries.forEach { size ->
                    ZillitChoiceChip(
                        label = str(
                            when (size) {
                                WatermarkSize.Small -> S.dd_watermark_size_small
                                WatermarkSize.Medium -> S.dd_watermark_size_medium
                                WatermarkSize.Large -> S.dd_watermark_size_large
                            },
                        ),
                        selected = value.size == size,
                        onClick = { onChange(value.copy(size = size)) },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel(str(S.dd_watermark_color))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                WatermarkStyle.COLORS.forEach { hex ->
                    val chosen = value.color.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(hexColor(hex))
                            .border(if (chosen) 2.dp else 0.5.dp, if (chosen) c.accent else c.borderStrong, CircleShape)
                            .clickable { onChange(value.copy(color = hex)) },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel(str(S.dd_watermark_opacity)) {
                ZillitText(
                    text = "${(value.opacity * PERCENT).roundToInt()}%",
                    style = ZillitTheme.typography.label,
                    color = c.textSecondary,
                )
            }
            OpacitySlider(
                value = value.opacity.toFloat(),
                onChange = { fraction ->
                    onChange(value.copy(opacity = (fraction * OPACITY_STEPS).roundToInt() / OPACITY_STEPS.toDouble()))
                },
            )
        }
    }
}

/**
 * A slim track with a round thumb, 5% to 70% — drawn here because the
 * Material slider's thick track and tall thumb are out of scale with the
 * rest of these controls.
 */
@Composable
private fun OpacitySlider(value: Float, onChange: (Float) -> Unit) {
    val c = ZillitTheme.colors
    val accent = c.accent
    val track = c.border
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(SLIDER_HEIGHT)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onChange(fractionAt(offset.x, size.width.toFloat())) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onChange(fractionAt(change.position.x, size.width.toFloat())) }
            },
    ) {
        val y = size.height / 2
        val radius = THUMB_RADIUS.toPx()
        val fraction = ((value - OPACITY_MIN) / (OPACITY_MAX - OPACITY_MIN)).coerceIn(0f, 1f)
        val x = radius + fraction * (size.width - 2 * radius)
        drawLine(
            track, Offset(radius, y), Offset(size.width - radius, y), strokeWidth = TRACK_STROKE, cap = StrokeCap.Round,
        )
        drawLine(accent, Offset(radius, y), Offset(x, y), strokeWidth = TRACK_STROKE, cap = StrokeCap.Round)
        drawCircle(Color.White, radius, Offset(x, y))
        drawCircle(accent, radius, Offset(x, y), style = Stroke(width = THUMB_RING))
    }
}

/** The pointer's x as a value on the range, clamped. */
private fun fractionAt(x: Float, width: Float): Float =
    OPACITY_MIN + (x / width).coerceIn(0f, 1f) * (OPACITY_MAX - OPACITY_MIN)

private val SLIDER_HEIGHT = 24.dp
private val THUMB_RADIUS = 7.dp
private const val TRACK_STROKE = 6f
private const val THUMB_RING = 4f

/**
 * The stamp as it will land: rotated across a page, or over the document
 * itself when its first page or image is supplied. Line 2 draws at 70% of
 * line 1, as every engine renders it.
 */
@Composable
internal fun WatermarkPreview(
    style: WatermarkStyle,
    text: String,
    modifier: Modifier = Modifier,
    image: ByteArray? = null,
    loading: Boolean = false,
) {
    val c = ZillitTheme.colors
    val bitmap = remember(image) { image?.let(::decodeImageBitmap) }
    val lines = text.split('\n').filter { it.isNotBlank() }.take(2)
    val base = (BASE_STAMP_SP * style.size.scale).toFloat()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PAGE_RATIO)
            .clip(ZillitTheme.shapes.large)
            .background(Color.White)
            .border(0.5.dp, c.border, ZillitTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            // A page of faint rules, so the stamp reads as sitting on a document.
            Canvas(Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl)) {
                val gap = size.height / RULE_COUNT
                for (i in 1 until RULE_COUNT) {
                    drawLine(
                        Color(0xFFE5E7EB),
                        androidx.compose.ui.geometry.Offset(0f, i * gap),
                        androidx.compose.ui.geometry.Offset(size.width, i * gap),
                        strokeWidth = 2f,
                    )
                }
            }
        }
        if (loading) ZillitSpinner(
            modifier = Modifier.align(Alignment.TopEnd).padding(ZillitTheme.spacing.sm),
            size = 14.dp,
        )
        Column(
            modifier = Modifier.rotate(STAMP_ANGLE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            lines.forEachIndexed { index, line ->
                ZillitText(
                    text = line,
                    style = ZillitTheme.typography.titleLarge.copy(
                        fontSize = (if (index == 0) base else base * WatermarkStyle.LINE2_SCALE.toFloat()).sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    ),
                    color = hexColor(style.color).copy(alpha = style.opacity.toFloat()),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** `#6b7280` → Color; anything unparseable is the default grey. */
internal fun hexColor(hex: String): Color {
    val digits = hex.trim().removePrefix("#")
    val value = digits.toLongOrNull(HEX_RADIX) ?: return DEFAULT_GREY
    return when (digits.length) {
        RGB_DIGITS -> Color(OPAQUE_ALPHA or value)
        ARGB_DIGITS -> Color(value)
        else -> DEFAULT_GREY
    }
}

private val DEFAULT_GREY = Color(0xFF6B7280)
private const val HEX_RADIX = 16
private const val RGB_DIGITS = 6
private const val ARGB_DIGITS = 8
private const val OPAQUE_ALPHA = 0xFF000000L

private const val PERCENT = 100
private const val OPACITY_STEPS = 20
private const val OPACITY_MIN = 0.05f
private const val OPACITY_MAX = 0.7f
private const val BASE_STAMP_SP = 22.0
private const val PAGE_RATIO = 1f / 1.3f
private const val RULE_COUNT = 12
private const val STAMP_ANGLE = -30f
