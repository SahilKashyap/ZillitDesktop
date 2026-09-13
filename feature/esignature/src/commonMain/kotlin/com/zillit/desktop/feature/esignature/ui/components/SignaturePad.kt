@file:Suppress("LongMethod", "MagicNumber") // The pad; the type-style scale table is the web's.

package com.zillit.desktop.feature.esignature.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.SignatureFont
import com.zillit.desktop.feature.esignature.ui.PadMode
import com.zillit.desktop.feature.esignature.ui.PadState

/**
 * The mark capture — the web's `SavedSignaturePicker`: pick a saved mark,
 * draw one, type a name in a script face, or upload an image. One
 * composable serves both the signer's pad and the library manager.
 */
@Composable
internal fun SignaturePad(
    pad: PadState,
    saved: List<SavedSignature>,
    savedImages: Map<String, ByteArray>,
    forSignature: Boolean,
    showSaved: Boolean,
    onMode: (PadMode) -> Unit,
    onStroke: (List<Pair<Float, Float>>) -> Unit,
    onClear: () -> Unit,
    onTyped: (String) -> Unit,
    onFont: (SignatureFont) -> Unit,
    onPickImage: () -> Unit,
    onUseSaved: (String) -> Unit,
    onDeleteSaved: ((String) -> Unit)? = null,
    onSaveForLater: ((Boolean) -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val modes = PadMode.entries.filter { showSaved || it != PadMode.Saved }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitSegmented(
            options = modes.map { ZillitTab(it.name, it.label) },
            activeId = pad.mode.name,
            onSelect = { id -> PadMode.entries.firstOrNull { it.name == id }?.let(onMode) },
        )
        when (pad.mode) {
            PadMode.Saved -> SavedMarks(saved, savedImages, forSignature, onUseSaved, onDeleteSaved)
            PadMode.Draw -> DrawSurface(pad.strokes, onStroke, onClear)
            PadMode.Type -> TypeSurface(pad, onTyped, onFont)
            PadMode.Upload -> UploadSurface(pad, onPickImage)
        }
        if (onSaveForLater != null && pad.mode != PadMode.Saved) {
            ZillitCheckbox(
                checked = pad.saveForLater,
                onCheckedChange = onSaveForLater,
                label = if (forSignature) "Save this signature for next time" else "Save these initials for next time",
            )
        }
        if (pad.mode == PadMode.Draw) {
            ZillitText(
                "Draw with the mouse or trackpad. Clear starts again.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun SavedMarks(
    saved: List<SavedSignature>,
    images: Map<String, ByteArray>,
    forSignature: Boolean,
    onUse: (String) -> Unit,
    onDelete: ((String) -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val mine = saved.filter { it.isSignature == forSignature }
    if (mine.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().height(120.dp).clip(ZillitTheme.shapes.medium)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                if (forSignature) {
                    "No saved signatures yet — draw or type one."
                } else {
                    "No saved initials yet — draw or type some."
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        return
    }
    ZillitScrollColumn(
        Modifier.fillMaxWidth().heightIn(max = SAVED_LIST_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        mine.forEach { mark ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .clickable { onUse(mark.id) }
                    .padding(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Box(
                    Modifier.weight(1f).height(72.dp).clip(ZillitTheme.shapes.small).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    BytesImage(
                        images[mark.id],
                        if (forSignature) "Signature" else "Initials",
                        Modifier.fillMaxWidth().height(64.dp),
                    )
                }
                ZillitButton(text = "Use", onClick = { onUse(mark.id) }, size = ButtonSize.Small)
                if (onDelete != null) {
                    ZillitButton(
                        text = "Delete",
                        onClick = { onDelete(mark.id) },
                        size = ButtonSize.Small,
                        variant = ButtonVariant.Tertiary,
                        leadingIcon = ZillitIcons.Trash,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawSurface(
    strokes: List<List<Pair<Float,
    Float>>>,
    onStroke: (List<Pair<Float, Float>>) -> Unit,
    onClear: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val current = remember { mutableListOf<Pair<Float, Float>>() }
    val padSize = remember { intArrayOf(0, 0) }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PAD_ASPECT)
                    .clip(ZillitTheme.shapes.medium)
                    .background(Color.White)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .onSizeChanged {
                        padSize[0] = it.width
                        padSize[1] = it.height
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                current.clear()
                                current += offset.toRaster(padSize)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                current += change.position.toRaster(padSize)
                            },
                            onDragEnd = {
                                if (current.size > 1) onStroke(current.toList())
                                current.clear()
                            },
                        )
                    },
            ) {
                val scaleX = size.width / RASTER_WIDTH
                val scaleY = size.height / RASTER_HEIGHT
                // The baseline the web's pad draws — where a signature sits.
                drawLine(
                    color = Color(0xFFCBD5E1),
                    start = Offset(size.width * 0.08f, size.height * 0.78f),
                    end = Offset(size.width * 0.92f, size.height * 0.78f),
                    strokeWidth = 1f,
                )
                strokes.forEach { stroke ->
                    if (stroke.size < 2) return@forEach
                    val path = Path()
                    path.moveTo(stroke.first().first * scaleX, stroke.first().second * scaleY)
                    stroke.drop(1).forEach { (px, py) -> path.lineTo(px * scaleX, py * scaleY) }
                    drawPath(
                        path,
                        INK,
                        style = Stroke(width = PEN_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            if (strokes.isNotEmpty()) {
                ZillitButton(
                    text = "Clear",
                    onClick = onClear,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun TypeSurface(pad: PadState, onTyped: (String) -> Unit, onFont: (SignatureFont) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = pad.typedName,
            onValueChange = onTyped,
            placeholder = "Type your name",
            label = "Name",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            SignatureFont.entries.forEach { font ->
                val active = font == pad.font
                Box(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.pill)
                        .background(if (active) colors.accent else Color.Transparent)
                        .border(1.dp, if (active) colors.accent else colors.border, ZillitTheme.shapes.pill)
                        .clickable { onFont(font) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    ZillitText(
                        font.label,
                        style = ZillitTheme.typography.labelSmall,
                        color = if (active) Color.White else colors.textSecondary,
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(PAD_ASPECT).clip(ZillitTheme.shapes.medium)
                .background(Color.White).border(1.dp, colors.border, ZillitTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = pad.typedName.ifBlank { "Your name" },
                style = ZillitTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Cursive,
                    fontStyle = FontStyle.Italic,
                    fontSize = (PREVIEW_PT * fontScale(pad.font)).sp,
                ),
                color = if (pad.typedName.isBlank()) Color(0xFFCBD5E1) else INK,
                maxLines = 1,
            )
        }
        ZillitText(
            "The saved mark is set in the chosen script face — the preview shows the shape, not the exact font.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun UploadSurface(pad: PadState, onPick: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(PAD_ASPECT).clip(ZillitTheme.shapes.medium)
                .background(Color.White).border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (pad.uploadBytes != null) {
                BytesImage(pad.uploadBytes, "Uploaded mark", Modifier.fillMaxWidth().padding(8.dp))
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZillitText(
                        "Choose a PNG or JPG",
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    ZillitText(
                        "A transparent PNG sits best on the page · up to 8 MB",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = if (pad.uploadBytes == null) "Choose image" else "Replace",
                onClick = onPick,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Upload,
            )
            if (pad.uploadName.isNotBlank()) {
                ZillitText(
                    pad.uploadName,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.width(260.dp),
                )
            }
        }
    }
}

private fun Offset.toRaster(padSize: IntArray): Pair<Float, Float> {
    val w = if (padSize[0] == 0) 1 else padSize[0]
    val h = if (padSize[1] == 0) 1 else padSize[1]
    return (x / w * RASTER_WIDTH) to (y / h * RASTER_HEIGHT)
}

private fun fontScale(font: SignatureFont): Float = when (font) {
    SignatureFont.Formal -> 1.0f
    SignatureFont.Flowing -> 0.92f
    SignatureFont.Casual -> 1.05f
    SignatureFont.Slim -> 1.0f
    SignatureFont.Bold -> 0.9f
    SignatureFont.Natural -> 0.72f
}

private val SAVED_LIST_MAX_HEIGHT = 300.dp
private const val PAD_ASPECT = 800f / 300f
private const val RASTER_WIDTH = 800f
private const val RASTER_HEIGHT = 300f
private const val PEN_WIDTH = 3f
private const val PREVIEW_PT = 44f
private val INK = Color(0xFF162A60)
