package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.EmailForwarding
import com.zillit.desktop.feature.email.domain.EmailForwardingRepository
import com.zillit.desktop.feature.email.domain.MailboxScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Auto-forwarding: `email-forwarding-setting` on the mail service.
 *
 * Android `EmailApi.kt:346-364` against `ApiUrl.EMAIL_FORWARDING_SETTING`
 * (`ApiUrl.kt:779`, `"${MAIL_URL}email-forwarding-setting"`); web
 * `newEmailApi.js:121-148`.
 */
class EmailForwardingRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** The shared Accounts mailbox forwards on its own setting. */
    private val scope: MailboxScope = MailboxScope.Personal,
) : EmailForwardingRepository {

    private val url get() = "${config.apiV2(ZillitService.Email)}email-forwarding-setting"

    // GET — EmailApi.getForwardingSetting (EmailApi.kt:348-352). A missing or
    // blank `forward_to_email` means "not configured" (ResponseDto.kt:98).
    override suspend fun current(): ZillitResult<EmailForwarding?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = url,
            serializer = ForwardingSettingDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
        ).map { it?.toForwarding() }

    // POST {forward_to_email, enabled:true} — ForwardingSettingRequest
    // (RequestDto.kt:188-191), EmailApi.saveForwardingSetting (EmailApi.kt:354-358).
    override suspend fun save(address: String): ZillitResult<EmailForwarding> {
        val trimmed = address.trim()
        return apiClient.requestOrNull(
            verb = HttpVerb.Post,
            url = url,
            serializer = ForwardingSettingDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("forward_to_email", trimmed)
                        put("enabled", true)
                    },
                ),
            ),
        ).map { saved ->
            // The echo can be blank on some builds; the address just sent is
            // then the truth (SettingsViewModel.kt:76).
            saved?.toForwarding() ?: EmailForwarding(trimmed)
        }
    }

    // DELETE, no body — EmailApi.deleteForwardingSetting (EmailApi.kt:360-364).
    override suspend fun remove(): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = scope.flagBody()?.let(::jsonBody),
        ).map { }
}

/** Android `ForwardingSettingResponse` (`ResponseDto.kt:99-104`). */
@Serializable
internal data class ForwardingSettingDto(
    @SerialName("forward_to_email") val forwardTo: String? = null,
    @SerialName("enabled") val enabled: Boolean = true,
) {
    fun toForwarding(): EmailForwarding? =
        forwardTo?.takeIf(String::isNotBlank)?.let { EmailForwarding(it, enabled) }
}
