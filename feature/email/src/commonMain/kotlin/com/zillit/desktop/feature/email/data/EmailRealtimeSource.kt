package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns the mailbox's socket traffic into typed events.
 *
 * The socket layer knows nothing about mail — it hands over named payloads.
 * This is where `inbound:email:received` becomes something the mailbox can act
 * on, and the only place in the app that knows that mapping.
 */
class EmailRealtimeSource(private val events: SocketEventBus) {

    val stream: Flow<EmailRealtimeEvent> =
        events.onAny(ZillitSocketEvents.Email.All)
            // Logged because a wrong wire name fails *silently* — the filter
            // simply never matches — and that is indistinguishable from "no
            // mail arrived". One line makes the difference visible.
            .onEach { ZillitLog.d(TAG) { "socket: ${it.event.value}" } }
            .mapNotNull(::toEmailEvent)

    private companion object {
        const val TAG = "Email"
    }
}

/**
 * Maps one socket message.
 *
 * Top-level and `internal` so tests exercise the real mapping rather than a
 * copy, and without standing up a socket to do it.
 */
internal fun toEmailEvent(message: SocketMessage): EmailRealtimeEvent? {
    if (message.event in ZillitSocketEvents.Email.Folders) return EmailRealtimeEvent.FoldersChanged
    if (message.event in ZillitSocketEvents.Email.Drafts) return EmailRealtimeEvent.DraftsChanged

    // The interesting object is nested under `data`, sometimes as a JSON
    // *string* rather than an object — same envelope as the notice board.
    val body = message.payload?.unwrapMailData()

    if (message.event == ZillitSocketEvents.Email.Read) {
        // Shares the readers' coercion: uid ships as a number on some events
        // and a string on others. Zero means absent.
        val uid = body?.int("uid") ?: 0
        return if (uid > 0) EmailRealtimeEvent.ReadChanged(uid) else null
    }

    return EmailRealtimeEvent.FolderChanged(message.folderHint(body))
}

/**
 * Which folder an event concerns.
 *
 * Most payloads name it. Where they do not, the event name still implies it:
 * mail *sent* lands in Sent, mail *received* lands in Inbox. Guessing here is
 * safe because the worst case is refreshing a folder that had not changed.
 *
 * A move touches two folders and names only one, so it returns null — the
 * client then refreshes whatever is open, which is the folder the user is
 * looking at and therefore the one they would notice being wrong.
 */
private fun SocketMessage.folderHint(body: JsonObject?): String? {
    body?.str("folder_name")?.let { return it }

    return when (event) {
        ZillitSocketEvents.Email.OutboundSent, ZillitSocketEvents.Email.OutboundDeleted ->
            EmailFolder.SENT
        ZillitSocketEvents.Email.InboundReceived, ZillitSocketEvents.Email.InboundDeleted,
        ZillitSocketEvents.Email.TrailDeleted,
        -> EmailFolder.INBOX
        ZillitSocketEvents.Email.TrashEmptied -> EmailFolder.TRASH
        else -> null
    }
}

/**
 * Digs the payload object out of the envelope.
 *
 * Accepts the object directly, `{"data": {...}}`, `{"data": "{...}"}` (a JSON
 * string, which the server does send) and `{"data": [{...}]}`.
 *
 * Unlike the notice board's version this does not require an `_id`: mail events
 * carry `uid` and `folder_name`, and several carry neither an id nor anything
 * else — an empty object is still a valid "something changed" signal.
 */
internal fun JsonElement.unwrapMailData(): JsonObject? = when (this) {
    is JsonObject -> (this["data"] ?: this["email"])?.unwrapMailData() ?: this
    is JsonArray -> firstOrNull()?.unwrapMailData()
    // JsonNull is a JsonPrimitive, so this covers every case.
    is JsonPrimitive ->
        if (!isString) null
        else runCatching { Json.parseToJsonElement(content) }.getOrNull()?.unwrapMailData()
}

