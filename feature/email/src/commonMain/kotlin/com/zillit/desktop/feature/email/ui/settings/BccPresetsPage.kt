package com.zillit.desktop.feature.email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.ui.DialogButtons
import com.zillit.desktop.feature.email.ui.ModalCard

/**
 * BCC Presets — Android `EmailPresetPage` (`mailing/views/EmailPresetPage.kt`):
 * addresses blind-copied on everything this mailbox sends.
 *
 * One page rather than Android's list-plus-dialog: there is a single field and
 * a row per address, and a dialog to type one line is a click for nothing on a
 * desktop.
 */
@Composable
internal fun BccPresetsPage(
    state: BccPresetsUiState,
    onEvent: (BccPresetsEvent) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = str(S.desktop_email_bcc_presets_title),
        subtitle = str(S.desktop_email_bcc_presets_subtitle),
        onBack = onBack,
    ) {
        state.error?.let { message ->
            SettingsMessage(message, StatusTone.Rejected, onDismiss = { onEvent(BccPresetsEvent.DismissError) })
        }

        AddPresetRow(state, onEvent)

        when {
            state.isLoading && state.presets.isEmpty() -> SettingsHint(str(S.ah_loading))

            state.presets.isEmpty() ->
                SettingsHint(str(S.desktop_email_no_bcc_yet))

            else -> state.presets.forEach { address -> PresetRow(address, state.isSaving, onEvent) }
        }
    }

    // Android confirms before removing (`EmailPresetPage.kt:112-127`): the list
    // is written back whole, so a mis-click silently changes every email that
    // goes out afterwards.
    state.pendingRemove?.let { address ->
        ModalCard(onDismiss = { onEvent(BccPresetsEvent.DismissRemove) }) {
            ZillitText(text = str(S.desktop_email_remove_bcc_title), style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = str(S.desktop_email_remove_bcc_body, address),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            DialogButtons(
                action = str(S.remove),
                variant = ButtonVariant.Danger,
                onConfirm = { onEvent(BccPresetsEvent.ConfirmRemove) },
                onDismiss = { onEvent(BccPresetsEvent.DismissRemove) },
            )
        }
    }
}

@Composable
private fun PresetRow(address: String, isSaving: Boolean, onEvent: (BccPresetsEvent) -> Unit) {
    SettingsRow {
        ZillitText(
            text = address,
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.bs_chip_remove, address),
            enabled = !isSaving,
            onClick = { onEvent(BccPresetsEvent.AskRemove(address)) },
        )
    }
}

/** The address field, its crew drop-down, and Add. */
@Composable
private fun AddPresetRow(state: BccPresetsUiState, onEvent: (BccPresetsEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitTextField(
            value = state.input,
            onValueChange = { onEvent(BccPresetsEvent.InputChanged(it)) },
            placeholder = "name@example.com",
            errorText = state.inputError,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onImeAction = { onEvent(BccPresetsEvent.Add) },
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                ZillitButton(
                    text = str(S.add),
                    size = ButtonSize.Small,
                    enabled = state.input.isNotBlank() && !state.isSaving,
                    onClick = { onEvent(BccPresetsEvent.Add) },
                )
            },
        )
        // The crew list under the field, as Android's autocomplete has it.
        state.suggestions.forEach { contact ->
            SettingsRow(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    ZillitText(text = contact.label, style = ZillitTheme.typography.bodyMedium)
                    ZillitText(
                        text = contact.address,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                ZillitButton(
                    text = str(S.add),
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    onClick = { onEvent(BccPresetsEvent.Pick(contact.address)) },
                )
            }
        }
    }
}
