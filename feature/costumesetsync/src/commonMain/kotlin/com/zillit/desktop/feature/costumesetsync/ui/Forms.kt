package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Form building blocks shared by every dialog in the tool, so a field looks
 * and behaves the same in the costume form, the fitting form and a ticket.
 * Prefer these to raw design-system components: a form that invents its own
 * label spacing or button row reads as a different tool.
 */

/** A dialog with a title, a scrolling body and Cancel / [confirmLabel] pinned under it. */
@Composable
fun FormDialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    danger: Boolean = false,
    subtitle: String? = null,
    width: Dp = 640.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    SyncDialogShell(
        title = title,
        subtitle = subtitle,
        visible = open,
        onDismiss = onDismiss,
        modifier = modifier,
        width = width,
        actions = {
            ZillitButton(t("csync_cancel"), onClick = onDismiss, variant = ButtonVariant.Secondary, enabled = !busy)
            ZillitButton(confirmLabel, onClick = onConfirm, variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary, enabled = confirmEnabled && !busy, loading = busy)
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), content = content)
    }
}

/** Fields in a wrapping two-up grid; give a field `Modifier.fillMaxWidth()`-style width with [FormCell] or [FormWide]. */
@Composable
fun FormGrid(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) { content() }
}

/** Half a dialog's width — two to a row in a [FormGrid]. */
val FormCell: Modifier = Modifier.width(290.dp)

/** A full row in a [FormGrid]. */
val FormWide: Modifier = Modifier.width(592.dp)

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = FormCell,
    placeholder: String? = null,
    help: String? = null,
    multiline: Boolean = false,
    number: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
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

/** A `YYYY-MM-DD` date field. */
@Composable
fun DateInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = FormCell) {
    ZillitDateField(value = value, onValueChange = onChange, label = label, modifier = modifier)
}

/** A label above a searchable pick-one list of (value, label) options; the empty value is "none". */
@Composable
fun PickInput(
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = FormCell,
    placeholder: String = "",
    help: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(label, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        ZillitSearchSelect(
            value = options.firstOrNull { it.first == value },
            options = options,
            onSelect = { onChange(it.first) },
            label = { it.second },
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
        help?.let { MutedText(it, maxLines = 2) }
    }
}

/** A pick-one over a `/meta` enum list, shown in the service's own words. */
@Composable
fun EnumInput(value: String, values: List<String>, onChange: (String) -> Unit, label: String, modifier: Modifier = FormCell, placeholder: String = "") {
    PickInput(value, enumOptions(values), onChange, label, modifier, placeholder)
}

/** A pick-one over records (characters, vendors, scenes…): [labelOf] names each row. */
@Composable
fun RecInput(
    value: String,
    rows: List<Rec>,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = FormCell,
    placeholder: String = "",
    labelOf: (Rec) -> String = { it.str("name") },
) {
    PickInput(value, rows.map { it.id to labelOf(it) }, onChange, label, modifier, placeholder)
}

/** Row of buttons at the end of a section. */
@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) { content() }
}

// -- request bodies --------------------------------------------------------

/**
 * A JSON body from pairs, the way the web builds one: a blank string drops the
 * key ("not set", as the reference sends it), `null` writes an explicit null
 * where the service wants one cleared (use [Clear]), numbers and booleans pass
 * through, a list becomes an array.
 */
fun body(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    fields.forEach { (key, value) ->
        when (value) {
            null -> Unit
            Clear -> put(key, JsonNull)
            is String -> if (value.isNotBlank()) put(key, JsonPrimitive(value))
            is Boolean -> put(key, JsonPrimitive(value))
            is Number -> put(key, JsonPrimitive(value))
            is JsonElement -> put(key, value)
            is Collection<*> -> put(key, JsonArray(value.map { it.toJson() }))
            else -> put(key, JsonPrimitive(value.toString()))
        }
    }
}

/** An explicit JSON null in a [body] — clears a field on the service. */
object Clear

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

/** A number field's text as a number, or null when blank or not a number (`num()` in the web forms). */
fun numOrNull(text: String): Double? = text.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

/**
 * [ZillitDialogShell] hosted in a window-sized popup. The shell is a plain
 * `Box(fillMaxSize)` with a scrim, so composed inside the tool's scrolling page
 * it measures against an infinite height and draws inline at the foot of the
 * page; a popup is bounded by the window, so it is a real overlay. Every dialog
 * in the tool goes through this, never `ZillitDialogShell` directly.
 */
@Composable
fun SyncDialogShell(
    title: String,
    onDismiss: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
    width: Dp = 640.dp,
    maxHeight: Dp = 720.dp,
    scrollable: Boolean = true,
    headerTrailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    androidx.compose.ui.window.Popup(
        alignment = androidx.compose.ui.Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        ZillitDialogShell(
            title = title,
            onDismiss = onDismiss,
            visible = true,
            modifier = modifier,
            icon = icon,
            subtitle = subtitle,
            width = width,
            maxHeight = maxHeight,
            scrollable = scrollable,
            headerTrailing = headerTrailing,
            actions = actions,
            content = content,
        )
    }
}
