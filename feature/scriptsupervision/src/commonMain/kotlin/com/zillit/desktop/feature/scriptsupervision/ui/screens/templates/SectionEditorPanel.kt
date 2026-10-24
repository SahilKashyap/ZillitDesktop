package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateField
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.ui.ConfirmDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.SwitchInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * The section being edited, beside the preview — the web's
 * `SectionEditorPanel`.
 *
 * Its title (or none), a custom section's colour, and its fields. Changes
 * apply as they are made; the template itself is saved from the header, which
 * is why nothing here has a save button.
 */
@Composable
internal fun SectionEditorPanel(
    draft: TemplateDraft,
    section: TemplateSection,
    label: (String) -> String,
    onClose: () -> Unit,
) {
    var settingsOf by remember(section.key) { mutableStateOf<String?>(null) }
    var removing by remember(section.key) { mutableStateOf(false) }
    val required = Templates.sectionDef(section.key)?.required == true

    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        PanelHeader(section, required, onRemove = { removing = true }, onClose = onClose)
        TitleRow(section) { draft.update(it) }
        if (section.isCustom) ColourRow(section) { draft.update(it) }

        MutedText("${t("fields")} (${section.fields.size})")
        if (section.fields.isEmpty()) MutedText(t("no_fields"))
        section.fields.forEachIndexed { index, field ->
            FieldRow(
                section = section,
                field = field,
                index = index,
                onChange = { draft.update(section.copy(fields = section.fields.replaceAt(index, it))) },
                onMove = { by -> draft.update(section.copy(fields = Templates.moved(section.fields, index, by))) },
                onDelete = {
                    draft.update(section.copy(fields = section.fields.filterIndexed { i, _ -> i != index }))
                },
                onSettings = { settingsOf = field.key },
            )
        }
        AddFieldRow(
            onAddField = {
                draft.update(section.copy(fields = section.fields + draft.blankField("")))
            },
            onAddGroup = { presetKey ->
                val keys = draft.keyAllocator()
                val preset = Templates.FIELD_GROUP_PRESETS.first { it.key == presetKey }
                draft.update(
                    section.copy(
                        fields = section.fields + preset.fields.map { it.toField(label).copy(key = keys("f")) },
                    ),
                )
            },
        )
    }

    PanelDialogs(
        draft = draft,
        section = section,
        label = label,
        settingsOf = settingsOf,
        removing = removing,
        onSettingsClosed = { settingsOf = null },
        onRemoveClosed = { removing = false },
    )
}

/** The field-settings form and the remove confirmation, neither open by default. */
@Composable
@Suppress("LongParameterList") // The panel's whole dialog state.
private fun PanelDialogs(
    draft: TemplateDraft,
    section: TemplateSection,
    label: (String) -> String,
    settingsOf: String?,
    removing: Boolean,
    onSettingsClosed: () -> Unit,
    onRemoveClosed: () -> Unit,
) {
    settingsOf?.let { key ->
        section.fields.firstOrNull { it.key == key }?.let { field ->
            FieldSettingsDialog(
                field = field,
                siblings = section.fields,
                onClose = onSettingsClosed,
                onSave = { changed ->
                    val index = section.fields.indexOfFirst { it.key == key }
                    draft.update(section.copy(fields = section.fields.replaceAt(index, changed)))
                    onSettingsClosed()
                },
            )
        }
    }
    if (removing) {
        ConfirmDialog(
            title = t("remove_section_title"),
            message = section.label,
            confirmLabel = t("remove"),
            danger = true,
            onClose = onRemoveClosed,
            onConfirm = {
                onRemoveClosed()
                draft.remove(section, label)
            },
        )
    }
}

/** What is being edited, and the two ways out of it. */
@Composable
private fun PanelHeader(section: TemplateSection, required: Boolean, onRemove: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MutedText(t("editing"))
            ZillitText(
                text = section.label.ifBlank { t("section") },
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
        // A required section cannot be removed — the service writes a take
        // against it whatever the template says.
        if (!required) {
            ZillitButton(
                text = t("remove"),
                onClick = onRemove,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Trash,
            )
        }
        ZillitIconButton(icon = ZillitIcons.Close, contentDescription = t("close"), onClick = onClose)
    }
}

