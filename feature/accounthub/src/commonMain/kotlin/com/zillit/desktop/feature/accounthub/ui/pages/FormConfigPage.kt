package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.HubPage

/**
 * Which fields a module's form shows, in what order, and which are required.
 *
 * ## A system field is taken off the form, never deleted
 *
 * The module's schema owns it. Removing one sets it hidden, and the add-a-field
 * panel offers it back; a custom field is deleted outright. One button, two
 * meanings, and the difference is the whole design.
 *
 * ## Nothing is addressed by position
 *
 * Every surface here filters something — hidden fields, the terms section — so
 * an index taken from what is on screen names a different row in the document.
 * Sections go by key and fields by their form key.
 */
@Composable
fun FormConfigPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig

    HubPage {
        Header(config, onEvent)
        ZillitTabStrip(
            tabs = FormModule.entries.map { ZillitTab(it.wire, it.label) },
            activeId = config.module.wire,
            onSelect = { wire ->
                FormModule.from(wire)?.let { onEvent(AccountHubEvent.OpenFormModule(it)) }
            },
        )

        when {
            config.loading -> ZillitSpinner()
            config.template.sections.isEmpty() -> ZillitEmptyState(
                title = "No form for ${config.module.label}",
                message = "The server builds a module's form from its own defaults the first " +
                    "time this page is opened. Nothing came back for this one.",
                icon = ZillitIcons.File,
            )

            else -> Body(config, onEvent)
        }
    }

    FormFieldInspector(config, onEvent)
    FormSectionDialogs(config, onEvent)
}

@Composable
private fun ColumnScope.Header(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    ZillitPageHeader(
        eyebrow = "Configuration",
        title = "Forms Configuration",
        description = "Fields and sections shown on a module's form.",
        actions = {
            if (config.editing) {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(AccountHubEvent.EditForm(false)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = "Save form",
                    onClick = { onEvent(AccountHubEvent.SaveFormTemplate) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Tick,
                    loading = config.saving,
                    enabled = config.dirty,
                )
            } else {
                ZillitButton(
                    text = "Reset to defaults",
                    onClick = { onEvent(AccountHubEvent.AskResetFormTemplate) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = config.saving,
                )
                ZillitButton(
                    text = "Edit",
                    onClick = { onEvent(AccountHubEvent.EditForm(true)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        },
    )
}

@Composable
private fun ColumnScope.Body(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitStatTile(
            label = "Sections",
            value = config.sectionCount.toString(),
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Fields",
            value = config.fieldCount.toString(),
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Custom",
            value = config.customCount.toString(),
            sub = "Added by this production",
            modifier = Modifier.weight(1f),
        )
    }

    if (config.dirty) {
        ZillitNotice(
            text = "Unsaved changes. Nobody else sees them until this form is saved.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(bottom = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (config.editing) {
            AddSectionRow(afterKey = null, label = "Add a section at the top", onEvent = onEvent)
        }
        config.template.configurable.forEach { section ->
            SectionCard(section, config, onEvent)
            if (config.editing) {
                AddSectionRow(
                    afterKey = section.key,
                    label = "Add a section after ${section.label}",
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun AddSectionRow(afterKey: String?, label: String, onEvent: (AccountHubEvent) -> Unit) {
    ZillitButton(
        text = label,
        onClick = { onEvent(AccountHubEvent.ComposeFormSection(afterKey)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Add,
    )
}

@Composable
private fun SectionCard(
    section: FormSection,
    config: FormConfigState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val collapsed = config.isCollapsed(section.key)
    ZillitSectionCard(
        title = section.label,
        meta = "${section.visible.size} of ${section.fields.size} shown",
        action = { SectionActions(section, config, onEvent) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (collapsed) return@ZillitSectionCard

        if (section.visible.isEmpty()) {
            ZillitText(
                text = "No fields on the form in this section.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        section.visible.forEach { field ->
            FieldRow(section, field, config, onEvent)
        }

        if (config.editing) {
            RemovedFields(section, onEvent)
            ZillitButton(
                text = "Add a field",
                onClick = { onEvent(AccountHubEvent.FocusFormField(section.key, null)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

@Composable
private fun SectionActions(
    section: FormSection,
    config: FormConfigState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config.editing) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronUp,
                contentDescription = "Move ${section.label} up",
                onClick = { onEvent(AccountHubEvent.NudgeFormSection(section.key, -1)) },
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronDown,
                contentDescription = "Move ${section.label} down",
                onClick = { onEvent(AccountHubEvent.NudgeFormSection(section.key, 1)) },
            )
            // A system section belongs to the module's schema: it can be
            // reordered and emptied, but not renamed away or deleted.
            if (!section.systemDefault) {
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Rename ${section.label}",
                    onClick = {
                        onEvent(AccountHubEvent.RenameFormSection(section.key, section.label))
                    },
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove ${section.label}",
                    onClick = { onEvent(AccountHubEvent.AskRemoveFormSection(section)) },
                )
            }
        }
        ZillitIconButton(
            icon = if (config.isCollapsed(section.key)) ZillitIcons.ChevronDown else ZillitIcons.ChevronUp,
            contentDescription = "Collapse ${section.label}",
            onClick = { onEvent(AccountHubEvent.ToggleFormSection(section.key)) },
        )
    }
}

@Composable
private fun FieldRow(
    section: FormSection,
    field: FormField,
    config: FormConfigState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = field.name, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = field.label,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitStatusPill(label = field.typeLabel, tone = StatusTone.Progress)
        if (field.required) {
            ZillitStatusPill(label = "Required", tone = StatusTone.Rejected)
        }
        ZillitStatusPill(
            label = if (field.systemDefault) "System" else "Custom",
            tone = if (field.systemDefault) StatusTone.Pending else StatusTone.Neutral,
        )

        if (config.editing) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronUp,
                contentDescription = "Move ${field.name} up",
                onClick = { onEvent(AccountHubEvent.NudgeFormField(section.key, field.id, -1)) },
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronDown,
                contentDescription = "Move ${field.name} down",
                onClick = { onEvent(AccountHubEvent.NudgeFormField(section.key, field.id, 1)) },
            )
            ZillitIconButton(
                icon = ZillitIcons.Settings,
                contentDescription = "Configure ${field.name}",
                onClick = { onEvent(AccountHubEvent.FocusFormField(section.key, field.id)) },
            )
        }
    }
}

/**
 * The system fields taken off this section.
 *
 * Listed under the section they belong to rather than in one pile, because a
 * field only goes back where it came from.
 */
@Composable
private fun ColumnScope.RemovedFields(section: FormSection, onEvent: (AccountHubEvent) -> Unit) {
    val removed = section.removed
    if (removed.isEmpty()) return

    ZillitText(
        text = "Off the form (${removed.size})",
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
    )
    removed.forEach { field ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitText(
                text = field.name,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(label = field.typeLabel, tone = StatusTone.Neutral)
            ZillitButton(
                text = "Put back",
                onClick = { onEvent(AccountHubEvent.RestoreFormField(section.key, field.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}
