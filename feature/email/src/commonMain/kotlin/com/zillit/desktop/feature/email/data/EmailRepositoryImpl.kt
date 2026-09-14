package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import com.zillit.desktop.feature.email.domain.asThread
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The mailbox, over the IMAP-backed mail API.
 *
 * ## Why almost everything is a POST
 *
 * These endpoints proxy a real IMAP server, and the folder name plus a uid or
 * message-id list goes in the body rather than the path — mail folders are
 * user-named and contain slashes, spaces and non-ASCII, none of which survive a
 * URL segment intact.
 *
 * The older `inbound/{timestamp}/previous/{folderId}` generation still answers
 * on this host, but neither the web app nor Android uses it any more.
 */
class EmailRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /**
     * Which mailbox every call addresses — see [MailboxScope]. Consulted per
     * request, because the same repository serves both and the user flips
     * between them.
     */
    private val scope: MailboxScope = MailboxScope.Personal,
    /**
     * The `From:` header the mail service is told — `Name <address>` of the
     * active mailbox, as the web and Android send it. Blank leaves it to the
     * server, which fills in the mailbox's own address.
     */
    private val fromHeader: () -> String = { "" },
) : EmailRepository {

    private val api get() = config.apiV2(ZillitService.Email)

    override suspend fun folders(): ZillitResult<List<EmailFolder>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${api}imap-folders",
            serializer = ListSerializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
            // The flag is part of the read-cache key too, so the shared
            // mailbox's folder list is never served for the personal one.
            queryParameters = scope.query(),
        ).map { rows -> rows.mapNotNull(::readFolder) }

    override suspend fun folderUids(folderName: String): ZillitResult<List<Int>> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}imap-emails/get-folder-uids",
            serializer = ListSerializer(JsonElement.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(scope.body(buildJsonObject { put("folder_name", folderName) })),
        ).map { rows -> rows.mapNotNull(::readUid) }

    override suspend fun index(folderName: String, uids: List<Int>): ZillitResult<List<EmailSummary>> {
        if (uids.isEmpty()) return ZillitResult.Success(emptyList())

        return apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}imap-emails/get-email-index",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("folder_name", folderName)
                        put("uids", buildJsonArray { uids.forEach { add(JsonPrimitive(it)) } })
                    },
                ),
            ),
        ).map { payload ->
            payload.emailRows()
                .mapNotNull(::readSummary)
                // The folder is known by the caller but not always echoed back,
                // and a summary that does not know its folder cannot be opened.
                .map { if (it.folderName.isBlank()) it.copy(folderName = folderName) else it }
                .also { parsed -> logDropped(parsed.size, payload.emailRows().size) }
        }
    }

    override suspend fun trail(
        folderName: String,
        messageIds: List<String>,
    ): ZillitResult<List<EmailMessage>> {
        if (messageIds.isEmpty()) return ZillitResult.Success(emptyList())

        return apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}imap-emails/get-emails",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("folder_name", folderName)
                        put("message_ids", buildJsonArray { messageIds.forEach { add(JsonPrimitive(it)) } })
                    },
                ),
            ),
            // A read in a POST's clothing: named so a message opened online
            // opens again with the network gone. Ids sorted — the same thread
            // asked for in another order is the same question.
            options = CallOptions(
                cacheAs = "${api}imap-emails/get-emails/$folderName/${messageIds.sorted().joinToString(",")}" +
                    scope.cacheSuffix(),
            ),
        ).map { payload ->
            payload.emailRows()
                .mapNotNull(::readMessage)
                // The folder is known by the caller and not always echoed
                // back; a message that does not know its folder cannot be
                // replied to from Sent correctly, nor have its files fetched.
                .map { if (it.folderName.isBlank()) it.copy(folderName = folderName) else it }
                .asThread()
        }
    }

    override suspend fun send(message: OutgoingEmail): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}imap-send",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(scope.body(message.toPayload(fromHeader()))),
        ).map { }

    override suspend fun attachment(
        attachmentId: String,
        messageId: String,
        folderName: String,
    ): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}imap-emails/get-attachment",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("attachment_id", attachmentId)
                        put("message_id", messageId)
                        put("folder_name", folderName)
                    },
                ),
            ),
        ).map { payload ->
            (payload as? JsonObject)?.str("base64_file_content").orEmpty()
        }

    override suspend fun move(
        messageIds: List<String>,
        fromFolder: String,
        toFolder: String,
    ): ZillitResult<Unit> =
        apiClient.request(
            // PUT, not POST. Both other clients use PUT here, and POST on this
            // path is the delete-adjacent endpoint — getting it wrong silently
            // did nothing rather than erroring.
            verb = HttpVerb.Put,
            url = "${api}imap-emails",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("message_ids", buildJsonArray { messageIds.forEach { add(JsonPrimitive(it)) } })
                        put("source_folder", fromFolder)
                        put("target_folder", toFolder)
                    },
                ),
            ),
        ).map { }

    override suspend fun deletePermanently(messageIds: List<String>): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "${api}imap-emails",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = jsonBody(
                scope.body(
                    buildJsonObject {
                        put("message_ids", buildJsonArray { messageIds.forEach { add(JsonPrimitive(it)) } })
                    },
                ),
            ),
        ).map { }

    override suspend fun emptyTrash(): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "${api}imap-emails/empty-trash",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = scope.flagBody()?.let(::jsonBody),
        ).map { }

    private fun logDropped(parsed: Int, total: Int) {
        if (parsed < total) ZillitLog.w(TAG) { "dropped ${total - parsed} unreadable messages" }
    }

    private companion object {
        const val TAG = "Email"
    }
}

