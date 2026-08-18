package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.sync.RetryPolicy
import com.zillit.desktop.core.sync.SyncContext
import com.zillit.desktop.core.sync.SyncHandler
import com.zillit.desktop.core.sync.SyncOperation
import com.zillit.desktop.core.sync.SyncOutcome
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatSendState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The outbox kind for a message written with no network. */
const val CHAT_SEND_KIND = "chat.send"

/**
 * What the outbox carries for a queued message: everything `send` needs. Text
 * only — a file needs uploading first, which is its own port; a captioned
 * attachment written offline still fails the way it did.
 */
@Serializable
data class QueuedChatSend(
    val receiverId: String,
    val body: String,
    val uniqueId: String,
    val timestampMillis: Long,
    val isGroup: Boolean,
)

/**
 * Sends a queued message once the socket is back.
 *
 * The socket layer already keys every send on the client-minted `uniqueId`,
 * so a retry after an ack that was lost cannot duplicate — the server upserts
 * on it, as the phones rely on. "Socket is not connected", an ack that timed
 * out, a production still opening (no session or project yet, the chat key
 * not yet fetched — the engine runs the moment the scope exists, seconds
 * before the socket and the credential bundle) are all time's to fix; only
 * the server saying no is final.
 */
class ChatSendHandler(
    private val repository: ChatRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val policy: RetryPolicy = RetryPolicy(),
) : SyncHandler {

    override val kind: String = CHAT_SEND_KIND

    override suspend fun execute(operation: SyncOperation, context: SyncContext): SyncOutcome {
        val queued = runCatching { json.decodeFromString(QueuedChatSend.serializer(), operation.payload) }
            .getOrElse { return SyncOutcome.Failed(ZillitError.Serialization(it.message)) }
        return when (
            val sent = repository.send(
                receiverId = queued.receiverId,
                body = queued.body,
                uniqueId = queued.uniqueId,
                nowMillis = queued.timestampMillis,
                isGroup = queued.isGroup,
                attachment = null,
            )
        ) {
            is ZillitResult.Success -> SyncOutcome.Done(result = queued.uniqueId)
            is ZillitResult.Failure -> when {
                policy.isRetryable(sent.error) -> SyncOutcome.RetryLater(sent.error)
                // The socket answered nothing in time — the room may not have
                // been joined yet after a reconnect. Not a refusal.
                sent.error is ZillitError.Unknown && sent.error.technical.orEmpty().startsWith(ACK_FAILED) ->
                    SyncOutcome.RetryLater(sent.error)
                // The production is still opening: no user/project published
                // yet, or the chat key has not arrived so nothing can be
                // encrypted. Both resolve by themselves within seconds.
                sent.error is ZillitError.Unauthorized || sent.error is ZillitError.Storage ->
                    SyncOutcome.RetryLater(sent.error)
                else -> SyncOutcome.Failed(sent.error)
            }
        }
    }

    private companion object {
        const val ACK_FAILED = "ack failed"
    }
}

/**
 * The queued message as a bubble in its thread — clock while it waits, the
 * failure mark if the server refused it. Null for anything else in the outbox
 * and for messages to other threads.
 */
fun SyncOperation.toQueuedBubble(peerId: String, json: Json = Json { ignoreUnknownKeys = true }): ChatMessage? {
    if (kind != CHAT_SEND_KIND || state == SyncState.Done) return null
    val queued = runCatching { json.decodeFromString(QueuedChatSend.serializer(), payload) }.getOrNull()
        ?: return null
    if (queued.receiverId != peerId) return null
    return ChatMessage(
        id = queued.uniqueId,
        uniqueId = queued.uniqueId,
        senderId = "me",
        receiverId = queued.receiverId,
        body = queued.body,
        timestampMillis = queued.timestampMillis,
        isMine = true,
        sendState = if (state == SyncState.Failed) ChatSendState.Failed else ChatSendState.Queued,
        isGroup = queued.isGroup,
    )
}
