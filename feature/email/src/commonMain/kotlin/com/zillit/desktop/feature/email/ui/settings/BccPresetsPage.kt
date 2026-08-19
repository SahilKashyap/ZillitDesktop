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
        title = "BCC Presets",
        subtitle = "Auto-add BCC recipients to every email",
        onBack = onBack,
    ) {
        state.error?.let { message ->
            SettingsMessage(message, StatusTone.Rejected, onDismiss = { onEvent(BccPresetsEvent.DismissError) })
        }

        AddPresetRow(state, onEvent)

        when {
            state.isLoading && state.presets.isEmpty() -> SettingsHint("Loading…")

            state.presets.isEmpty() ->
                SettingsHint("No BCC addresses yet. Anything here is copied on every email you send.")

            else -> state.presets.forEach { address -> PresetRow(address, state.isSaving, onEvent) }
        }
    }

    // Android confirms before removing (`EmailPresetPage.kt:112-127`): the list
    // is written back whole, so a mis-click silently changes every email that
    // goes out afterwards.
    state.pendingRemove?.let { address ->
        ModalCard(onDismiss = { onEvent(BccPresetsEvent.DismissRemove) }) {
            ZillitText(text = "Remove this BCC address?", style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = "$address will no longer be blind-copied on the emails you send.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            DialogButtons(
                action = "Remove",
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
            contentDescription = "Remove $address",
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
                    text = "Add",
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
                    text = "Add",
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    onClick = { onEvent(BccPresetsEvent.Pick(contact.address)) },
                )
            }
        }
    }
}
