package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Condition
import com.zillit.desktop.feature.scriptsupervision.domain.Formula
import com.zillit.desktop.feature.scriptsupervision.domain.Lookup
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateField
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.domain.Validation
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.MultiPickInput
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.serialization.json.JsonPrimitive

/**
 * A field's settings beyond its name, type and required flag — the web's
 * `FieldSettingsModal`.
 *
 * Its default, what it will accept, its options, when it shows, and how a
 * calculated or lookup field gets its value. Only the parts that mean
 * something for the field's TYPE are shown, which is what the web does: a
 * divider has no default and an image has no validation.
 */
@Composable
internal fun FieldSettingsDialog(
    field: TemplateField,
    siblings: List<TemplateField>,
    onClose: () -> Unit,
    onSave: (TemplateField) -> Unit,
) {
    var draft by remember(field.key) { mutableStateOf(field) }
    // A rule can only point at another field, and never at a divider.
    val others = siblings.filter { it.key != field.key && it.type != "divider" }
        .map { it.key to it.label.ifBlank { it.key } }

    FormDialog(
        open = true,
        title = "${t("field_settings")} · ${field.label}",
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = { onSave(draft) },
        width = DIALOG_WIDTH,
    ) {
        if (draft.isChoice) {
            TagInput(
                tokens = draft.options,
                onChange = { draft = draft.copy(options = it) },
                label = t("options"),
                modifier = FormWide,
            )
        }
        if (draft.type !in NO_DEFAULT) DefaultRow(draft) { draft = it }
        if (draft.type in VALIDATED) ValidationRows(draft) { draft = it }
        if (draft.type == "calculated") FormulaRows(draft, others) { draft = it }
        if (draft.type == "lookup") LookupRows(draft) { draft = it }
        ConditionRows(draft, others) { draft = it }
    }
}

/** What the field starts as, in the control its type asks for. */
@Composable
private fun DefaultRow(field: TemplateField, onChange: (TemplateField) -> Unit) {
    val current = field.defaultValue?.toString()?.trim('"').orEmpty()
    FormGrid {
        when {
            field.type == "switch" || field.type == "checkbox" -> PickInput(
                value = current,
                options = listOf("true" to t("req"), "false" to t("opt")),
                onChange = { onChange(field.copy(defaultValue = JsonPrimitive(it == "true"))) },
                label = t("default_value"),
            )
            field.isChoice -> PickInput(
                value = current,
                options = field.options.map { it to it },
                onChange = { onChange(field.copy(defaultValue = JsonPrimitive(it))) },
                label = t("default_value"),
                clearable = true,
            )
            else -> TextInput(
                value = current,
                onChange = { onChange(field.copy(defaultValue = JsonPrimitive(it))) },
                label = t("default_value"),
                number = field.type == "number",
            )
        }
    }
}

/** What the field will accept. */
@Composable
private fun ValidationRows(field: TemplateField, onChange: (TemplateField) -> Unit) {
    val rules = field.validation ?: Validation()
    val set = { next: Validation -> onChange(field.copy(validation = next)) }
    ZillitSectionLabel(t("validation"))
    FormGrid {
        if (field.type == "number") {
            TextInput(
                rules.min?.let(::plain).orEmpty(),
                { set(rules.copy(min = it.toDoubleOrNull())) },
                t("min"), modifier = FormHalf, number = true,
            )
            TextInput(
                rules.max?.let(::plain).orEmpty(),
                { set(rules.copy(max = it.toDoubleOrNull())) },
                t("max"), modifier = FormHalf, number = true,
            )
        } else {
            TextInput(
                rules.minLength?.toString().orEmpty(),
                { set(rules.copy(minLength = it.toIntOrNull())) },
                t("min_length"), modifier = FormHalf, number = true,
            )
            TextInput(
                rules.maxLength?.toString().orEmpty(),
                { set(rules.copy(maxLength = it.toIntOrNull())) },
                t("max_length"), modifier = FormHalf, number = true,
            )
            TextInput(rules.pattern, { set(rules.copy(pattern = it)) }, t("pattern"), modifier = FormWide)
        }
    }
}

