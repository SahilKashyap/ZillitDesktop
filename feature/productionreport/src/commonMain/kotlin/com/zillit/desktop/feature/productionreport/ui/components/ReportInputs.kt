// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.productionreport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * The report's boxed input: 1 px border, radius 8, and the web's focus —
 * an orange border with a soft 2 px ring.
 */
@Composable
internal fun ReportInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    autoFocus: Boolean = false,
    leadingIcon: ImageVector? = null,
    radius: Dp = 8.dp,
    textStyle: TextStyle? = null,
    error: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onEnter: (() -> Unit)? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val colors = ReportTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChange(focused) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val border = when {
        error -> Color(0xFFF04438)
        focused -> colors.accent
        else -> colors.borderStrong
    }
    val ring = when {
        error && focused -> Color(0x33F04438)
        focused -> colors.accent.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    val style = (textStyle ?: reportText(14.sp)).copy(color = colors.textPrimary)
    Box(
        modifier = modifier
            .border(2.dp, ring, RoundedCornerShape(radius + 2.dp))
            .padding(2.dp)
            .clip(RoundedCornerShape(radius))
            .background(if (colors.isDark) Color(0x0FFFFFFF) else Color.White)
            .border(1.dp, border, RoundedCornerShape(radius)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            leadingIcon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.padding(end = 8.dp).size(14.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        style = style.copy(color = colors.textMuted),
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.accent),
                    interactionSource = source,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 20.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            val enterKey = event.key == Key.Enter || event.key == Key.NumPadEnter
                            val enter = event.type == KeyEventType.KeyDown && enterKey
                            val submits = singleLine || !event.isShiftPressed
                            if (enter && onEnter != null && submits) {
                                onEnter()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        }
    }
}

/** A field label: 11 px, uppercase, 6 px above the control. */
@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = reportText(11.sp, FontWeight.Medium).copy(letterSpacing = 0.5.sp),
        color = ReportTheme.colors.textPrimary.copy(alpha = if (ReportTheme.colors.isDark) 0.75f else 0.85f),
        modifier = modifier.padding(bottom = 6.dp),
    )
}
