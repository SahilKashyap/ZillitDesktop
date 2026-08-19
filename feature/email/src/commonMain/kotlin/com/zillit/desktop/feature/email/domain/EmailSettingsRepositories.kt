package com.zillit.desktop.feature.email.domain

/**
 * Everything the Email Settings window talks to.
 *
 * Bundled for the same reason the composer's collaborators are — five
 * repositories in a constructor is where an injection site puts one in the
 * wrong slot and nothing complains. Each is its own port because each is its
 * own endpoint family on its own service (see the `data` implementations);
 * a single "settings repository" would hide that three of them are really the
 * user's profile.
 */
data class EmailSettingsRepositories(
    val conversationView: ConversationViewRepository,
    val groups: EmailGroupRepository,
    val bccPresets: BccPresetRepository,
    val forwarding: EmailForwardingRepository,
    val credentials: MailboxCredentialsRepository,
)
