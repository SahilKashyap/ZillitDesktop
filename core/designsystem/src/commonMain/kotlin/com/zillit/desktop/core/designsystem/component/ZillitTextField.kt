package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The app's only text field.
 *
 * Built on `BasicTextField` rather than Material's `OutlinedTextField` because
 * the Material one hardcodes a 56dp height and a floating label — both sized for
 * touch. At desktop density that wastes vertical space and looks like a phone
 * app scaled up.
 *
 * Label, helper text, error state, leading/trailing content and character count
 * are all parameters, so there is one focus/hover/error implementation rather
 * than a family that drifts apart.
 */
@Composable
fun ZillitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    leadingIcon: ImageVector? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    maxLength: Int? = null,
    onImeAction: () -> Unit = {},
    containerColor: Color? = null,
    contentColor: Color? = null,
    shape: Shape? = null,
) {
    // The String API cannot say where the cursor goes, so this mirror decides:
    // while the text round-trips unchanged the user's own selection stands, and
    // a replacement from outside — a mention completed, a draft cleared on send
    // — lands the cursor at the end. Without this the cursor stays at its old
    // offset and the next keystrokes splice into the middle of the new text.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    if (fieldValue.text != value) {
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    ZillitTextField(
        value = fieldValue,
        onValueChange = { next ->
            fieldValue = next
            if (next.text != value) onValueChange(next.text)
        },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helperText = helperText,
        errorText = errorText,
        leadingIcon = leadingIcon,
        trailingContent = trailingContent,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        keyboardType = keyboardType,
        imeAction = imeAction,
        visualTransformation = visualTransformation,
        maxLength = maxLength,
        onImeAction = onImeAction,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = shape,
    )
}

/** The full-control variant: callers who own selection state pass a [TextFieldValue]. */
@Composable
fun ZillitTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    leadingIcon: ImageVector? = null,
    /**
     * A control at the trailing edge — a date picker's calendar button, a
     * password reveal. Inside the field's border, so it reads as part of the
     * input rather than a button that happens to sit beside it.
     */
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    maxLength: Int? = null,
    onImeAction: () -> Unit = {},
    /**
     * Overrides for surfaces with their own contract — the composer's white
     * field on its gray bar. Null keeps the theme's colors, which is every
     * other field in the app.
     */
    containerColor: Color? = null,
    contentColor: Color? = null,
    /** Overrides the theme's field shape — the composer's pill. */
    shape: Shape? = null,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val hasError = errorText != null

    val borderColor by animateColorAsState(
        when {
            !enabled -> colors.border
            hasError -> colors.danger
            focused -> colors.accent
            hovered -> colors.borderStrong
            else -> colors.border
        },
        label = "fieldBorder",
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        label?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.label,
                color = if (hasError) colors.danger else colors.textSecondary,
            )
        }

        FieldRow(
            borderColor = borderColor,
            enabled = enabled,
            containerColor = containerColor,
            shape = shape,
            leadingIcon = leadingIcon,
            trailingContent = trailingContent,
            trailing = maxLength?.let { limit -> "${value.text.length}/$limit" },
        ) {
            FieldInput(
                value = value,
                onValueChange = onValueChange,
                contentColor = contentColor,
                placeholder = placeholder,
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                keyboardType = keyboardType,
                imeAction = imeAction,
                visualTransformation = visualTransformation,
                maxLength = maxLength,
                interaction = interaction,
                onFocusChanged = { focused = it },
                onImeAction = onImeAction,
            )
        }

        // Error replaces helper text rather than stacking, so the field's height
        // does not jump when validation fails.
        (errorText ?: helperText)?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = if (hasError) colors.danger else colors.textMuted,
            )
        }
    }
}

/** The editable text itself, plus its placeholder. */
@Suppress("LongParameterList")
@Composable
private fun FieldInput(
    value: TextFieldValue,
    contentColor: Color?,
    onValueChange: (TextFieldValue) -> kotlin.Unit,
    placeholder: String?,
    enabled: Boolean,
    readOnly: Boolean,
    singleLine: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    visualTransformation: VisualTransformation,
    maxLength: Int?,
    interaction: MutableInteractionSource,
    onFocusChanged: (Boolean) -> kotlin.Unit,
    onImeAction: () -> kotlin.Unit,
) {
    val colors = ZillitTheme.colors
    if (value.text.isEmpty() && placeholder != null) {
        ZillitText(placeholder, style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            // Truncate rather than reject: silently dropping the keystroke that
            // hits the limit reads as a broken keyboard.
            onValueChange(
                if (maxLength != null && next.text.length > maxLength) {
                    TextFieldValue(next.text.take(maxLength), TextRange(maxLength))
                } else {
                    next
                },
            )
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        textStyle = ZillitTheme.typography.bodyMedium.copy(
            color = contentColor ?: if (enabled) colors.textPrimary else colors.textDisabled,
        ),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onGo = { onImeAction() },
            onNext = { onImeAction() },
            onSend = { onImeAction() },
        ),
        interactionSource = interaction,
    )
}

/** The bordered row: leading icon, the input itself, and an optional counter. */
@Composable
private fun FieldRow(
    containerColor: Color?,
    borderColor: Color,
    enabled: Boolean,
    shape: Shape?,
    leadingIcon: ImageVector?,
    trailing: String?,
    trailingContent: (@Composable () -> kotlin.Unit)?,
    // Last, so the call site's trailing lambda binds here and not to the slot
    // above it.
    input: @Composable () -> kotlin.Unit,
) {
    val colors = ZillitTheme.colors
    val fieldShape = shape ?: ZillitTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ZillitDimens.controlHeight)
            .clip(fieldShape)
            .background(containerColor ?: if (enabled) colors.surface else colors.surfaceHover)
            .border(FieldBorderWidth, borderColor, fieldShape)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        leadingIcon?.let { ZillitIcon(it, tint = colors.textMuted, size = ZillitDimens.iconSmall) }
        Box(Modifier.weight(1f)) { input() }
        trailing?.let {
            ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        trailingContent?.invoke()
    }
}

private val FieldBorderWidth = 1.dp
