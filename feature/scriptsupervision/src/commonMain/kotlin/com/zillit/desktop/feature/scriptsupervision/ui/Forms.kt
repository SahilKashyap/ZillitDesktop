package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTokenField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The form parts every dialog in this module uses, so a field behaves the same
 * in a sound report and in a quick log.
 *
 * The web builds these out of one antd `Form` per screen; here each screen
 * holds its own fields as state, which is also how the module's sibling
 * (Costumes & Set Sync) is written.
 */

/** A dialog with a title, a scrolling body, and Cancel / [confirmLabel] under it. */
@Composable
fun FormDialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    danger: Boolean = false,
    width: Dp = DIALOG_WIDTH,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitDialogShell(
        title = title,
        onDismiss = onDismiss,
        visible = open,
        width = width,
        actions = {
            ZillitButton(text = t("cancel"), onClick = onDismiss, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled && !busy,
                loading = busy,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
            )
        },
        content = content,
    )
}

/** The fields of a form, wrapping onto as many rows as the dialog is wide. */
@Composable
fun FormGrid(content: @Composable FlowRowScopeShim.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        FlowRowScopeShim.content()
    }
}

/**
 * The receiver [FormGrid]'s content gets.
 *
 * An object rather than Compose's own `FlowRowScope`, which is experimental:
 * the fields inside a grid need no scope of their own (each carries its own
 * width), and taking the real one would tie every screen to that annotation.
 */
object FlowRowScopeShim

/** A field's own width inside a [FormGrid]: one cell, half a cell, or the whole row. */
val FormCell: Modifier = Modifier.width(240.dp)
val FormHalf: Modifier = Modifier.width(116.dp)
val FormWide: Modifier = Modifier.fillMaxWidth()

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = FormCell,
    placeholder: String? = null,
    help: String? = null,
    multiline: Boolean = false,
    number: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
) {
    ZillitTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        helperText = help,
        errorText = error,
        singleLine = !multiline,
        enabled = enabled,
        keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text,
    )
}

/** A pick-one over `value to label` pairs. */
@Composable
fun PickInput(
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = FormCell,
    placeholder: String = "",
    clearable: Boolean = false,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        label?.let { ZillitText(it, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary) }
        ZillitSearchSelect(
            value = options.firstOrNull { it.first == value },
            options = options,
            onSelect = { onChange(it.first) },
            label = { it.second },
            placeholder = placeholder,
            enabled = enabled,
            onClear = { onChange("") }.takeIf { clearable && value.isNotBlank() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A list of short values typed one after another — the web's
 * `<Select mode="tags" tokenSeparators={[',']} open={false}>`, which is a
 * tag box with no menu behind it: a character's aliases, an actor's allergies,
 * a story day's scene numbers.
 *
 * `open={false}` is the web saying "these are typed, not chosen", so this is a
 * token field rather than a picker. A comma ends a token here as it does
 * there.
 */
@Composable
fun TagInput(
    tokens: List<String>,
    onChange: (List<String>) -> Unit,
    label: String?,
    modifier: Modifier = FormCell,
    placeholder: String? = null,
    help: String? = null,
) {
    var typed by remember { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        label?.let { ZillitText(it, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary) }
        ZillitTokenField(
            tokens = tokens,
            input = typed,
            onValueChange = { next, input ->
                // A comma in the input is a finished token, as the web's
                // tokenSeparators makes it.
                val cut = input.split(',')
                if (cut.size > 1) {
                    val finished = cut.dropLast(1).map { it.trim() }.filter { it.isNotBlank() }
                    onChange((next + finished).distinct())
                    typed = cut.last()
                } else {
                    if (next != tokens) onChange(next)
                    typed = input
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
        )
        help?.let { ZillitText(it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary) }
    }
}

/**
 * A pick-MANY over `value to label` pairs — the web's
 * `<Select mode="multiple">`: a look's story days and scenes, a photo's
 * characters.
 *
 * The value order is the order they were picked in, which is the order the
 * service stores and the web shows them in.
 */
@Composable
fun MultiPickInput(
    values: List<String>,
    options: List<Pair<String, String>>,
    onChange: (List<String>) -> Unit,
    label: String?,
    modifier: Modifier = FormCell,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        label?.let { ZillitText(it, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary) }
        ZillitMultiSelect(
            // Only the options that still EXIST: a look keeps the id of a
            // scene somebody deleted, and offering it back would re-save it.
            selected = options.filter { it.first in values },
            options = options,
            label = { it.second },
            onChange = { picked -> onChange(picked.map { it.first }) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder ?: (str(S.select) + "…"),
            enabled = enabled,
        )
    }
}

/** A yes/no field, as the web's `Switch` is. */
@Composable
fun SwitchInput(value: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = FormCell) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSwitch(checked = value, onCheckedChange = onChange)
        ZillitText(label, style = ZillitTheme.typography.bodyMedium)
    }
}

/**
 * A JSON body from `key to value` pairs, dropping nothing.
 *
 * The service distinguishes "" (cleared) from an absent key, so a form that
 * means to clear a field has to send the empty value — see [dropEmpty] for the
 * one case that must not.
 */
fun body(vararg fields: Pair<String, Any?>): JsonObject = body(fields.toList())

/** [body] from a list — what a form built from a spec has, with no array to copy. */
fun body(fields: List<Pair<String, Any?>>): JsonObject = buildJsonObject {
    fields.forEach { (key, value) -> put(key, value.toJson()) }
}

/**
 * The web's `dropEmpty`, for a CREATE only.
 *
 * The service's create validator refuses null and refuses "" for a pick-list
 * field, so a field nobody filled is left out of a create entirely — while an
 * edit must send "" to clear what is already stored.
 */
fun JsonObject.dropEmpty(): JsonObject = buildJsonObject {
    this@dropEmpty.forEach { (key, value) ->
        val blank = value is JsonNull || (value as? JsonPrimitive)?.takeIf { it.isString }?.content == ""
        if (!blank) put(key, value)
    }
}

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    // A list field is a JSON ARRAY, never its own `toString`: aliases,
    // allergies and scene numbers all travel this way, and `[a, b]` as a
    // string is what the service would otherwise store for them.
    is List<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}

private val DIALOG_WIDTH = 640.dp
