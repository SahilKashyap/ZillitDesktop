package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns Home's socket traffic into typed events.
 *
 * The socket layer knows nothing about notice boards — it hands over named
 * payloads. This is where `home:message:added` becomes something the board can
 * act on, and it is the only place in the app that knows that mapping.
 */
class HomeRealtimeSource(
    private val events: SocketEventBus,
    private val decryptBody: (String) -> String,
    /** Who is signed in — rights events for anyone else are not this board's. */
    private val myUserId: () -> String? = { null },
) {

    val stream: Flow<HomeRealtimeEvent> =
        events.onAny(ZillitSocketEvents.Home.All + ZillitSocketEvents.AccessGrid.All)
            .mapNotNull(::toEvent)

    private fun toEvent(message: SocketMessage): HomeRealtimeEvent? = when {
        message.event in ZillitSocketEvents.Home.Units -> HomeRealtimeEvent.UnitsChanged

        // A receipt, not a post: the board shows nothing for it, and only an
        // open read-by panel is listening.
        message.event == ZillitSocketEvents.Home.MessageReadBy ->
            message.payload?.readByMessageId()?.let(HomeRealtimeEvent::ReadByChanged)

        // A comment's payload is the comment, not the notice it belongs to,
        // so there is nothing to patch in place — the board is re-read, which
        // is what every other board does with the same events.
        message.event in ZillitSocketEvents.Home.Comments -> HomeRealtimeEvent.UnitsChanged

        // An admin moved MY rights: the tab strip and the composer's gate are
        // both stale, so the unit list is read afresh — Android's own
        // fallback branch, minus its in-place patching (QA #18: rights taken
        // away kept working until the app restarted). Somebody else's rights
        // are their board's business.
        message.event in ZillitSocketEvents.AccessGrid.All ->
            HomeRealtimeEvent.UnitsChanged.takeIf {
                message.payload?.rightsTargetUserId() == myUserId()
            }

        else -> message.payload?.unwrapData()?.let { body -> boardEvent(message, body) }
    }

    /** The per-board events, once the payload has been unwrapped. */
    private fun boardEvent(message: SocketMessage, body: JsonObject): HomeRealtimeEvent? {
        // The wire nests the interesting object under `data`, sometimes as a
        // JSON **string** rather than an object — Android re-parses it for the
        // same reason (`baseSocketParser`).
        val unitId = body.stringField("unit_id")

        return when (message.event) {
            ZillitSocketEvents.Home.MessageAdded ->
                readNotice(body, decryptBody)?.let { HomeRealtimeEvent.NoticeAdded(unitId, it) }

            ZillitSocketEvents.Home.MessageEdited ->
                readNotice(body, decryptBody)?.let { HomeRealtimeEvent.NoticeEdited(unitId, it) }

            ZillitSocketEvents.Home.MessageDeleted ->
                body.stringField("_id")?.let { HomeRealtimeEvent.NoticeDeleted(unitId, it) }

            // The multi-delete carries ids rather than a post; reloading is
            // simpler than removing n things by hand, and it is rare. Any Home
            // event not named above lands here for the same reason.
            else -> HomeRealtimeEvent.UnitsChanged
        }
    }
}

/**
 * Digs the payload object out of the envelope.
 *
 * Accepts the object directly, `{"data": {...}}`, `{"data": "{...}"}` (a JSON
 * string, which the server does send) and `{"data": [{...}]}`. Being generous
 * here is cheaper than a class of silent no-ops where a board simply never
 * updates.
 */
internal fun JsonElement.unwrapData(): JsonObject? {
    val inner = (this as? JsonObject)?.get("data") ?: this

    return when (inner) {
        is JsonObject -> if (inner.containsKey("_id")) inner else inner["data"]?.unwrapData()
        is JsonArray -> inner.firstOrNull()?.unwrapData()
        is JsonPrimitive ->
            if (!inner.isString) null
            else runCatching { Json.parseToJsonElement(inner.content) }.getOrNull()?.unwrapData()
        else -> null
    }
}

/** Internal, not private: [BoardRealtimeEvents]' mapping reads the same keys. */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * Whose rights an access-grid event moves.
 *
 * The payload is an array whose first element names the person as `user_id`
 * or `_id` (Android's `baseSocketWithUserIDParser`). Not [unwrapData]: that
 * helper insists on an `_id` key inside `data` envelopes, and these rows
 * are bare.
 */
internal fun JsonElement.rightsTargetUserId(): String? {
    val row = when (this) {
        is JsonArray -> firstOrNull() as? JsonObject
        is JsonObject -> this
        else -> null
    } ?: return null
    return row.stringField("user_id") ?: row.stringField("_id")
}
