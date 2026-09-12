package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.SelectionType
import com.zillit.desktop.core.forms.fieldLabelFor
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.NewFieldDraft

private const val NO_SELECTION = ""

/**
 * One field, added or inspected.
 *
 * The same panel for both, because adding a field and editing one are the same
 * decisions in the same order.
 */
@Composable
fun FormFieldInspector(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val focus = config.focus
    val section = config.focusedSection
    val field = config.focusedField

    ZillitDialogShell(
        title =
            if (focus?.isNew == true) (if (section?.key == "line_items") "Add Custom Column" else "Add Custom Field")
            else field?.name
            .orEmpty()
            .ifBlank { "Field" },
        subtitle = section?.label,
        icon = ZillitIcons.File,
        visible = focus != null && section != null,
        onDismiss = { onEvent(AccountHubEvent.DismissFormField) },
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(AccountHubEvent.DismissFormField) },
                variant = ButtonVariant.Tertiary,
            )
            if (focus?.isNew == true) {
                ZillitButton(
                    text = "Add",
                    onClick = { onEvent(AccountHubEvent.AddFormField) },
                    enabled = config.draft.isReady,
                )
            }
        },
    ) {
        if (section == null) return@ZillitDialogShell
        if (focus?.isNew == true) {
            NewFieldForm(config.draft, section, onEvent)
        } else if (field != null) {
            ExistingFieldForm(field, section, config, onEvent)
        }
    }
}

@Composable
private fun ColumnScope.NewFieldForm(
    draft: NewFieldDraft,
    section: FormSection,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitTextField(
        value = draft.name,
        onValueChange = { onEvent(AccountHubEvent.EditNewFormField(draft.copy(name = it))) },
        label = "Field name",
        placeholder = "Enter field name...",
        // The key is derived from the name and cannot be typed: it is what the
        // form stores the value under, and a person editing it by hand would
        // be renaming a column.
        helperText = "Stored as ${fieldLabelFor(draft.name).takeIf { draft.name.isNotBlank() } ?: "…"}",
        modifier = Modifier.fillMaxWidth(),
    )

    TypePicker(draft.type) { onEvent(AccountHubEvent.EditNewFormField(draft.copy(type = it))) }

    if (draft.type == FormFieldType.Select.wire) {
        SelectionPicker(draft.selectionType) {
            onEvent(AccountHubEvent.EditNewFormField(draft.copy(selectionType = it)))
        }
    }

    ZillitCheckbox(
        checked = draft.required,
        onCheckedChange = { onEvent(AccountHubEvent.EditNewFormField(draft.copy(required = it))) },
        label = "Required",
    )

    RemovedFieldsHint(section, onEvent)
}

@Composable
private fun ColumnScope.ExistingFieldForm(
    field: FormField,
    section: FormSection,
    config: FormConfigState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitStatusPill(
            label = if (field.systemDefault) "System" else "Custom",
            tone = if (field.systemDefault) StatusTone.Pending else StatusTone.Neutral,
        )
        ZillitStatusPill(label = field.typeLabel, tone = StatusTone.Progress)
    }

    ZillitTextField(
        value = field.name,
        onValueChange = {
            onEvent(AccountHubEvent.SetFormFieldName(section.key, field.id, it))
        },
        label = "Field name",
        helperText = "Stored as ${field.label}",
        modifier = Modifier.fillMaxWidth(),
    )

    // A system field's type belongs to the module's schema. Changing it would
    // have the form send a date where the module expects a number, so it is
    // shown and not offered.
    if (field.systemDefault) {
        ZillitText(
            text = "Type: ${field.typeLabel}. A system field's type is part of the module and " +
                "cannot be changed here.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    } else {
        TypePicker(field.type) { onEvent(AccountHubEvent.SetFormFieldType(section.key, field.id, it)) }
        if (field.type == FormFieldType.Select.wire) {
            SelectionPicker(field.selectionType) {
                onEvent(AccountHubEvent.SetFormFieldSelection(section.key, field.id, it))
            }
        }
    }

    ZillitCheckbox(
        checked = field.required,
        onCheckedChange = {
            onEvent(AccountHubEvent.SetFormFieldRequired(section.key, field.id, it))
        },
        label = "Required",
    )

    MoveToSection(field, section, config, onEvent)

    ZillitNotice(
        text = if (field.systemDefault) {
            "Removing a system field takes it off this form. It stays part of the module and " +
                "comes back from the section's “off the form” list."
        } else {
            "Removing a custom field deletes it. Anything already submitted keeps the value; " +
                "the box stops being offered."
        },
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitButton(
        text = if (field.systemDefault) "Take off the form" else "Delete this custom field",
        onClick = { onEvent(AccountHubEvent.RemoveFormField(section.key, field.id)) },
        variant = ButtonVariant.Danger,
        leadingIcon = ZillitIcons.Trash,
    )
}

@Composable
private fun ColumnScope.MoveToSection(
    field: FormField,
    section: FormSection,
    config: FormConfigState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val others = config.template.configurable.filterNot { it.key == section.key }
    if (others.isEmpty()) return

    ZillitText(text = "Move to another section", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = section.key,
        options = listOf(section.key) + others.map { it.key },
        onSelect = { key ->
            if (key != section.key) {
                onEvent(AccountHubEvent.MoveFormFieldToSection(section.key, field.id, key))
            }
        },
        label = { key -> config.template.section(key)?.label ?: key },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * "System Fields" — the module's own fields taken off this section, each with
 * a Restore. Listed under the section they belong to rather than in one pile,
 * because a field only goes back where it came from.
 */
@Composable
private fun ColumnScope.RemovedFieldsHint(section: FormSection, onEvent: (AccountHubEvent) -> Unit) {
    val removed = section.removed
    if (removed.isEmpty()) return
    ZillitText(text = "System Fields", style = ZillitTheme.typography.label)
    ZillitText(
        text = "${removed.size} system field(s) are off this section. Restore one rather than adding a duplicate.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    removed.forEach { field ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitText(text = field.name, style = ZillitTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            ZillitStatusPill(label = field.typeLabel, tone = StatusTone.Neutral)
            ZillitButton(
                text = "Restore",
                onClick = { onEvent(AccountHubEvent.RestoreFormField(section.key, field.id)) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

@Composable
private fun ColumnScope.TypePicker(type: String, onPick: (String) -> Unit) {
    ZillitText(text = "Type", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = type,
        // The field's own type first, so a server type this picker does not
        // offer — Petty Cash has `textarea` — is shown rather than silently
        // read as the first entry.
        options = (listOf(type) + FormFieldType.entries.map { it.wire }).distinct(),
        onSelect = onPick,
        label = { wire -> FormFieldType.from(wire)?.label ?: wire },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.SelectionPicker(selectionType: String?, onPick: (String?) -> Unit) {
    ZillitText(text = "Options come from", style = ZillitTheme.typography.label)
    ZillitSelect(
        value = selectionType ?: NO_SELECTION,
        options = listOf(NO_SELECTION) + SelectionType.entries.map { it.wire },
        onSelect = { wire -> onPick(wire.takeIf { it != NO_SELECTION }) },
        label = { wire ->
            SelectionType.from(wire)?.label ?: "Choose a source…"
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
