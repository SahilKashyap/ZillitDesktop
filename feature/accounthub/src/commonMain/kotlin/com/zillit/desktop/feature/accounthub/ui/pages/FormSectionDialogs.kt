package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.DiscardIntent
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog

/** Adding and deleting a section, the reset confirmation, and the unsaved-changes question. */
@Composable
internal fun FormSectionDialogs(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    AddSectionDialog(config, onEvent)
    DeleteSectionDialog(config, onEvent)
    ResetTemplateDialog(config, onEvent)
    DiscardChangesDialog(config, onEvent)
}

/** "Add Section" — a name, and Enter adds it, as the web's input does. */
@Composable
private fun AddSectionDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val composer = config.composer
    val shown = rememberLatestNonNull(composer)
    val focus = remember { FocusRequester() }
    LaunchedEffect(composer != null) { if (composer != null) runCatching { focus.requestFocus() } }
    ZillitDialogShell(
        title = str(S.desktop_add_section),
        subtitle = shown?.afterKey?.let { key -> config.template.section(key)?.label?.let { "After $it" } }
            ?: str(S.desktop_hub_at_the_top_of_the_form),
        icon = ZillitIcons.Add,
        visible = composer != null,
        onDismiss = { onEvent(AccountHubEvent.DismissFormSection) },
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AccountHubEvent.DismissFormSection) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_add_section),
                onClick = { onEvent(AccountHubEvent.AddFormSection) },
                enabled = shown?.isReady == true,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_hub_enter_a_name_for_the_new_section),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = shown?.name.orEmpty(),
            onValueChange = { onEvent(AccountHubEvent.EditFormSectionName(it)) },
            placeholder = str(S.desktop_hub_e_g_additional_info),
            imeAction = ImeAction.Done,
            onImeAction = { onEvent(AccountHubEvent.AddFormSection) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/** The web's `ConfirmModal`, word for word. */
@Composable
private fun DeleteSectionDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val section = rememberLatestNonNull(config.removingSection)
    HubConfirmDialog(
        visible = config.removingSection != null,
        title = str(S.desktop_delete_section),
        message = "Are you sure you want to delete \"${section?.label.orEmpty()}\"? All fields in this section " +
            "will be removed. This action cannot be undone.",
        confirmLabel = str(S.delete),
        onConfirm = { onEvent(AccountHubEvent.ConfirmRemoveFormSection) },
        onDismiss = { onEvent(AccountHubEvent.DismissRemoveFormSection) },
    )
}

/**
 * Back to the module's defaults.
 *
 * Asked, where the web resets on the click: this is the one action on the page
 * that takes effect for everybody at once, without a save, and there is no
 * undo.
 */
@Composable
private fun ResetTemplateDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    HubConfirmDialog(
        visible = config.confirmingReset,
        title = str(S.desktop_reset_to_defaults_2),
        message = "The ${config.module.label} form goes back to the system defaults for everyone on this " +
            "production. Custom fields, removed fields and the order you set are all lost. This can't be undone.",
        confirmLabel = str(S.desktop_reset_to_defaults),
        loading = config.resetting,
        onConfirm = { onEvent(AccountHubEvent.ConfirmResetFormTemplate) },
        onDismiss = { onEvent(AccountHubEvent.DismissResetFormTemplate) },
    )
}

/** Unsaved edits, and what is about to replace them. */
@Composable
private fun DiscardChangesDialog(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val intent = rememberLatestNonNull(config.discard)
    val module = config.module.label
    HubConfirmDialog(
        visible = config.discard != null,
        title = str(S.desktop_discard_unsaved_changes),
        message = when (intent) {
            is DiscardIntent.Switch ->
                "Your changes to the $module form haven't been saved. Discard them and open " +
                    "${intent.module.label}?"
            else -> "Your changes to the $module form haven't been saved. Discard them and go back to the saved form?"
        },
        confirmLabel = str(S.ah_discard),
        cancelLabel = str(S.ah_keep_editing),
        onConfirm = { onEvent(AccountHubEvent.ConfirmDiscardFormChanges) },
        onDismiss = { onEvent(AccountHubEvent.DismissDiscardFormChanges) },
    )
}

private val DIALOG_WIDTH = 440.dp
