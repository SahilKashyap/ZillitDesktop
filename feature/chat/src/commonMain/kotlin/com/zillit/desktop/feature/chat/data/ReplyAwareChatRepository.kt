package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatReplyRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A repository that can carry a reply reference on the wire.
 *
 * Separate from [ChatRepository] because that interface (and its
 * implementation, and `sendEnvelope` in ChatWire.kt) are being edited
 * concurrently by another session: the view model asks for this capability
 * with `as?` and falls back to a plain [ChatRepository.send] — the message
 * still goes, just without the quote — until the repository adopts it by
 * sending the parent id as the envelope's `reply` string.
 */
interface ReplyAwareChatRepository {
    @Suppress("LongParameterList") // Mirrors ChatRepository.send plus the reference.
    suspend fun sendWithReply(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean,
        attachment: ChatAttachment?,
        replyTo: ChatReplyRef,
        /** A shared place, exactly as [ChatRepository.send] carries one. */
        location: com.zillit.desktop.feature.chat.domain.ChatLocation? = null,
    ): ZillitResult<Unit>
}


/**
 * The quoted parent off a received message — the `reply` object Android
 * stores on `ChatAndGroupModel.reply` (`ChatAndGroupRequestModelHandler.kt:47`)
 * and renders through `handleReplyMessage` (`HoldersViewhandler.kt:780-797`):
 * the words decrypt like a body, and a wordless quote falls back to naming
 * the file. Null when the message quotes nothing, or the object is too
 * broken to say what it quotes.
 */
fun readReplyRef(message: JsonObject, decrypt: (String) -> String?): ChatReplyRef? {
    // The echo of our own send may still carry the id string we put in;
    // the expanded object arrives on history rows and everyone else's copy.
    (message["reply"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { id ->
        return ChatReplyRef(messageId = id, senderId = "", body = "", kind = "text", attachmentName = "")
    }
    val reply = message["reply"] as? JsonObject ?: return null
    val messageId = reply.text("message_id") ?: return null
    val cipher = reply.text("message")
    val attachment = (reply["attachment"] as? JsonObject)?.text("name")
    return ChatReplyRef(
        messageId = messageId,
        senderId = reply.text("sender").orEmpty(),
        body = cipher?.let { decrypt(it) ?: it }.orEmpty(),
        kind = reply.text("message_type") ?: "text",
        attachmentName = attachment.orEmpty(),
    )
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