/** How a calculated field works its value out. */
@Composable
private fun FormulaRows(
    field: TemplateField,
    others: List<Pair<String, String>>,
    onChange: (TemplateField) -> Unit,
) {
    val formula = field.formula ?: Formula("", emptyList())
    FormGrid {
        PickInput(
            value = formula.formulaType,
            options = Templates.FORMULA_TYPES.map { type -> type to t("formula_$type") },
            onChange = { onChange(field.copy(formula = formula.copy(formulaType = it))) },
            label = t("formula"),
        )
        MultiPickInput(
            values = formula.sourceFields,
            options = others,
            onChange = { onChange(field.copy(formula = formula.copy(sourceFields = it))) },
            label = t("source_fields"),
            modifier = FormWide,
        )
    }
}

/** Where a lookup field's choices come from. */
@Composable
private fun LookupRows(field: TemplateField, onChange: (TemplateField) -> Unit) {
    val lookup = field.lookup ?: Lookup("", "")
    FormGrid {
        PickInput(
            value = lookup.source,
            options = Templates.LOOKUP_SOURCES.map { it to t("lookup_$it") },
            onChange = { onChange(field.copy(lookup = lookup.copy(source = it))) },
            label = t("lookup_source"),
        )
        TextInput(
            value = lookup.displayField,
            onChange = { onChange(field.copy(lookup = lookup.copy(displayField = it))) },
            label = t("display_field"),
        )
    }
}

/**
 * When this field shows at all.
 *
 * ⚠ Every rule must name a field — the service refuses one that does not —
 * so a half-written rule is kept on screen to be finished and dropped on the
 * way to the wire rather than refused.
 */
@Composable
private fun ConditionRows(
    field: TemplateField,
    others: List<Pair<String, String>>,
    onChange: (TemplateField) -> Unit,
) {
    ZillitSectionLabel(t("show_when"))
    if (others.isEmpty()) {
        MutedText(t("no_fields"))
        return
    }
    val rules = field.conditions
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        rules.forEachIndexed { index, rule ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                PickInput(
                    value = rule.field,
                    options = others,
                    onChange = { onChange(field.withRule(index, rule.copy(field = it))) },
                    label = null,
                    modifier = FormHalf,
                )
                PickInput(
                    value = rule.operator,
                    options = Templates.CONDITION_OPERATORS.map { it to t("operator_$it") },
                    onChange = { onChange(field.withRule(index, rule.copy(operator = it))) },
                    label = null,
                    modifier = FormHalf,
                )
                // The two "is set" tests take no value to compare against.
                if (rule.operator == "equals" || rule.operator == "not_equals") {
                    TextInput(
                        value = rule.value,
                        onChange = { onChange(field.withRule(index, rule.copy(value = it))) },
                        label = null,
                        modifier = FormHalf,
                    )
                }
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = t("delete"),
                    onClick = {
                        onChange(field.copy(conditions = rules.filterIndexed { i, _ -> i != index }))
                    },
                    tint = ZillitTheme.colors.danger,
                )
            }
        }
        ZillitButton(
            text = t("add_rule"),
            onClick = {
                onChange(field.copy(conditions = rules + Condition(others.first().first, "equals", "")))
            },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

private fun TemplateField.withRule(index: Int, rule: Condition): TemplateField =
    copy(conditions = conditions.mapIndexed { i, existing -> if (i == index) rule else existing })

/** A whole number prints without its decimal point. */
private fun plain(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** Types with nothing sensible to start as. */
private val NO_DEFAULT = setOf("divider", "calculated", "image")

/** Types whose settings include what they will accept. */
private val VALIDATED = setOf("number", "text", "textarea")

private val DIALOG_WIDTH = 600.dp
