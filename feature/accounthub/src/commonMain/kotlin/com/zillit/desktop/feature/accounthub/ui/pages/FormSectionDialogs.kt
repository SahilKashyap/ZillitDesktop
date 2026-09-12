package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.FormConfigState

/** Adding, renaming and removing a section, and the reset confirmation. */
@Composable
fun FormSectionDialogs(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    AddSectionDialog(config, onEvent)
    RenameSectionDialog(config, onEvent)
    RemoveSectionDialog(config, onEvent)
    ResetTemplateDialog(config, onEvent)
}

@Composable
private fun AddSectionDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    ZillitDialogShell(
        title = "Add Section",
        subtitle = config.addingSectionAfter?.let { key ->
            config.template.section(key)?.label?.let { "After $it" }
        } ?: "At the top of the form",
        icon = ZillitIcons.Add,
        visible = config.addingSectionAfter != null || config.addingSectionName.isNotEmpty(),
        onDismiss = { onEvent(AccountHubEvent.DismissFormSection) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissFormSection) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Add section",
                onClick = { onEvent(AccountHubEvent.AddFormSection) },
                enabled = config.addingSectionName.isNotBlank(),
            )
        },
    ) {
        ZillitTextField(
            value = config.addingSectionName,
            onValueChange = { onEvent(AccountHubEvent.EditFormSectionName(it)) },
            label = "Section name",
            placeholder = "e.g. Additional Info",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RenameSectionDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    ZillitDialogShell(
        title = "Rename section",
        icon = ZillitIcons.Edit,
        visible = config.renamingSection != null,
        onDismiss = { onEvent(AccountHubEvent.DismissFormSectionRename) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissFormSectionRename) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Rename",
                onClick = { onEvent(AccountHubEvent.SaveFormSectionRename) },
                enabled = config.renamingSectionName.isNotBlank(),
            )
        },
    ) {
        ZillitTextField(
            value = config.renamingSectionName,
            onValueChange = { onEvent(AccountHubEvent.EditFormSectionRename(it)) },
            label = "Section name",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RemoveSectionDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val section = config.removingSection
    ZillitDialogShell(
        title = "Delete Section",
        icon = ZillitIcons.Warning,
        visible = section != null,
        onDismiss = { onEvent(AccountHubEvent.DismissRemoveFormSection) },
        actions = {
            ZillitButton(
                text = "Keep it",
                onClick = { onEvent(AccountHubEvent.DismissRemoveFormSection) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(AccountHubEvent.ConfirmRemoveFormSection) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = "Delete \"${section?.label.orEmpty()}\"? The ${section?.fields?.size ?: 0} field(s) in this " +
                "section go with it. " +
                "Move any you want to keep into another section first.",
            style = ZillitTheme.typography.bodyMedium,
        )
        ZillitNotice(
            text = "Nothing changes for anybody until the form is saved.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Back to the module's defaults.
 *
 * Confirmed and said plainly: this is the one action on the page that takes
 * effect for everybody immediately, without a save, and there is no undo.
 */
@Composable
private fun ResetTemplateDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    ZillitDialogShell(
        title = "Reset ${config.module.label} to the defaults?",
        icon = ZillitIcons.Warning,
        visible = config.confirmingReset,
        onDismiss = { onEvent(AccountHubEvent.DismissResetFormTemplate) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissResetFormTemplate) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Reset the form",
                onClick = { onEvent(AccountHubEvent.ConfirmResetFormTemplate) },
                variant = ButtonVariant.Danger,
                loading = config.saving,
            )
        },
    ) {
        ZillitText(
            text = "Every custom field this production added, every field taken off the form " +
                "and every reorder goes. The module's own defaults come back in their place.",
            style = ZillitTheme.typography.bodyMedium,
        )
        ZillitNotice(
            text = "This saves immediately and applies to everybody on the production.",
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Warning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
