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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.rules.EmailRulesUiState
import com.zillit.desktop.feature.email.rules.EmailRulesPage
import com.zillit.desktop.feature.email.rules.EmailRulesEvent

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
    rules: SectionBinding<EmailRulesUiState, EmailRulesEvent>,
    onOpenSignatures: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        val back = { onEvent(EmailSettingsEvent.Back) }
        when (state.section) {
            null -> SettingsCards(state, onEvent, onOpenSignatures)
            EmailSettingsSection.Rules -> EmailRulesPage(rules.state, rules.onEvent, back)
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
            title = str(S.email_settings),
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
                text = str(S.ah_loading),
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
        title = str(S.email_trailing),
        detail = str(S.desktop_email_conversation_view_detail),
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
        title = str(S.signatures),
        detail = str(S.desktop_email_signatures_detail),
        onClick = onOpenSignatures,
    )
    if (state.canManageGroups) {
        SettingsCard(
            icon = ZillitIcons.Users,
            title = str(S.email_groups),
            detail = str(S.desktop_email_groups_subtitle),
            onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.Groups)) },
        )
    }
    SettingsCard(
        icon = ZillitIcons.Filter,
        title = str(S.email_rules_title),
        detail = str(S.desktop_email_rules_detail),
        onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.Rules)) },
    )
    SettingsCard(
        icon = ZillitIcons.UserPlus,
        title = str(S.desktop_email_bcc_presets_title),
        detail = str(S.desktop_email_bcc_presets_subtitle),
        onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.BccPresets)) },
    )
    SettingsCard(
        icon = ZillitIcons.ArrowRight,
        title = str(S.desktop_email_forwarding),
        detail = str(S.desktop_email_forwarding_subtitle),
        onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.Forwarding)) },
    )
    SettingsCard(
        icon = ZillitIcons.Info,
        title = str(S.email_credentials),
        detail = str(S.desktop_email_credentials_subtitle),
        onClick = { onEvent(EmailSettingsEvent.Open(EmailSettingsSection.Credentials)) },
    )
}
