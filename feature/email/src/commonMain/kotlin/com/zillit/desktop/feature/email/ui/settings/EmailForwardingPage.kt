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
        title = "Email Forwarding",
        subtitle = "Auto-forward incoming emails to an external address",
        onBack = onBack,
    ) {
        state.error?.let { message ->
            SettingsMessage(message, StatusTone.Rejected, onDismiss = { onEvent(EmailForwardingEvent.DismissMessage) })
        }
        state.info?.let { message ->
            SettingsMessage(message, StatusTone.Done, onDismiss = { onEvent(EmailForwardingEvent.DismissMessage) })
        }

        if (state.isLoading && state.saved == null) {
            SettingsHint("Loading…")
            return@SettingsPage
        }

        ZillitText(
            text = state.saved?.let { "Mail is being forwarded to ${it.address}." }
                ?: "Mail is not being forwarded. Add an address to start.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )

        ZillitTextField(
            value = state.address,
            onValueChange = { onEvent(EmailForwardingEvent.AddressChanged(it)) },
            label = "Forward to",
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
                    text = "Turn off",
                    variant = ButtonVariant.Tertiary,
                    enabled = !state.isSaving,
                    onClick = { onEvent(EmailForwardingEvent.Remove) },
                )
            }
            ZillitButton(
                text = if (state.isConfigured) "Update" else "Start forwarding",
                enabled = state.canSave && !state.isSaving,
                loading = state.isSaving,
                onClick = { onEvent(EmailForwardingEvent.Save) },
            )
        }
    }
}
