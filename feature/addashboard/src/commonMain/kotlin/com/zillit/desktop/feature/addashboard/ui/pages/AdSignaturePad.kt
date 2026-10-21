package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Where a signature is drawn, for the day's sign-off and for a cast row.
 *
 * The strokes are collected here and rendered to a PNG by the host — encoding
 * an image is the platform's work. A typed name is the fallback for a mouse
 * nobody can sign with, and it is rendered the same way: what the server
 * stores has to be an image either way, because it is printed on every
 * voucher and on the published sheet.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun AdSignaturePad(
    strokes: List<List<Pair<Float, Float>>>,
    typedName: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = PAD_HEIGHT,
    onStroke: (List<Pair<Float, Float>>) -> Unit,
    onTypedName: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = ZillitTheme.colors
    var live by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), modifier = modifier) {
        ZillitSectionLabel(text = str(S.desktop_dm_draw_your_signature))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(PAD_BORDER, colors.border, ZillitTheme.shapes.medium)
                .pointerInput(busy) {
                    if (busy) return@pointerInput
                    detectDragGestures(
                        onDragStart = { start -> live = listOf(start.x to start.y) },
                        onDragEnd = {
                            if (live.size > 1) onStroke(live)
                            live = emptyList()
                        },
                        onDragCancel = { live = emptyList() },
                    ) { change, _ ->
                        live = live + (change.position.x to change.position.y)
                    }
                },
        ) {
            (strokes + listOf(live)).forEach { stroke ->
                if (stroke.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(stroke.first().first, stroke.first().second)
                    stroke.drop(1).forEach { (x, y) -> lineTo(x, y) }
                }
                drawPath(
                    path = path,
                    color = colors.textPrimary,
                    style = Stroke(width = INK_WIDTH, cap = StrokeCap.Round),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = typedName,
                onValueChange = onTypedName,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = str(S.desktop_ds_type_your_name),
                enabled = !busy,
            )
            ZillitButton(
                text = str(S.clear),
                onClick = onClear,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
        ZillitText(
            text = str(S.desktop_ad_signature_pad_hint),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    }
}

/** The size a rendered signature is asked for, in pixels. */
internal const val SIGNATURE_WIDTH_PX = 600
internal const val SIGNATURE_HEIGHT_PX = 200

private val PAD_HEIGHT = 160.dp
private val PAD_BORDER = 1.dp
private const val INK_WIDTH = 2.5f
