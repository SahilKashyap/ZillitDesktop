package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.ConversationViewRepository
import com.zillit.desktop.feature.email.domain.MailboxCredentials
import com.zillit.desktop.feature.email.domain.MailboxCredentialsRepository
import com.zillit.desktop.feature.email.domain.MailboxDirectory
import com.zillit.desktop.feature.email.domain.MailboxScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The IMAP/SMTP details an external client needs, and the one thing about them
 * that can be changed.
 *
 * Three endpoints on two services:
 *
 *  - the host/port/user come off the profile (core service; see
 *    [MailboxProfileSource]);
 *  - the password comes from `GET imap-credentials/reveal` on the mail service
 *    (Android `ApiUrl.kt:770`, `MailCredentialsRevealer.kt:58-98`);
 *  - a new password goes to `PUT imap-credentials/update` on the mail service
 *    (Android `ApiUrl.kt:760`, `GeneralSettingsActivity.kt:256-276`).
 */
class MailboxCredentialsRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val profile: MailboxProfileSource = MailboxProfileSource(apiClient, config),
    /** The reveal is per mailbox; the shared one's password is its own. */
    private val scope: MailboxScope = MailboxScope.Personal,
) : MailboxCredentialsRepository {

    private val mail get() = config.apiV2(ZillitService.Email)

    override suspend fun credentials(): ZillitResult<MailboxCredentials?> =
        profile.profile().map { it.credentials }

    /**
     * `GET imap-credentials/reveal`.
     *
     * Not through the read cache: a password must never be written to disk,
     * and a stale one served offline would be worse than none. Android reads
     * SMTP first and IMAP second (`RevealablePasswordBinder.kt:92-93`); a 200
     * with neither is reported as "no mailbox" rather than shown blank
     * (`MailCredentialsRevealer.kt:86-92`).
     */
    override suspend fun revealPassword(): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${mail}imap-credentials/reveal",
            serializer = RevealedCredentialsDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            options = CallOptions(readCache = false),
        ).flatMap { revealed ->
            val password = revealed.smtp?.password?.takeIf(String::isNotBlank)
                ?: revealed.imap?.password?.takeIf(String::isNotBlank)
            if (password == null) {
                ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = NOT_AVAILABLE))
            } else {
                ZillitResult.Success(password)
            }
        }

    /**
     * `PUT imap-credentials/update {password}` — `CredentialUpdateRequestModel`
     * (`EmailSendRequestModel.kt:142-145`); Android sends `message_id` as
     * null, which Gson omits, so the body is the one key.
     *
     * Android's constant has a doubled slash (`"${MAIL_URL}/imap-credentials/update"`,
     * `ApiUrl.kt:760`); the web spells it with one (`emailApi.js:265`). One
     * here — the server tolerates the phone's, and a path is not a place to
     * copy an accident.
     */
    override suspend fun updatePassword(password: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "${mail}imap-credentials/update",
            module = RequestModule.ProjectUser,
            body = jsonBody(buildJsonObject { put("password", password) }),
        ).map { }

    private companion object {
        const val HTTP_OK = 200

        /** Android `MailCredentialsRevealer.ERROR_NOT_AVAILABLE`, the server's own code for it. */
        const val NOT_AVAILABLE = "email_credentials_not_available"
    }
}

/**
 * Android `RevealedCredentials` (`RevealedCredentialsDto.kt:14-28`).
 *
 * The phone decodes the `{status, message, data}` envelope first and a bare
 * object second (`RevealedCredentialsDto.kt:30-41`); `ApiClient` unwraps the
 * envelope, so this is the inner shape only. Never logged, never kept: it
 * exists for the length of one `flatMap`.
 */
@Serializable
internal data class RevealedCredentialsDto(
    @SerialName("email_address") val emailAddress: String? = null,
    @SerialName("smtp") val smtp: RevealedServerDto? = null,
    @SerialName("imap") val imap: RevealedServerDto? = null,
) {
    override fun toString(): String = "RevealedCredentialsDto(address=$emailAddress)"
}

@Serializable
internal data class RevealedServerDto(
    @SerialName("host") val host: String? = null,
    @SerialName("port") val port: Int? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("password") val password: String? = null,
) {
    override fun toString(): String = "RevealedServerDto(host=$host)"
}

/**
 * Conversation view, on the core service.
 *
 * Read from the profile; written with `PATCH user/update-conversation-view
 * {conversation_view}` (Android `ApiUrl.kt:778`, `EmailApi.kt:368-377`; web
 * `projectApi.js:1255-1263`).
 */
class ConversationViewRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val profile: MailboxProfileSource = MailboxProfileSource(apiClient, config),
    /**
     * The shared Accounts mailbox stores this setting on the project rather
     * than the user (`accounts_mail_box_detail.conversation_view`, written
     * by `PATCH project/accounts-mail-box/conversation-view`); while it is
     * active the reads and writes go there.
     */
    private val scope: MailboxScope = MailboxScope.Personal,
    private val directory: MailboxDirectory? = null,
) : ConversationViewRepository {

    // Android defaults a missing value to on (ConversationViewPreference.kt:30);
    // the web defaults the shared mailbox's to off (`getEmailThreadEnabled`).
    override suspend fun isEnabled(): ZillitResult<Boolean> =
        if (scope.isAccountsActive() && directory != null) {
            directory.accounts().map { it?.conversationView ?: false }
        } else {
            profile.profile().map { it.conversationView ?: true }
        }

    override suspend fun setEnabled(enabled: Boolean): ZillitResult<Unit> =
        if (scope.isAccountsActive() && directory != null) {
            directory.setAccountsConversationView(enabled)
        } else {
            apiClient.envelope(
                verb = HttpVerb.Patch,
                url = "${config.apiV2()}user/update-conversation-view",
                module = RequestModule.ProjectUser,
                body = jsonBody(buildJsonObject { put("conversation_view", enabled) }),
            ).map { }
        }
}