/** The section's title, and whether the log shows it at all. */
@Composable
private fun TitleRow(section: TemplateSection, onChange: (TemplateSection) -> Unit) {
    TextInput(
        value = section.label,
        onChange = { onChange(section.copy(label = it)) },
        label = t("section_title"),
        modifier = FormWide,
        // The service refuses a section with no name, so the panel says so
        // before the save does.
        error = t("section_title_required").takeIf { section.label.isBlank() },
    )
    SwitchInput(
        value = section.hideTitle,
        onChange = { onChange(section.copy(hideTitle = it)) },
        label = t("hidden"),
        modifier = FormWide,
    )
}

/** A custom section's colour, from the swatches the designer offers. */
@Composable
private fun ColourRow(section: TemplateSection, onChange: (TemplateSection) -> Unit) {
    MutedText(t("color"))
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Templates.SECTION_SWATCHES.forEach { hex ->
            val fill = runCatching { Color(hex.removePrefix("#").toLong(HEX_RADIX) or OPAQUE) }.getOrNull()
                ?: return@forEach
            Box(
                Modifier
                    .width(SWATCH)
                    .height(SWATCH)
                    .clip(ZillitTheme.shapes.small)
                    .background(fill)
                    .clickable { onChange(section.copy(color = hex)) },
            ) {
                if (section.color == hex) {
                    ZillitText("✓", style = ZillitTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

/** One field: its name, its type, whether it is required, and its controls. */
@Composable
@Suppress("LongParameterList") // One lambda per control on the row.
private fun FieldRow(
    section: TemplateSection,
    field: TemplateField,
    index: Int,
    onChange: (TemplateField) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit,
) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        TextInput(
            value = field.label,
            onChange = { onChange(field.copy(label = it)) },
            label = t("field_name"),
            modifier = FormWide,
            error = t("field_name").takeIf { field.label.isBlank() },
        )
        PickInput(
            value = field.type,
            options = Templates.FIELD_TYPES.map { it to t("field_type_$it") },
            onChange = { onChange(field.changeType(it)) },
            label = t("field_type"),
            modifier = FormWide,
        )
        // A divider is a heading, not an input: it has nothing to require
        // and nothing to configure.
        if (field.type != "divider") {
            SwitchInput(
                value = field.required,
                onChange = { onChange(field.copy(required = it)) },
                label = t("required"),
                modifier = FormWide,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronUp,
                contentDescription = t("move_up"),
                onClick = { onMove(-1) },
                enabled = index > 0,
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronDown,
                contentDescription = t("move_down"),
                onClick = { onMove(1) },
                enabled = index < section.fields.lastIndex,
            )
            if (field.type != "divider") {
                ZillitButton(
                    text = t("field_settings"),
                    onClick = onSettings,
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.Settings,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = t("delete"),
                onClick = onDelete,
                tint = ZillitTheme.colors.danger,
            )
        }
    }
}

/** Adding one field, or a whole preset group of them. */
@Composable
private fun AddFieldRow(onAddField: () -> Unit, onAddGroup: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            text = t("add_field"),
            onClick = onAddField,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
        ZillitButton(
            text = t("add_group"),
            onClick = { open = !open },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Templates.FIELD_GROUP_PRESETS.forEach { preset ->
            ZillitButton(
                text = t("preset_${preset.key}"),
                onClick = { onAddGroup(preset.key); open = false },
                variant = ButtonVariant.Tertiary,
            )
        }
    }
}

/**
 * This field as another type.
 *
 * ⚠ Turning a field into a select gives it ONE option to start from, because
 * a choice field with no options is a control nobody can use and the service
 * stores it happily. Turning it away from a choice drops the options rather
 * than keeping them for a type that cannot show them.
 */
private fun TemplateField.changeType(type: String): TemplateField = copy(
    type = type,
    options = when {
        type == "select" || type == "multiselect" -> options.ifEmpty { listOf("1") }
        else -> emptyList()
    },
)

/** [index] replaced by [value]. */
private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, existing -> if (i == index) value else existing }

private const val OPAQUE = 0xFF000000L
private const val HEX_RADIX = 16
private val SWATCH = 24.dp
