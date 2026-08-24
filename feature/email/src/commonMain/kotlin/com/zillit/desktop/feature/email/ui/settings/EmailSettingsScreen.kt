package com.zillit.desktop.feature.email.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Email Settings — Android `GeneralSettingsActivity`
 * (`ui/settings/GeneralSettingsActivity.kt`, layout `email_fragment_settings.xml`).
 *
 * The card list, in the phone's order, and the sub-page each card opens. Not
 * a modal over the mailbox: the phone pushes an activity and the web opens a
 * modal, but a settings task on a desktop — pasting an IMAP host into another
 * client, say — wants a window that stays put while you go elsewhere.
 */
@Composable
internal fun EmailSettingsScreen(
    state: EmailSettingsUiState,
    onEvent: (EmailSettingsEvent) -> Unit,
    groups: SectionBinding<EmailGroupsUiState, EmailGroupsEvent>,
    bccPresets: SectionBinding<BccPresetsUiState, BccPresetsEvent>,
    forwarding: SectionBinding<EmailForwardingUiState, EmailForwardingEvent>,
    credentials: SectionBinding<MailboxCredentialsUiState, MailboxCredentialsEvent>,
    onOpenSignatures: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        val back = { onEvent(EmailSettingsEvent.Back) }
        when (state.section) {
            null -> SettingsCards(state, onEvent, onOpenSignatures)
            EmailSettingsSection.Groups -> EmailGroupsPage(groups.state, groups.onEvent, back)
            EmailSettingsSection.BccPresets -> BccPresetsPage(bccPresets.state, bccPresets.onEvent, back)
            EmailSettingsSection.Forwarding -> EmailForwardingPage(forwarding.state, forwarding.onEvent, back)
            EmailSettingsSection.Credentials ->
                MailboxCredentialsPage(credentials.state, credentials.onEvent, onCopy, back)
        }
    }
}

/** The card list, with the header over it and the load state under it. */
@Composable
private fun SettingsCards(
    state: EmailSettingsUiState,
    onEvent: (EmailSettingsEvent) -> Unit,
    onOpenSignatures: () -> Unit,
) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitPageHeader(
            title = "Email Settings",
            modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
        )

        state.error?.let { message ->
            SettingsMessage(
                text = message,
                tone = StatusTone.Rejected,
                onDismiss = { onEvent(EmailSettingsEvent.DismissError) },
            )
        }

        SettingsCardList(state, onEvent, onOpenSignatures)

        if (state.isLoading) {
            ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )
        }
    }
}

/** The six cards, in Android's order (`email_fragment_settings.xml`). */
@Composable
private fun SettingsCardList(
    state: EmailSettingsUiState,
    onEvent: (EmailSettingsEvent) -> Unit,
    onOpenSignatures: () -> Unit,
) {
    SettingsCard(
        icon = ZillitIcons.Mail,
        title = "Conversation View",
        detail = "Group emails in the same conversation together",
        onClick = null,
        trailing = {
            // A checkbox rather than a switch: the design system has no
            // switch, and Settings elsewhere in this app uses the same.
            ZillitCheckbox(
                checked = state.conversationView,
                enabled = !state.isSavingConversationView && !state.isLoading,
                onCheckedChange = { onEvent(EmailSettingsEvent.ConversationViewChanged(it)) },
            )
        },
    )
    SettingsCard(
        icon = ZillitIcons.Edit,
        title = "Signatures",
        detail = "Manage your email signatures",
        onClick = onOpenSignatures,
    )
    if (state.canManageGroups) {
        SettingsCard(
            icon = ZillitIcons.Users,
            title = "Email Groups",
            detail = "Manage your email groups",
            onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.Groups)) },
        )
    }
    SettingsCard(
        icon = ZillitIcons.UserPlus,
        title = "BCC Presets",
        detail = "Auto-add BCC recipients to every email",
        onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.BccPresets)) },
    )
    SettingsCard(
        icon = ZillitIcons.ArrowRight,
        title = "Email Forwarding",
        detail = "Auto-forward incoming emails to an external address",
        onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.Forwarding)) },
    )
    SettingsCard(
        icon = ZillitIcons.Info,
        title = "Email Setup Externally",
        detail = "View IMAP/SMTP credentials for external clients",
        onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.Credentials)) },
    )
}
