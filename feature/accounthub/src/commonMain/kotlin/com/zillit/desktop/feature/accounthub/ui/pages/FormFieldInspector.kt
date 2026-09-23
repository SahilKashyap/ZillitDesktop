package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.SelectionType
import com.zillit.desktop.core.forms.fieldLabelFor
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.NewFieldDraft
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect

/**
 * The property panel — the web's right-hand panel for the field in hand, or
 * for a new one.
 *
 * One panel for both, because adding a field and editing one are the same
 * decisions in the same order: a name, a type, where a select draws its
 * options from, and whether it is required.
 */
@Composable
internal fun FormFieldInspector(
    state: AccountHubUiState,
    config: FormConfigState,
    onEvent: (AccountHubEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = config.focus ?: return
    val section = config.template.section(focus.sectionKey) ?: return
    if (focus.isNew) {
        NewFieldPanel(config, section, onEvent, modifier)
    } else {
        val field = section.fields.firstOrNull { it.id == focus.fieldId } ?: return
        FieldPanel(state, config, section, field, onEvent, modifier)
    }
}

/** An existing field: its badges and Remove, its name, type, source and whether it is required. */
@Suppress("LongMethod") // One panel, read top to bottom; the order is the reading order.
@Composable
private fun FieldPanel(
    state: AccountHubUiState,
    config: FormConfigState,
    section: FormSection,
    field: FormField,
    onEvent: (AccountHubEvent) -> Unit,
    modifier: Modifier,
) {
    val system = field.systemDefault
    SidePanel(
        title = field.name.ifBlank { str(S.desktop_untitled_field) },
        subtitle = "${section.label} — Field #${field.order}",
        onClose = { onEvent(AccountHubEvent.DismissFormField) },
        modifier = modifier,
    ) {
        PanelBody {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs + 2.dp),
            ) {
                FormBadge(
                    if (system) str(S.desktop_language_system_short) else str(S.custom),
                    if (system) BadgeTone.System else BadgeTone.Custom,
                )
                FormBadge(field.typeBadge, BadgeTone.Type)
                Box(Modifier.weight(1f))
                // One Remove for both kinds, and what it does differs by origin:
                // a custom field is deleted, a system field only leaves this form.
                ZillitTooltip(
                    if (system) {
                        str(S.desktop_hub_take_this_system_field_off_the_form_restore_it_from)
                    } else {
                        str(S.desktop_hub_delete_this_custom_field)
                    },
                ) {
                    RemoveChip(
                        text = str(S.remove),
                        description = if (system) "Take ${field.name} off the form" else "Delete ${field.name}",
                    ) { onEvent(AccountHubEvent.RemoveFormField(section.key, field.id)) }
                }
            }

            PanelField(str(S.desktop_field_name)) {
                ZillitTextField(
                    value = field.name,
                    onValueChange = { onEvent(AccountHubEvent.SetFormFieldName(section.key, field.id, it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyHint(field.label)
            }

            PanelField(str(S.type)) {
                TypeSelect(field.type, enabled = !system) {
                    onEvent(AccountHubEvent.SetFormFieldType(section.key, field.id, it))
                }
                // The module's schema owns a system field's type: changing it
                // would have the form send a date where a number is expected.
                if (system) FieldHint(str(S.desktop_hub_a_system_fields_type_is_part_of_the_module))
            }

            if (!system && field.type == FormFieldType.Select.wire) {
                PanelField(str(S.desktop_selection_type)) {
                    SelectionSelect(field.selectionType) {
                        onEvent(AccountHubEvent.SetFormFieldSelection(section.key, field.id, it))
                    }
                }
            }

            RequiredRow(field.required) {
                onEvent(AccountHubEvent.SetFormFieldRequired(section.key, field.id, it))
            }

            if (!system) MoveToSection(state, config, section, field, onEvent)
        }
    }
}

/**
 * A custom field's section. Only a custom field moves: the module's forms
 * look for their own fields in the section the module put them in.
 */
@Composable
private fun MoveToSection(
    state: AccountHubUiState,
    config: FormConfigState,
    section: FormSection,
    field: FormField,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val sections = config.template.configurable
    if (sections.size < 2 || !state.viewer.canEdit) return
    PanelField(str(S.desktop_section)) {
        HubSelect(
            value = section,
            options = sections,
            label = { it.label },
            onSelect = { picked ->
                if (picked != null && picked.key != section.key) {
                    onEvent(AccountHubEvent.MoveFormFieldToSection(section.key, field.id, picked.key))
                }
            },
            searchable = false,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldHint(str(S.desktop_hub_moves_this_field_to_the_end_of_that_section))
    }
}

/** A new field: name, type, source, required, Add — and the system fields this section can have back. */
@Composable
private fun NewFieldPanel(
    config: FormConfigState,
    section: FormSection,
    onEvent: (AccountHubEvent) -> Unit,
    modifier: Modifier,
) {
    val draft = config.draft
    val edit = { next: NewFieldDraft -> onEvent(AccountHubEvent.EditNewFormField(next)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(section.key) { runCatching { focus.requestFocus() } }
    SidePanel(
        title = if (section.isLineItems) str(S.desktop_new_column) else str(S.desktop_new_field),
        subtitle = section.label,
        onClose = { onEvent(AccountHubEvent.DismissFormField) },
        modifier = modifier,
    ) {
        PanelBody {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs + 2.dp)) {
                FormBadge(str(S.custom), BadgeTone.Custom)
                FormBadge(draft.type, BadgeTone.Type)
            }

            PanelField(str(S.desktop_field_name)) {
                ZillitTextField(
                    value = draft.name,
                    onValueChange = { edit(draft.copy(name = it)) },
                    placeholder = str(S.desktop_enter_field_name),
                    imeAction = ImeAction.Done,
                    onImeAction = { onEvent(AccountHubEvent.AddFormField) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                // The key is derived from the name and cannot be typed: it is
                // what the form stores the value under.
                KeyHint(if (draft.name.isBlank()) "…" else fieldLabelFor(draft.name))
            }

            PanelField(str(S.type)) {
                TypeSelect(draft.type, enabled = true) { edit(draft.copy(type = it)) }
            }

            if (draft.type == FormFieldType.Select.wire) {
                PanelField(str(S.desktop_selection_type)) {
                    SelectionSelect(draft.selectionType) { edit(draft.copy(selectionType = it)) }
                }
            }

            RequiredRow(draft.required) { edit(draft.copy(required = it)) }

            ZillitButton(
                text = if (section.isLineItems) str(S.desktop_add_column) else str(S.add_field),
                onClick = { onEvent(AccountHubEvent.AddFormField) },
                enabled = draft.isReady,
                leadingIcon = ZillitIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )

            SystemFields(section, config.systemFieldsOpen, onEvent)
        }
    }
}

/**
 * "System Fields (N)" — the module's own fields taken off this section, each
 * with a "+" that puts it back.
 *
 * Here, in the add-a-field panel, because restoring a system field and adding
 * a custom one are the same job — put a field on this form — and scoped to
 * this section, because a field only goes back where the module reads it.
 * Absent when the section has nothing removed, so it never lists nothing.
 */
@Composable
private fun SystemFields(section: FormSection, open: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val removed = section.removed
    if (removed.isEmpty()) return
    val turn by animateFloatAsState(if (open) HALF_TURN else 0f, label = "systemFieldsChevron")
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.small)
                .clickable { onEvent(AccountHubEvent.ToggleSystemFields(!open)) }
                .padding(vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelLabel("System Fields (${removed.size})")
            Box(Modifier.weight(1f))
            ZillitIcon(
                icon = ZillitIcons.ChevronDown,
                tint = colors.textMuted,
                size = 12.dp,
                modifier = Modifier.rotate(turn),
            )
        }
        if (open) {
            removed.forEach { field -> RemovedFieldRow(section, field, onEvent) }
        }
    }
}

@Composable
private fun RemovedFieldRow(section: FormSection, field: FormField, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .padding(start = ZillitTheme.spacing.sm + 2.dp, end = ZillitTheme.spacing.xs + 2.dp)
            .padding(vertical = ZillitTheme.spacing.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = field.name,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        FormBadge(field.typeBadge, BadgeTone.Type)
        ZillitTooltip("Add ${field.name} back to this form") {
            Box(
                modifier = Modifier
                    .size(RESTORE_BUTTON)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent)
                    .clickable(onClickLabel = "Add ${field.name} back to this form") {
                        onEvent(AccountHubEvent.RestoreFormField(section.key, field.id))
                    },
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    icon = ZillitIcons.Add,
                    contentDescription = "Add ${field.name} back to this form",
                    tint = colors.textOnAccent,
                    size = 11.dp,
                )
            }
        }
    }
}

// -- pieces ---------------------------------------------------------------------------------

@Composable
private fun ColumnScope.PanelBody(content: @Composable ColumnScope.() -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        contentPadding = PaddingValues(
            horizontal = ZillitTheme.spacing.lg + ZillitTheme.spacing.xs,
            vertical = ZillitTheme.spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

@Composable
private fun PanelField(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs + 2.dp)) {
        PanelLabel(label)
        content()
    }
}

/** "Saved as budget_code" — the key a field's value is stored under. */
@Composable
private fun KeyHint(key: String) {
    ZillitText(
        text = "Saved as $key",
        style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/**
 * The type picker. The field's own type is offered first when the picker
 * would not offer it — Petty Cash has `textarea` system fields — so it is
 * shown as it is rather than read as the first entry.
 */
@Composable
private fun TypeSelect(type: String, enabled: Boolean, onPick: (String) -> Unit) {
    HubSelect(
        value = type,
        options = (listOf(type) + FormFieldType.entries.map { it.wire }).distinct(),
        label = { wire -> FormFieldType.from(wire)?.label ?: wire.replaceFirstChar(Char::uppercase) },
        onSelect = { picked -> if (picked != null && picked != type) onPick(picked) },
        enabled = enabled,
        searchable = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SelectionSelect(selectionType: String?, onPick: (String?) -> Unit) {
    HubSelect(
        value = SelectionType.from(selectionType),
        options = SelectionType.entries,
        label = { it.label },
        onSelect = { onPick(it?.wire) },
        placeholder = str(S.desktop_choose_selection_type),
        clearable = true,
        searchable = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RequiredRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.docusign_prop_required),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
        )
        ZillitSwitch(checked = checked, onCheckedChange = onChange)
    }
}

private const val HALF_TURN = 180f
private val RESTORE_BUTTON = 22.dp
