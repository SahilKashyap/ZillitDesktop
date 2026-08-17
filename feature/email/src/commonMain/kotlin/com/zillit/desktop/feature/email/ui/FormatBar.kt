package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.email.domain.MarkFamily
import com.zillit.desktop.feature.email.domain.TextMark

/**
 * The composer's formatting controls.
 *
 * Undo and redo, the four switches, then a size and two colour pickers — the
 * inline formats mail clients render faithfully. Block formats (lists,
 * alignment, fonts) are deliberately absent until the document model can carry
 * them honestly.
 *
 * Every control is non-focusable: each acts on the field's current selection,
 * and a control that steals the caret makes every second action apply to
 * nothing.
 */
@Composable
internal fun FormatBar(
    state: FormatBarState,
    onToggle: (TextMark) -> Unit,
    onPick: (TextMark) -> Unit,
    onClear: (MarkFamily) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatChip(label = "↺", enabled = state.canUndo, isActive = false, onClick = onUndo)
        FormatChip(label = "↻", enabled = state.canRedo, isActive = false, onClick = onRedo)

        Spacer(Modifier.width(ZillitTheme.spacing.xs))

        SWITCH_MARKS.forEach { style ->
            FormatChip(
                label = style.switchLabel,
                labelStyle = style.switchLabelStyle,
                isActive = style in state.active,
                onClick = { onToggle(style) },
            )
        }

        Spacer(Modifier.width(ZillitTheme.spacing.xs))

        SizePicker(current = state.sizePx, onPick = onPick, onClear = onClear)
        ColorPicker(
            label = "A",
            current = state.colorHex,
            clears = MarkFamily.TextColor,
            style = { TextMark.TextColor(it) },
            onPick = onPick,
            onClear = onClear,
        )
        ColorPicker(
            label = "▉",
            current = state.highlightHex,
            clears = MarkFamily.Highlight,
            style = { TextMark.Highlight(it) },
            onPick = onPick,
            onClear = onClear,
        )
    }
}

/** One toolbar button. Non-focusable — see [FormatBar]. */
@Composable
private fun FormatChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    labelStyle: TextStyle? = null,
    enabled: Boolean = true,
    underlay: Color? = null,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(
                when {
                    isActive -> colors.accentSoft
                    hovered && enabled -> colors.surfaceHover
                    else -> colors.canvas
                },
            )
            .hoverable(interaction)
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The button is styled as the thing it does — a bold B, an italic I.
        // Faster to read than any icon set would be for this many states.
        ZillitText(
            text = label,
            style = labelStyle ?: ZillitTheme.typography.bodyMedium,
            color = when {
                !enabled -> colors.textDisabled
                isActive -> colors.accentText
                else -> colors.textSecondary
            },
        )
        if (underlay != null) {
            Box(
                Modifier
                    .size(SWATCH_BAR_WIDTH, SWATCH_BAR_HEIGHT)
                    .background(underlay, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Font size, from the same whitelist the web composer offers. */
@Composable
private fun SizePicker(
    current: Int?,
    onPick: (TextMark) -> Unit,
    onClear: (MarkFamily) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = ZillitTheme.colors

    Box {
        FormatChip(
            label = current?.toString() ?: "16",
            isActive = current != null,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(MENU_RADIUS)),
        ) {
            FONT_SIZES.forEach { px ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        // The default size is the absence of a mark, so the
                        // body follows the theme instead of hard-coding it.
                        if (px == DEFAULT_SIZE) onClear(MarkFamily.FontSize) else onPick(TextMark.FontSize(px))
                    },
                    text = {
                        ZillitText(
                            text = if (px == DEFAULT_SIZE) "$px (default)" else "$px",
                            style = ZillitTheme.typography.bodyMedium,
                            color = if (px == current) colors.accentText else colors.textPrimary,
                        )
                    },
                )
            }
        }
    }
}

/** Text or highlight colour, over one shared palette. */
@Composable
private fun ColorPicker(
    label: String,
    current: String?,
    clears: MarkFamily,
    style: (String) -> TextMark,
    onPick: (TextMark) -> Unit,
    onClear: (MarkFamily) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = ZillitTheme.colors

    Box {
        FormatChip(
            label = label,
            isActive = current != null,
            onClick = { expanded = true },
            underlay = current?.let { hexColor(it) } ?: colors.textMuted,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(MENU_RADIUS)),
        ) {
            DropdownMenuItem(
                onClick = {
                    expanded = false
                    onClear(clears)
                },
                text = {
                    ZillitText(
                        text = "Default",
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                    )
                },
            )
            PALETTE.chunked(SWATCHES_PER_ROW).forEach { row ->
                SwatchRow(row, current) { hex ->
                    expanded = false
                    onPick(style(hex))
                }
            }
        }
    }
}

@Composable
private fun SwatchRow(row: List<String>, current: String?, onPick: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.padding(
            horizontal = ZillitTheme.spacing.md,
            vertical = ZillitTheme.spacing.xxs,
        ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        row.forEach { hex ->
            Box(
                Modifier
                    .size(SWATCH_SIZE)
                    .clip(RoundedCornerShape(SWATCH_RADIUS))
                    .background(hexColor(hex) ?: colors.textMuted)
                    .border(
                        if (hex == current) 2.dp else 1.dp,
                        if (hex == current) colors.accent else colors.border,
                        RoundedCornerShape(SWATCH_RADIUS),
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

private val TextMark.switchLabel: String
    get() = when (this) {
        TextMark.Italic -> "I"
        TextMark.Underline -> "U"
        TextMark.Strike -> "S"
        else -> "B"
    }

private val TextMark.switchLabelStyle: TextStyle
    @Composable get() = ZillitTheme.typography.bodyMedium.copy(
        fontWeight = if (this == TextMark.Bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (this == TextMark.Italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = when (this) {
            TextMark.Underline -> TextDecoration.Underline
            TextMark.Strike -> TextDecoration.LineThrough
            else -> null
        },
    )

/** The web composer's Quill size whitelist. */
internal val FONT_SIZES = listOf(10, 12, 14, 16, 18, 20, 24, 28, 32, 36)
internal const val DEFAULT_SIZE = 16

/** A compact cut of Quill's default swatch grid — the colours mail actually uses. */
internal val PALETTE = listOf(
    "#000000", "#444444", "#888888", "#e60000", "#ff9900",
    "#ffff00", "#008a00", "#0066cc", "#9933ff", "#ffffff",
)

private const val SWATCHES_PER_ROW = 5
private val SWATCH_SIZE = 20.dp
private val SWATCH_RADIUS = 4.dp
private val SWATCH_BAR_WIDTH = 14.dp
private val SWATCH_BAR_HEIGHT = 3.dp
private val MENU_RADIUS = 8.dp
