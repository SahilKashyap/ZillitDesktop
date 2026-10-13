package com.zillit.desktop.core.workspace.forward

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The questions a film tool asks before it will take a forwarded file.
 *
 * The web has a modal per tool — `DocumentModal` for the distributions and
 * the budgets, `AddScene` for continuity, each tool's own create dialog for
 * casting, wardrobe and location — all collecting from the same small set of
 * fields. This draws whichever ones the destination asked for
 * ([ForwardReach.open]) and hands the answers back by key.
 *
 * Kept composed with its form held through the exit, as every other dialog
 * in this file's neighbourhood is, so the close can animate.
 */
@Composable
fun ForwardFormDialog(
    form: ForwardForm?,
    onSubmit: (answers: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    /** True while the answers are on the wire — the buttons go quiet. */
    busy: Boolean = false,
) {
    var shown by remember { mutableStateOf(form) }
    if (form != null) shown = form
    val asking = shown ?: return
    var answers by remember(asking.targetId) {
        mutableStateOf(asking.fields.associate { it.key to it.initial() })
    }
    val missing = asking.fields.any { it.required && answers[it.key].isNullOrBlank() }

    ZillitDialogShell(
        title = asking.title,
        icon = ZillitIcons.Forward,
        visible = form != null,
        onDismiss = onDismiss,
        width = FORM_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                enabled = !busy,
            )
            ZillitButton(
                text = str(S.send),
                enabled = !missing && !busy,
                onClick = { onSubmit(answers) },
            )
        },
    ) {
        asking.note?.let { note ->
            ZillitText(
                text = note,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        asking.fields.forEach { field ->
            FieldRow(
                field = field,
                value = answers[field.key].orEmpty(),
                enabled = !busy,
                onValue = { value -> answers = answers + (field.key to value) },
            )
        }
    }
}

/** One question, drawn as the design system draws its kind. */
@Composable
private fun FieldRow(
    field: ForwardField,
    value: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    when (field) {
        is ForwardField.Text -> ZillitTextField(
            value = value,
            onValueChange = onValue,
            label = field.label,
            placeholder = field.hint.orEmpty(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        is ForwardField.Day -> ZillitDateField(
            value = value,
            onValueChange = onValue,
            label = field.label,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        is ForwardField.Choice -> {
            ZillitText(
                text = field.label,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            val chosen = field.options.firstOrNull { it.value == value } ?: field.options.first()
            // A dot only where the options carry colours — the page colours
            // the distributions ask for. A list of departments draws none.
            val swatch: (@Composable (ForwardChoice) -> Unit)? =
                if (field.options.none { it.swatch != null }) {
                    null
                } else {
                    { option -> Swatch(option.swatch) }
                }
            ZillitSelect(
                value = chosen,
                options = field.options,
                onSelect = { option -> onValue(option.value) },
                label = { it.label },
                enabled = enabled,
                showInitials = false,
                optionLeading = swatch,
                fieldLeading = swatch,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One option's colour, as the dot the distributions' pickers wear. */
@Composable
private fun Swatch(hex: String?) {
    Box(
        Modifier
            .size(SWATCH)
            .clip(CircleShape)
            .background(hex.orEmpty().asColour(ZillitTheme.colors.border)),
    )
}

/** `#RRGGBB` as a colour, or [fallback] when the string is not one. */
private fun String.asColour(fallback: Color): Color {
    val hex = trim().removePrefix("#")
    if (hex.length != HEX_LENGTH) return fallback
    val value = hex.toLongOrNull(radix = 16) ?: return fallback
    return Color(OPAQUE or value)
}

/** What a field starts with, and what an unanswered choice falls back to. */
private fun ForwardField.initial(): String = when (this) {
    is ForwardField.Text -> initial
    is ForwardField.Day -> initial
    is ForwardField.Choice -> initial.ifBlank { options.firstOrNull()?.value.orEmpty() }
}

private val FORM_WIDTH = 420.dp
private val SWATCH = 14.dp
private const val HEX_LENGTH = 6

/** `#RRGGBB` carries no alpha; every swatch is drawn solid. */
private const val OPAQUE = 0xFF000000L