/**
 * Mail comes back as `{"emails": [...]}` from the index call and as a bare array
 * from the trail call. Accepting both keeps one reader for two endpoints.
 */
private fun JsonElement.emailRows(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["emails"] as? JsonArray) ?: (this["data"] as? JsonArray) ?: emptyList()
    else -> emptyList()
}

/**
 * The send payload.
 *
 * Each recipient is wrapped in `{"email_address": …}` and `references` is
 * space-joined per RFC 5322 — both are the wire's shape, and both are why the
 * composer keeps plain strings and this function does the translating.
 */
private fun OutgoingEmail.toPayload(from: String): JsonObject = buildJsonObject {
    put("to", addressArray(to))
    put("cc", addressArray(cc))
    put("bcc", addressArray(bcc))
    if (from.isNotBlank()) put("from", from)
    // "(No Subject)" rather than blank, as the web sends it: the recipient's
    // client shows something, and the Sent row does too.
    put("subject", subject.ifBlank { "(No Subject)" })
    put("body", body)
    put("references", references.joinToString(" "))
    // The original's files on a reply or forward — copies the mail service
    // already holds, named the way both phones name them.
    put("ingrained_attachment", buildJsonArray { forwarded.forEach { add(it.toWire()) } })
    // Empty rather than omitted, matching both other clients: the field is
    // always present and the server reads "" as "this was not a draft".
    put("email_draft_id", draftId.orEmpty())
    put("attachments", buildJsonArray { attachments.forEach { add(it.toWire()) } })
}





/**
 * A per-mailbox tail for read-cache names: the shared mailbox's folders and
 * threads are different answers, and must not be served for the personal one.
 */
internal fun MailboxScope.cacheSuffix(): String = if (isAccountsActive()) "?accounts" else ""

/**
 * An attachment as the phones send one back (`AttachmentDto`): everything
 * the server told us, so it can find the copy it already holds.
 */
internal fun EmailAttachment.toWire(): JsonObject = buildJsonObject {
    put("id", id)
    put("attachment_id", id)
    put("name", fileName)
    put("content_type", contentType.orEmpty())
    put("content_length", sizeBytes)
    put("size", sizeBytes)
    put("content_disposition", contentDisposition)
    contentId?.let { put("content_id", it) }
    media?.let { put("media", it) }
    bucket?.let { put("bucket", it) }
    region?.let { put("region", it) }
}

internal fun addressArray(addresses: List<String>): JsonArray = buildJsonArray {
    addresses.map(String::trim).filter(String::isNotEmpty).forEach { address ->
        add(buildJsonObject { put("email_address", address) })
    }
}
