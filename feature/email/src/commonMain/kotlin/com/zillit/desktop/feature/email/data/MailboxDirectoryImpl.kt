package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.MailboxDirectory
import com.zillit.desktop.feature.email.domain.MailboxIdentity
import com.zillit.desktop.feature.email.domain.MailboxKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The two mailboxes a production can give this user, read from the two
 * records that carry them.
 *
 * The personal one rides `GET user/profile` (`mail_box_detail`); the shared
 * Accounts one rides `GET project/{id}` (`accounts_mail_box_detail`, which
 * the server fills only for Accounts-department members and leaves `{}` for
 * everyone else). Both are on the core service, not the mail one — they are
 * project-management records — and both are read fresh on every open: the
 * web refetches the project on entry for exactly this field, because
 * department membership changes while the user is elsewhere.
 */
class MailboxDirectoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** The open production; null before one is open, when there is nothing to ask. */
    private val projectId: () -> String?,
    /** The signed-in user's display name, the `From:` fallback for a nameless mailbox. */
    private val userName: () -> String = { "" },
) : MailboxDirectory {

    private val api get() = config.apiV2()

    override suspend fun personal(): ZillitResult<MailboxIdentity?> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}user/profile",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { payload ->
            val profile = payload as? JsonObject ?: return@map null
            val box = profile["mail_box_detail"] as? JsonObject ?: return@map null
            box.toIdentity(MailboxKind.Personal, fallbackName = userName())
                ?.copy(bccPresets = profile.presets())
        }

    override suspend fun accounts(): ZillitResult<MailboxIdentity?> {
        val id = projectId()?.takeIf { it.isNotBlank() } ?: return ZillitResult.Success(null)
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}project/$id",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            // Not from the read cache: a stale answer here is exactly the
            // thing the web's entry refetch exists to avoid.
            options = CallOptions(readCache = false),
        ).map { payload ->
            val box = (payload as? JsonObject)?.get("accounts_mail_box_detail") as? JsonObject
            box?.toIdentity(MailboxKind.Accounts, fallbackName = "Accounts")?.copy(bccPresets = box.presets())
        }
    }

    override suspend fun setAccountsConversationView(enabled: Boolean): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "${api}project/accounts-mail-box/conversation-view",
            module = RequestModule.ProjectUser,
            body = jsonBody(buildJsonObject { put("conversation_view", enabled) }),
        ).map { }

    override suspend fun setAccountsBccPresets(addresses: List<String>): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "${api}project/accounts-mail-box/bcc",
            module = RequestModule.ProjectUser,
            body = jsonBody(
                buildJsonObject {
                    putJsonArray("bcc") {
                        addresses.forEach { add(buildJsonObject { put("email_address", it) }) }
                    }
                },
            ),
        ).map { }
}

/**
 * A `mail_box_detail` / `accounts_mail_box_detail` object; null without an
 * address, which is what "no mailbox here" looks like on both records.
 */
internal fun JsonObject.toIdentity(kind: MailboxKind, fallbackName: String): MailboxIdentity? {
    val address = str("email_address") ?: return null
    return MailboxIdentity(
        kind = kind,
        address = address,
        name = str("name") ?: fallbackName,
        // Nullable on purpose: absent means "never set", and the two mailboxes
        // default differently (see `ConversationViewRepositoryImpl`).
        conversationView = (this["conversation_view"] as? JsonPrimitive)
            ?.let { it.booleanOrNull ?: (it.contentOrNull == "true") },
    )
}

/** `bcc: [{email_address}]`, on the profile and on the accounts record alike. */
private fun JsonObject.presets(): List<String> =
    (this["bcc"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.str("email_address") }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
