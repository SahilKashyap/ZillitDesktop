package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.feature.email.domain.EmailSettingsRepositories

/**
 * The live settings repositories, sharing one profile reader.
 *
 * One [MailboxProfileSource] rather than one per repository, so a future
 * cache in it would serve all three profile-backed settings from a single
 * `GET user/profile`. Today each section reads it when opened.
 */
fun remoteEmailSettingsRepositories(apiClient: ApiClient, config: AppConfig): EmailSettingsRepositories {
    val profile = MailboxProfileSource(apiClient, config)
    return EmailSettingsRepositories(
        conversationView = ConversationViewRepositoryImpl(apiClient, config, profile),
        groups = EmailGroupRepositoryImpl(apiClient, config),
        bccPresets = BccPresetRepositoryImpl(apiClient, config, profile),
        forwarding = EmailForwardingRepositoryImpl(apiClient, config),
        credentials = MailboxCredentialsRepositoryImpl(apiClient, config, profile),
    )
}
