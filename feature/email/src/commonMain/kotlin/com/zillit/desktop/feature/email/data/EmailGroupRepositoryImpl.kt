package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.EmailGroup
import com.zillit.desktop.feature.email.domain.EmailGroupRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Distribution groups: `imap-email-group` on the mail service.
 *
 * Android `EmailApi.kt:230-255` against `ApiUrl.EMAIL_GROUP`
 * (`ApiUrl.kt:759`, `"${MAIL_URL}imap-email-group"`); the web's
 * `emailApi.js:225-260` is the same four calls.
 */
class EmailGroupRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : EmailGroupRepository {

    private val api get() = config.apiV2(ZillitService.Email)
    private val url get() = "${api}imap-email-group"

    // GET imap-email-group — EmailApi.fetchEmailGroups (EmailApi.kt:232-236).
    override suspend fun groups(): ZillitResult<List<EmailGroup>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = url,
            serializer = ListSerializer(EmailGroupDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            // Soft-deleted rows come back with a tombstone; Android drops them
            // (EmailGroupsViewModel.kt:45).
            rows.filter { it.deleted == 0L }.map { it.toGroup() }
        }

    // POST imap-email-group {group_name, members_email:[...]} —
    // CreateEmailGroupRequest (RequestDto.kt:166-171), EmailApi.kt:238-242.
    override suspend fun create(name: String, members: List<String>): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = url,
            module = RequestModule.ProjectUser,
            body = jsonBody(groupBody(id = null, name = name, members = members)),
        ).map { }

    // PUT imap-email-group {_id, group_name, members_email:[...]} —
    // UpdateEmailGroupRequest (RequestDto.kt:173-180), EmailApi.kt:244-248.
    override suspend fun update(id: String, name: String, members: List<String>): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = url,
            module = RequestModule.ProjectUser,
            body = jsonBody(groupBody(id = id, name = name, members = members)),
        ).map { }

    // DELETE imap-email-group/{id}, no body — EmailApi.kt:250-255.
    override suspend fun delete(id: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "$url/$id",
            module = RequestModule.ProjectUser,
        ).map { }

    private fun groupBody(id: String?, name: String, members: List<String>) = buildJsonObject {
        id?.let { put("_id", it) }
        put("group_name", name)
        // Bare strings on the way in, `{email_address, enabled}` objects on
        // the way out — see [GroupMemberDto].
        putJsonArray("members_email") { members.forEach { add(it) } }
    }
}

/** Android `EmailGroupDto` (`EmailDto.kt:152-162`). */
@Serializable
internal data class EmailGroupDto(
    @SerialName("_id") val id: String = "",
    @SerialName("group_name") val groupName: String = "",
    @SerialName("members_email") val members: List<GroupMemberDto> = emptyList(),
    @SerialName("mail_box_detail") val mailbox: GroupMailboxDto? = null,
    @SerialName("deleted") val deleted: Long = 0,
) {
    fun toGroup() = EmailGroup(
        id = id,
        name = groupName,
        members = members.filter { it.enabled }.mapNotNull { it.address.takeIf(String::isNotBlank) },
        address = mailbox?.address?.takeIf(String::isNotBlank),
    )
}

/** Android `GroupMemberDto` (`EmailDto.kt:169-174`). */
@Serializable
internal data class GroupMemberDto(
    @SerialName("email_address") val address: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
)

/** Android `GroupMailBoxDto` (`EmailDto.kt:164-168`). */
@Serializable
internal data class GroupMailboxDto(
    @SerialName("email_address") val address: String? = null,
    @SerialName("name") val name: String? = null,
)
