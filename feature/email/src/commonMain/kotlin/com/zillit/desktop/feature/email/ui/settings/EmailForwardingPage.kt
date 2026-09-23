package com.zillit.desktop.feature.email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Email Forwarding — Android's forwarding half of `SettingsViewModel`
 * (`ui/settings/SettingsViewModel.kt:51-118`): one outside address that
 * incoming mail is copied to.
 *
 * The state of it is said in words above the field, because "is forwarding
 * on?" is the question this page exists to answer and an empty text box does
 * not answer it.
 */
@Composable
internal fun EmailForwardingPage(
    state: EmailForwardingUiState,
    onEvent: (EmailForwardingEvent) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = str(S.desktop_email_forwarding),
        subtitle = str(S.desktop_email_forwarding_subtitle),
        onBack = onBack,
    ) {
        state.error?.let { message ->
            SettingsMessage(message, StatusTone.Rejected, onDismiss = { onEvent(EmailForwardingEvent.DismissMessage) })
        }
        state.info?.let { message ->
            SettingsMessage(message, StatusTone.Done, onDismiss = { onEvent(EmailForwardingEvent.DismissMessage) })
        }

        if (state.isLoading && state.saved == null) {
            SettingsHint(str(S.ah_loading))
            return@SettingsPage
        }

        ZillitText(
            text = state.saved?.let { str(S.desktop_email_forwarding_to, it.address) }
                ?: str(S.desktop_email_forwarding_off),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )

        ZillitTextField(
            value = state.address,
            onValueChange = { onEvent(EmailForwardingEvent.AddressChanged(it)) },
            label = str(S.desktop_email_forward_to),
            placeholder = "name@example.com",
            errorText = state.inputError,
            enabled = !state.isSaving,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onImeAction = { if (state.canSave) onEvent(EmailForwardingEvent.Save) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            // Turning it off is destructive enough to earn its own button, but
            // not a dialog: the address is still in the field to put back.
            if (state.isConfigured) {
                ZillitButton(
                    text = str(S.desktop_turn_off),
                    variant = ButtonVariant.Tertiary,
                    enabled = !state.isSaving,
                    onClick = { onEvent(EmailForwardingEvent.Remove) },
                )
            }
            ZillitButton(
                text = str(if (state.isConfigured) S.update else S.desktop_email_start_forwarding),
                enabled = state.canSave && !state.isSaving,
                loading = state.isSaving,
                onClick = { onEvent(EmailForwardingEvent.Save) },
            )
        }
    }
}
