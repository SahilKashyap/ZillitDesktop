package com.zillit.desktop.core.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField

/**
 * Colour swatches, width chips, undo and clear — one row over the canvas.
 * The image reply's toolbar, shared with the picked-media editor's Draw tool.
 */
@Composable
fun PenToolbar(pen: PenState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ColorSwatches(selected = pen.color, onSelect = { pen.color = it })
        Spacer(Modifier.width(ZillitTheme.spacing.sm))
        SizeDots(count = PEN_WIDTHS.size, selected = pen.widthIndex, onSelect = { pen.widthIndex = it })
        Spacer(Modifier.weight(1f))
        ToolLink(text = "Undo", enabled = pen.strokes.isNotEmpty(), onClick = pen::undo)
        ToolLink(text = "Clear", enabled = pen.strokes.isNotEmpty(), danger = true, onClick = pen::clear)
    }
}

/**
 * The text tool's row: the line to add, its colour and size, and the button
 * that drops it on the picture. [onPlace] is the canvas's business — it
 * knows the picture's centre and size — so the toolbar only asks.
 */
@Composable
fun TextToolbar(tool: TextToolState, onPlace: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = tool.input,
            onValueChange = { tool.input = it },
            placeholder = "Type a line, then Add…",
            onImeAction = onPlace,
            modifier = Modifier.weight(1f).widthIn(min = TEXT_FIELD_MIN_WIDTH),
        )
        ZillitButton(
            text = "Add",
            onClick = onPlace,
            enabled = tool.input.isNotBlank(),
            size = ButtonSize.Small,
        )
        ColorSwatches(selected = tool.color, onSelect = { tool.color = it })
        SizeDots(count = TEXT_SIZES.size, selected = tool.sizeIndex, onSelect = { tool.sizeIndex = it })
        ToolLink(text = "Undo", enabled = tool.texts.isNotEmpty(), onClick = tool::undo)
        ToolLink(text = "Clear", enabled = tool.texts.isNotEmpty(), danger = true, onClick = tool::clear)
    }
}

/**
 * The crop tool's row: what to do, and Apply / Reset — Android's crop
 * fragment has the same pair (`fragment_edit_image_crop.xml`), minus the
 * ratio presets a desktop drag makes redundant.
 */
@Composable
fun CropToolbar(canApply: Boolean, onApply: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = "Drag a rectangle over the picture, then Apply.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "Reset",
            onClick = onReset,
            enabled = canApply,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        ZillitButton(text = "Apply", onClick = onApply, enabled = canApply, size = ButtonSize.Small)
    }
}

/** The palette as a row of discs; the chosen one wears the accent ring. */
@Composable
fun ColorSwatches(selected: Color, onSelect: (Color) -> Unit) {
    PEN_COLORS.forEach { color ->
        val isSelected = selected == color
        Box(
            modifier = Modifier
                .size(SWATCH)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) SWATCH_RING_SELECTED else SWATCH_RING,
                    color = if (isSelected) ZillitTheme.colors.accent else ZillitTheme.colors.border,
                    shape = CircleShape,
                )
                .clickable { onSelect(color) },
        )
    }
}

/** Size chips — the dot inside grows with the size it selects. */
@Composable
fun SizeDots(count: Int, selected: Int, onSelect: (Int) -> Unit) {
    repeat(count) { index ->
        val isSelected = selected == index
        Box(
            modifier = Modifier
                .size(SWATCH)
                .clip(CircleShape)
                .background(
                    if (isSelected) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surfaceSunken,
                )
                .clickable { onSelect(index) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(SIZE_DOT_BASE * (index + 1))
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.textPrimary),
            )
        }
    }
}

/** Undo / Clear as text links, muted when there is nothing to act on. */
@Composable
private fun ToolLink(text: String, enabled: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = when {
            !enabled -> colors.textMuted
            danger -> colors.danger
            else -> colors.accentText
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

private val SWATCH = 24.dp
private val SWATCH_RING = 1.dp
private val SWATCH_RING_SELECTED = 2.dp
private val SIZE_DOT_BASE = 4.dp
private val TEXT_FIELD_MIN_WIDTH = 160.dp
