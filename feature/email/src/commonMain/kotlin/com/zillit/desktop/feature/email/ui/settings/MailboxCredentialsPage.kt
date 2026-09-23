package com.zillit.desktop.feature.email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.zillit.desktop.feature.email.domain.MailboxServer
import com.zillit.desktop.feature.email.ui.DialogButtons
import com.zillit.desktop.feature.email.ui.ModalCard

/**
 * Email Setup Externally — Android's IMAP/SMTP card
 * (`GeneralSettingsActivity.kt`): what to type into Outlook or Apple Mail.
 *
 * Every value has a copy button, which is the whole point of the page: these
 * are transcribed into another application, and a mistyped host is a support
 * ticket. The password is never on screen unless asked for and is dropped the
 * moment it is hidden — see [MailboxCredentialsUiState.revealedPassword].
 */
@Composable
internal fun MailboxCredentialsPage(
    state: MailboxCredentialsUiState,
    onEvent: (MailboxCredentialsEvent) -> Unit,
    onCopy: (String) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = str(S.email_credentials),
        subtitle = str(S.desktop_email_credentials_subtitle),
        onBack = onBack,
    ) {
        state.error?.let { message ->
            SettingsMessage(
                message,
                StatusTone.Rejected,
                onDismiss = { onEvent(MailboxCredentialsEvent.DismissMessage) },
            )
        }
        state.info?.let { message ->
            SettingsMessage(message, StatusTone.Done, onDismiss = { onEvent(MailboxCredentialsEvent.DismissMessage) })
        }

        val credentials = state.credentials
        when {
            state.isLoading && credentials == null -> SettingsHint(str(S.ah_loading))

            credentials == null ->
                SettingsHint(str(S.desktop_email_credentials_no_mailbox))

            else -> {
                CopyableRow(str(S.hint_email), credentials.emailAddress, onCopy)
                ServerBlock(str(S.desktop_email_imap_incoming), credentials.imap, onCopy)
                ServerBlock(str(S.desktop_email_smtp_outgoing), credentials.smtp, onCopy)
                PasswordRow(state, onEvent, onCopy)
            }
        }
    }

    if (state.isChangingPassword) {
        ChangePasswordDialog(state, onEvent)
    }
}

@Composable
private fun ServerBlock(title: String, server: MailboxServer, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(text = title, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        CopyableRow(str(S.host_txt), server.host, onCopy)
        if (server.portLabel.isNotEmpty()) CopyableRow(str(S.port_txt), server.portLabel, onCopy)
        CopyableRow(str(S.desktop_username), server.username, onCopy)
    }
}

/** A label, its value, and the copy button that is the reason this page exists. */
@Composable
private fun CopyableRow(label: String, value: String, onCopy: (String) -> Unit) {
    SettingsRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
            ZillitText(text = value, style = ZillitTheme.typography.bodyMedium)
        }
        ZillitIconButton(
            icon = ZillitIcons.File,
            contentDescription = str(S.desktop_copy_named, label),
            onClick = { onCopy(value) },
        )
    }
}

/** Reveal, copy, and change — the password's three affordances, in one row. */
@Composable
private fun PasswordRow(
    state: MailboxCredentialsUiState,
    onEvent: (MailboxCredentialsEvent) -> Unit,
    onCopy: (String) -> Unit,
) {
    SettingsRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = str(S.password_txt),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitText(
                // Dots of a fixed length: the real one's length is a hint too.
                text = state.revealedPassword ?: HIDDEN_PASSWORD,
                style = ZillitTheme.typography.bodyMedium,
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Eye,
            contentDescription = str(if (state.isRevealed) S.hide_password_txt else S.show_password_txt),
            enabled = !state.isRevealing,
            onClick = { onEvent(MailboxCredentialsEvent.ToggleReveal) },
        )
        ZillitIconButton(
            icon = ZillitIcons.File,
            contentDescription = str(S.desktop_email_copy_password),
            enabled = !state.isRevealing,
            onClick = {
                // Already on screen? Copy it here; otherwise the view model
                // fetches and hands it back through its effect.
                state.revealedPassword?.let(onCopy) ?: onEvent(MailboxCredentialsEvent.CopyPassword)
            },
        )
        ZillitButton(
            text = str(S.change),
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = { onEvent(MailboxCredentialsEvent.ChangePassword) },
        )
    }
}

@Composable
private fun ChangePasswordDialog(state: MailboxCredentialsUiState, onEvent: (MailboxCredentialsEvent) -> Unit) {
    ModalCard(onDismiss = { onEvent(MailboxCredentialsEvent.CancelNewPassword) }) {
        ZillitText(text = str(S.desktop_email_change_mailbox_password), style = ZillitTheme.typography.titleMedium)
        ZillitText(
            text = str(S.desktop_email_change_password_warning),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = state.newPassword,
            onValueChange = { onEvent(MailboxCredentialsEvent.NewPasswordChanged(it)) },
            label = str(S.desktop_email_new_password),
            enabled = !state.isUpdatingPassword,
            visualTransformation = PasswordVisualTransformation(),
            imeAction = ImeAction.Done,
            onImeAction = { if (state.canUpdatePassword) onEvent(MailboxCredentialsEvent.ConfirmNewPassword) },
            modifier = Modifier.fillMaxWidth(),
        )
        DialogButtons(
            action = str(S.change_password_txt),
            enabled = state.canUpdatePassword && !state.isUpdatingPassword,
            loading = state.isUpdatingPassword,
            onConfirm = { onEvent(MailboxCredentialsEvent.ConfirmNewPassword) },
            onDismiss = { onEvent(MailboxCredentialsEvent.CancelNewPassword) },
        )
    }
}

/** Not the real length — that is a hint about the password itself. */
private const val HIDDEN_PASSWORD = "••••••••••"
