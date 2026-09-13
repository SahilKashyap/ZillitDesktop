package com.zillit.desktop.feature.esignature.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.ui.decodeImageBitmap

/**
 * One rendered page at [zoom], with a slot for overlays measured in
 * page points. The tap conversion is top-left to top-left — this
 * service's convention, no flip anywhere.
 */
@Composable
internal fun PageCanvas(
    page: EsignPage,
    zoom: Float,
    modifier: Modifier = Modifier,
    onTap: ((xPt: Double, yPt: Double) -> Unit)? = null,
    crosshair: Boolean = false,
    overlay: @Composable BoxScope.(scale: PageScale) -> Unit = {},
) {
    val bitmap = remember(page.page, page.imageBytes.size) { decodeImageBitmap(page.imageBytes) } ?: return
    val widthDp: Dp = (page.widthPx * zoom).dp
    val heightDp: Dp = (page.heightPx * zoom).dp
    val scale = PageScale(dpPerPoint = widthDp.value / page.widthPt.toFloat())
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitText(
            "Page ${page.page}",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .width(widthDp)
                .height(heightDp)
                .shadow(6.dp, ZillitTheme.shapes.small)
                .background(Color.White)
                .border(1.dp, ZillitTheme.colors.border)
                .then(if (crosshair && onTap != null) Modifier.pointerHoverIcon(PointerIcon.Crosshair) else Modifier)
                .then(
                    if (onTap != null) {
                        Modifier.pointerInput(page.page, zoom) {
                            detectTapGestures { offset ->
                                val xPt = offset.x / (size.width.toFloat() / page.widthPt)
                                val yPt = offset.y / (size.height.toFloat() / page.heightPt)
                                onTap(xPt.toDouble(), yPt.toDouble())
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Page ${page.page}",
                modifier = Modifier.size(widthDp, heightDp),
                contentScale = ContentScale.FillBounds,
            )
            overlay(scale)
        }
    }
}

/** Dp per PDF point at the current zoom. */
internal data class PageScale(val dpPerPoint: Float) {
    fun x(field: EnvelopeField): Dp = (field.x * dpPerPoint).dp
    fun y(field: EnvelopeField): Dp = (field.y * dpPerPoint).dp
    fun w(field: EnvelopeField): Dp = (field.width * dpPerPoint).dp
    fun h(field: EnvelopeField): Dp = (field.height * dpPerPoint).dp
    fun pt(dp: Float): Double = (dp / dpPerPoint).toDouble()
}

/** A field's rectangle on the page, positioned; children draw the face. */
@Composable
internal fun BoxScope.FieldRect(
    field: EnvelopeField,
    scale: PageScale,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(x = scale.x(field), y = scale.y(field))
            .size(scale.w(field), scale.h(field))
            .then(modifier),
        content = content,
    )
}
