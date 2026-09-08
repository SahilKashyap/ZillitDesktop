package com.zillit.desktop.feature.chat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The chat half of a `notification:silent` frame — the instruction every
 * phone and the web apply to their local badge ledgers and the server never
 * applies to its own rows: rooms the user lost (`chat_room_no_access`, the
 * room left, deleted or kicked from) and messages since deleted
 * (`deleted_chat_ids`). The tool and unit halves live in the home module.
 */
data class ChatSilence(
    val rooms: Set<String> = emptySet(),
    val deletedMessageIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = rooms.isEmpty() && deletedMessageIds.isEmpty()
}

/** Null when the frame carries no chat instruction; the shape is tolerated as the badge frames are. */
fun chatSilenceFrom(payload: JsonElement?): ChatSilence? {
    val record = payload.record() ?: return null
    val reference = (record["reference_data"] as? JsonObject) ?: record
    val silence = ChatSilence(
        rooms = reference.strings("chat_room_no_access") + record.strings("chat_room_no_access"),
        deletedMessageIds = reference.strings("deleted_chat_ids") + record.strings("deleted_chat_ids"),
    )
    return silence.takeUnless { it.isEmpty }
}

private fun JsonElement?.record(): JsonObject? = when (this) {
    is JsonObject -> when (val inner = this["data"]) {
        null -> this
        is JsonObject -> inner["data"]?.record() ?: inner
        is JsonArray -> inner.firstOrNull()?.record()
        is JsonPrimitive ->
            if (!inner.isString) this
            else runCatching { Json.parseToJsonElement(inner.content) }.getOrNull()?.record() ?: this
        else -> this
    }
    is JsonArray -> firstOrNull()?.record()
    is JsonPrimitive ->
        if (!isString) null
        else runCatching { Json.parseToJsonElement(content) }.getOrNull()?.record()
    else -> null
}

private fun JsonObject.strings(key: String): Set<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        ?.toSet()
        .orEmpty()
