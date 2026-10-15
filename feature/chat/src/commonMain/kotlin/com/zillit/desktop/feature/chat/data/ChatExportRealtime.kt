package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.chat.domain.ChatExport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One `chat:export:updated` event: the whole export, and the production it
 * belongs to.
 *
 * The production is carried separately because the payload names it in the
 * envelope rather than inside the export, and the gate it feeds is the whole
 * reason this type exists — see [ChatExportRealtimeSource].
 */
data class ChatExportUpdate(
    val export: ChatExport,
    /** `project_id` from the envelope, or null when the frame did not say. */
    val projectId: String?,
)

/**
 * The export's live progress, off the socket C&C already has open.
 *
 * The server pushes when processing starts, roughly every 30 seconds while
 * it runs, and on completion or failure. It does **not** push a cancellation
 * or a removal — the `DELETE` answer is that — so this stream is never the
 * only way the dialog learns something, and the fallback poll covers the
 * rest. A temporary failure sends the export back to `queued` and it retries
 * within minutes, so `processing → queued` arrives here and is normal.
 *
 * [projectId] is the open production, read per event rather than captured:
 * one socket serves every production this device is on, and the user's own
 * room spans all of them. Without the gate, an export started in another
 * production would drive this one's dialog — the same mistake the chat
 * frames guard against with [frameProjectId].
 */
class ChatExportRealtimeSource(
    events: SocketEventBus,
    private val projectId: () -> String?,
) {

    val stream: Flow<ChatExportUpdate> =
        events.on(ZillitSocketEvents.ChatExport.Updated)
            .mapNotNull(::toChatExportUpdate)
            .mapNotNull { update -> update.takeIf { it.belongsTo(projectId()) } }
}

/**
 * Whether this update is about [openProjectId].
 *
 * An update that does not name a production is taken: the contract says the
 * envelope carries one, and dropping an unnamed frame would silently stall
 * a dialog if the server ever stopped sending it. An update that names a
 * *different* production is always dropped.
 */
internal fun ChatExportUpdate.belongsTo(openProjectId: String?): Boolean =
    projectId == null || openProjectId == null || projectId == openProjectId

/**
 * Maps one socket message.
 *
 * Top-level and `internal` so the mapping is tested for real rather than in
 * a copy, and without standing up a socket to do it.
 */
internal fun toChatExportUpdate(message: SocketMessage): ChatExportUpdate? {
    val envelope = message.payload as? JsonObject ?: return null
    // Same envelope as the rest of the C&C events: the record is under
    // `data`, sometimes as a JSON *string* rather than an object.
    val record = envelope["data"].chatExportRecord() ?: return null
    val export = runCatching { EXPORT_JSON.decodeFromJsonElement(ChatExportDto.serializer(), record) }
        .getOrNull()
        ?.toDomain()
        ?: return null

    return ChatExportUpdate(export = export, projectId = frameProjectId(envelope))
}

// `coerceInputValues` for the same reason as the repository's reader: a null
// where a value is declared must not drop the whole update.
private val EXPORT_JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
