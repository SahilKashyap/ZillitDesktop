package com.zillit.desktop.feature.chat.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Per-conversation unread from the notification backlog — the rows of
 * `project/all/notifications`, as both phones and the web fold them
 * (Android `CommonBadgesHandler.groupCncLabelData`, web `readCncBadgesFromLocalDB`).
 *
 * A row counts when it is a chat notification (`section` cnc_label, `tool`
 * chat_label), unread, not deleted, and not one of our own echoes. Rooms
 * (`unit` chat_group_label) key by `reference_data.chat_room_id`; DMs by the
 * sender — `reference_data.sender_id` first, the row's own `sender` as the
 * fallback. Rows that name neither cannot be placed and are dropped.
 */
fun conversationUnreadFrom(payload: JsonElement): Map<String, Int> {
    val rows = when (payload) {
        is JsonArray -> payload
        is JsonObject -> payload["data"] as? JsonArray
        else -> null
    } ?: return emptyMap()
    val counts = mutableMapOf<String, Int>()
    rows.forEach { element ->
        val key = (element as? JsonObject)?.conversationKeyIfUnread() ?: return@forEach
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts
}

/** The conversation this row badges, or null when it badges nothing. */
private fun JsonObject.conversationKeyIfUnread(): String? {
    if (!isUnreadChatRow()) return null
    val reference = this["reference_data"] as? JsonObject
    if (reference?.flag("self") == true || reference?.flag("ignore") == true) return null
    val key = if (text("unit") == GROUP_UNIT) {
        reference?.text("chat_room_id")
    } else {
        reference?.text("sender_id") ?: text("sender")
    }
    return key?.takeIf { it.isNotBlank() }
}

private fun JsonObject.isUnreadChatRow(): Boolean {
    if (text("section") != CNC_SECTION || text("tool") != CHAT_TOOL) return false
    if (flag("message_read") || flag("silent")) return false
    val deleted = (this["deleted"] as? JsonPrimitive)?.longOrNull ?: 0L
    return deleted <= 0L
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

private const val CNC_SECTION = "cnc_label"
private const val CHAT_TOOL = "chat_label"
private const val GROUP_UNIT = "chat_group_label"
