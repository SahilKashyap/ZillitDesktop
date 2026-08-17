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
) {

    val stream: Flow<HomeRealtimeEvent> =
        events.onAny(ZillitSocketEvents.Home.All).mapNotNull(::toEvent)

    private fun toEvent(message: SocketMessage): HomeRealtimeEvent? {
        if (message.event in ZillitSocketEvents.Home.Units) return HomeRealtimeEvent.UnitsChanged

        // The wire nests the interesting object under `data`, sometimes as a
        // JSON **string** rather than an object — Android re-parses it for the
        // same reason (`baseSocketParser`).
        val body = message.payload?.unwrapData() ?: return null
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

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
