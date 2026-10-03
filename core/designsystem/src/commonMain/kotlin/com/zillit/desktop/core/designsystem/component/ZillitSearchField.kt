package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A search box: magnifier, text, and a clear button once there is something to
 * clear.
 *
 * Separate from [ZillitTextField] rather than a variant of it. A search field
 * has no label, no validation, no error state and no IME action worth
 * submitting; expressing it as a labelled text field with five parameters
 * disabled would leave every call site carrying arguments that must stay off.
 *
 * The clear button is not decoration — a filter with no visible way to reset is
 * a way to make a list look empty for reasons the user cannot see.
 */
@Composable
fun ZillitSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.search),
    enabled: Boolean = true,
    /** Overrides the well's fill — the chat listing's grey pill. */
    containerColor: androidx.compose.ui.graphics.Color? = null,
    /**
     * False drops the resting hairline and keeps only the focus ring — a
     * filled pill that is its own edge, as the chat listing draws it.
     */
    bordered: Boolean = true,
) {
    val colors = ZillitTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = FIELD_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(containerColor ?: colors.surfaceSunken)
            .then(searchEdge(focused, bordered))
            .padding(horizontal = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Search,
            tint = colors.textMuted,
            size = ICON_SIZE,
        )

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                ZillitText(
                    text = placeholder,
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // Full width, or the empty field is a zero-width click target
                // inside a wide box — focusable only by pixel-hunting.
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }

        if (value.isNotEmpty()) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.ah_cd_clear_search),
                onClick = { onValueChange("") },
                tint = colors.textMuted,
            )
        }
    }
}

/**
 * The well's edge: the focus ring, the resting hairline, or — unbordered and
 * unfocused — no edge at all. Not a 0dp border: in Compose that is not
 * nothing, it is the one-pixel hairline.
 */
@Composable
private fun searchEdge(focused: Boolean, bordered: Boolean): Modifier {
    val colors = ZillitTheme.colors
    return if (focused || bordered) {
        Modifier.border(
            width = if (focused) FOCUS_BORDER else HAIRLINE,
            color = if (focused) colors.focusRing else colors.border,
            shape = ZillitTheme.shapes.medium,
        )
    } else {
        Modifier
    }
}

private val FIELD_HEIGHT = 36.dp
private val ICON_SIZE = 16.dp
private val HAIRLINE = 1.dp
private val FOCUS_BORDER = 2.dp
