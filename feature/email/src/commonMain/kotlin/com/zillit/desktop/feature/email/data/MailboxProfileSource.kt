package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.email.domain.MailboxCredentials
import com.zillit.desktop.feature.email.domain.MailboxServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The mailbox half of the user's profile.
 *
 * Three settings — conversation view, BCC presets, the IMAP/SMTP details —
 * are not on the mail service at all: they ride on `GET user/profile`
 * (Android `ApiUrl.kt:105`, `PATH_USER_PROFILE = "${USER}profile"`), which the
 * phone reads once into `profileDetailsFlow` and every settings screen then
 * consults (`GeneralSettingsActivity.kt:182`, `EmailPresetPage.kt:103`,
 * `ConversationViewPreference.kt:30`). The desktop's session snapshot drops
 * these fields, so this reads the same GET again for just the mailbox part.
 *
 * Public only so the three repositories that need it can share one instance
 * (see `remoteEmailSettingsRepositories`); nothing outside `data` should hold
 * it.
 */
class MailboxProfileSource(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) {
    /** The core service, not the mail one: the profile is a project-management record. */
    private val api get() = config.apiV2()

    /**
     * [options] names another production (and the caller's id on it) when the
     * open one is not the right scope — the picker asks before any is open.
     */
    suspend fun profile(options: CallOptions = CallOptions()): ZillitResult<MailboxProfile> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}user/profile",
            serializer = MailboxProfileDto.serializer(),
            module = RequestModule.ProjectUser,
            options = options,
        ).map { it.toProfile() }
}

data class MailboxProfile(
    /** Null when the profile has no mailbox at all. */
    val conversationView: Boolean?,
    val bccPresets: List<String>,
    val credentials: MailboxCredentials?,
)

/**
 * `UserData`, cut down to the mailbox fields (`JoinProjectResponse.kt:149-152`).
 *
 * The password fields (`secure_smtp_password`, `secure_imap_password`) are
 * deliberately not declared. Since F-008 they carry `enc:v1:…` ciphertext,
 * which is useless to render and dangerous to keep — declaring them would
 * make it one refactor away from showing up on a screen. The plaintext has
 * one source, the reveal endpoint, and one reader,
 * [MailboxCredentialsRepositoryImpl].
 */
@Serializable
internal data class MailboxProfileDto(
    @SerialName("mail_box_detail") val mailbox: MailboxDetailDto? = null,
    @SerialName("bcc") val bcc: List<BccPresetDto>? = null,
) {
    fun toProfile() = MailboxProfile(
        conversationView = mailbox?.conversationView,
        bccPresets = bcc.orEmpty().mapNotNull { it.address?.takeIf(String::isNotBlank) },
        credentials = mailbox?.toCredentials(),
    )
}

/** Android `MailingModel` (`JoinProjectResponse.kt:224-246`), minus the ciphertext. */
@Serializable
internal data class MailboxDetailDto(
    @SerialName("conversation_view") val conversationView: Boolean? = null,
    @SerialName("email_address") val emailAddress: String? = null,
    @SerialName("secure_smtp_server_host") val smtpHost: String? = null,
    @SerialName("secure_smtp_server_port") val smtpPort: Int? = null,
    @SerialName("secure_smtp_user_name") val smtpUser: String? = null,
    @SerialName("secure_imap_server_host") val imapHost: String? = null,
    @SerialName("secure_imap_server_port") val imapPort: Int? = null,
    @SerialName("secure_imap_user_name") val imapUser: String? = null,
) {
    /**
     * Null when there is nothing to show — no address and no host on either
     * side, which is what a user without a provisioned mailbox looks like.
     */
    fun toCredentials(): MailboxCredentials? {
        val address = emailAddress?.takeIf(String::isNotBlank)
        if (address == null && smtpHost.isNullOrBlank() && imapHost.isNullOrBlank()) return null
        return MailboxCredentials(
            emailAddress = address.orEmpty(),
            smtp = MailboxServer(smtpHost.orEmpty(), smtpPort, smtpUser.orEmpty()),
            imap = MailboxServer(imapHost.orEmpty(), imapPort, imapUser.orEmpty()),
        )
    }
}

/** Android `BccPresetModel` (`JoinProjectResponse.kt:258-260`). */
@Serializable
internal data class BccPresetDto(
    @SerialName("email_address") val address: String? = null,
)
