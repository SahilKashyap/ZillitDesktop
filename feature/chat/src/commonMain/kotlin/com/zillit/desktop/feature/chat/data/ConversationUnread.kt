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
fun conversationUnreadFrom(payload: JsonElement): Map<String, Int> =
    conversationBacklogFrom(payload).unread

/**
 * The backlog in full: [conversationUnreadFrom]'s counts, plus each
 * conversation's newest `created` across every chat row — read rows and our
 * own echoes included, since a thread we read on the phone or wrote into from
 * it still moved.
 */
/**
 * @param readMarks what this device knows to be read per conversation — its own
 *   reads, reads relayed from the phones (`selfReads`) and `notification:silent`
 *   prunes. A row no newer than the mark does not count: the server keeps rows
 *   the phones have long marked read, and they would otherwise sit in the badge
 *   for good (seen live 2026-09-03: a C&C badge of 8 over two silent threads).
 * @param silencedIds message ids a `notification:silent` told every device to
 *   drop (`deleted_chat_ids`); the server relays that instruction but never
 *   applies it to its own rows.
 */
fun conversationBacklogFrom(
    payload: JsonElement,
    readMarks: Map<String, Long> = emptyMap(),
    silencedIds: Set<String> = emptySet(),
): ConversationBacklog {
    val rows = when (payload) {
        is JsonArray -> payload
        is JsonObject -> payload["data"] as? JsonArray
        else -> null
    } ?: return ConversationBacklog()
    val tally = BacklogTally(readMarks, silencedIds)
    rows.forEach { element -> (element as? JsonObject)?.let(tally::add) }
    return tally.backlog()
}

/** The four maps a backlog folds into, filled one row at a time. */
private class BacklogTally(private val readMarks: Map<String, Long>, private val silencedIds: Set<String>) {
    private val counts = mutableMapOf<String, Int>()
    private val newest = mutableMapOf<String, Long>()
    private val rooms = mutableSetOf<String>()
    private val messageKeys = mutableMapOf<String, String>()

    fun add(row: JsonObject) {
        val key = row.conversationKey() ?: return
        if (row.text("unit") == GROUP_UNIT) rooms += key
        val created = row.createdMillis()
        created?.let { at -> newest[key] = maxOf(newest[key] ?: 0L, at) }
        val messageId = row.messageId()
        if (messageId != null && messageId in silencedIds) return
        if (row.countsAsUnread(created, readMarks[key])) {
            counts[key] = (counts[key] ?: 0) + 1
            messageId?.let { messageKeys[it] = key }
        }
    }

    fun backlog() = ConversationBacklog(unread = counts, activity = newest, rooms = rooms, messageKeys = messageKeys)
}

/** Unread on the server, not our echo, and newer than what this device knows to be read. */
private fun JsonObject.countsAsUnread(created: Long?, readMark: Long?): Boolean {
    val readHere = created != null && created <= (readMark ?: 0L)
    return isUnread() && !isSelfEcho() && !readHere
}

/** The chat message a notification row is about, under whichever key this server version used. */
private fun JsonObject.messageId(): String? {
    val reference = this["reference_data"] as? JsonObject
    return listOfNotNull(reference?.text("chat_id"), reference?.text("message_id"), text("reference_id"))
        .firstOrNull { it.isNotBlank() }
}

/** The conversation this row belongs to, or null when it is not a chat row. */
private fun JsonObject.conversationKey(): String? {
    if (!isChatRow()) return null
    val reference = this["reference_data"] as? JsonObject
    if (reference?.flag("ignore") == true) return null
    val key = if (text("unit") == GROUP_UNIT) {
        reference?.text("chat_room_id")
    } else {
        reference?.text("sender_id") ?: text("sender")
    }
    return key?.takeIf { it.isNotBlank() }
}

/** A live chat notification: the CNC section's chat tool, not deleted. */
private fun JsonObject.isChatRow(): Boolean {
    if (text("section") != CNC_SECTION || text("tool") != CHAT_TOOL) return false
    val deleted = (this["deleted"] as? JsonPrimitive)?.longOrNull ?: 0L
    return deleted <= 0L
}

private fun JsonObject.isUnread(): Boolean = !flag("message_read") && !flag("silent")

/** Our own message, notified back to us from another device — it badges nothing. */
private fun JsonObject.isSelfEcho(): Boolean =
    (this["reference_data"] as? JsonObject)?.flag("self") == true

private fun JsonObject.createdMillis(): Long? =
    (this["created"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        ?.takeIf { it > 0L }

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

private const val CNC_SECTION = "cnc_label"
private const val CHAT_TOOL = "chat_label"
private const val GROUP_UNIT = "chat_group_label"
