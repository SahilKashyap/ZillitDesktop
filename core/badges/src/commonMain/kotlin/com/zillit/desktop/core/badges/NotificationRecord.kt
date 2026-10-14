package com.zillit.desktop.core.badges

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One row of the notification ledger — a badge-bearing event, as the
 * notification service records it.
 *
 * The columns Android lifts out of the wire row (`NotificationModelDB`) plus
 * the few `reference_data` fields the counting and read rules key on. [raw]
 * keeps the whole row as sent, so the chat listing can fold it the way it
 * folds the server's backlog and a rule that needs one more field need not
 * change the schema.
 */
@Suppress("LongParameterList") // A wire row, column for column.
data class NotificationRecord(
    /** `notification_uuid` — the primary key on every client. */
    val id: String,
    val projectId: String,
    /** `device_id` — which of this person's devices the row was written for. */
    val deviceId: String = "",
    /** The row's `_id`; what SOS and the global list delete by. */
    val mongoId: String = "",
    val section: String = "",
    val tool: String = "",
    val unit: String = "",
    val level1: String = "",
    val level2: String = "",
    val level3: String = "",
    val action: String = "",
    val referenceId: String = "",
    val sender: String = "",
    val receiver: String = "",
    val created: Long = 0L,
    val updated: Long = 0L,
    val messageRead: Boolean = false,
    val isGlobal: Boolean = false,
    /** `reference_data.ignore` — a row no client counts. */
    val ignored: Boolean = false,
    /** `reference_data.self` — this person's own action echoed back. */
    val self: Boolean = false,
    val silent: Boolean = false,
    val deleted: Boolean = false,
    val chatRoomId: String = "",
    val senderId: String = "",
    /** `reference_data.chat.unit_id` (or `reference_data.unit_id`) — a unit's chat, read with the unit. */
    val chatUnitId: String = "",
    /**
     * When a calendar invite stops being pending — see [calendarExpiryOf]. Null
     * is an invite that never expires (no end known, or a series with no end),
     * which stays on the pending side; it still counts.
     */
    val calendarExpiry: Long? = null,
    /** Whether the API handed this row over (it then moves the seed watermark) or the socket did. */
    val fromApi: Boolean = false,
    val raw: String = "",
) {
    /**
     * Whether this row is a badge right now.
     *
     * `silent` and `self` are news to nobody: a silent push carries no badge,
     * and an echo of this person's own action badges them for something they
     * did (web `AllBadges.jsx:338-341`). The socket seam dropped both
     * ([ledgerArrivalsFrom]) but the API's backlog pages carry the same rows
     * and nothing filtered those — so the very same event badged this desktop
     * or not depending on whether the app happened to be open when it fired.
     * The test belongs here, where every path passes.
     */
    val counts: Boolean get() = !messageRead && !ignored && !deleted && !silent && !self

    /**
     * The conversation a C&C chat row belongs to — iOS's grouping
     * (`getNotificationRootObject`): a DM (`chat_member_label`) by its sender,
     * every other unit — a group, a department room — by its room. A row that
     * names no room falls back to its sender, as the older writers filed DMs
     * with no unit. Null when the row names neither.
     *
     * The rule used to be the other way round (a group by room, everything
     * else by sender), which filed a department room's messages under the
     * DM of whoever wrote them.
     */
    val conversationKey: String?
        get() {
            if (tool != CHAT_TOOL) return null
            val bySender = senderId.takeIf { it.isNotBlank() } ?: sender.takeIf { it.isNotBlank() }
            return if (unit == CHAT_MEMBER_UNIT) bySender else chatRoomId.takeIf { it.isNotBlank() } ?: bySender
        }

    companion object {
        const val CHAT_TOOL = "chat_label"
        const val CALL_TOOL = "call_label"
        const val CHAT_GROUP_UNIT = "chat_group_label"
        const val CHAT_MEMBER_UNIT = "chat_member_label"
        const val CALENDAR_TOOL = "calendar_label"
        const val AD_DASHBOARD_TOOL = "ad_dashboard_label"
    }
}

/**
 * Reads one wire row into a record, or null for a row with no identity or
 * no production — neither can be filed anywhere.
 *
 * Every field is read leniently: the service sends numbers as strings,
 * booleans as `"true"`/`1`, and `reference_data` sometimes as a JSON string.
 */
fun notificationRecordFrom(
    row: JsonObject,
    fromApi: Boolean,
    fallbackProjectId: String? = null,
): NotificationRecord? {
    val reference = row.referenceData()
    val id = row.text("notification_uuid") ?: reference?.text("notification_id") ?: row.text("_id") ?: return null
    val projectId = row.text("project_id") ?: fallbackProjectId ?: return null
    val chat = reference?.get("chat") as? JsonObject
    return NotificationRecord(
        id = id,
        projectId = projectId,
        deviceId = row.text("device_id").orEmpty(),
        mongoId = row.text("_id").orEmpty(),
        section = row.text("section").orEmpty(),
        tool = row.text("tool").orEmpty(),
        unit = row.text("unit").orEmpty(),
        level1 = row.text("level_1").orEmpty(),
        level2 = row.text("level_2").orEmpty(),
        level3 = row.text("level_3").orEmpty(),
        action = row.text("action").orEmpty(),
        referenceId = row.text("reference_id").orEmpty(),
        sender = row.text("sender").orEmpty(),
        receiver = row.text("receiver").orEmpty(),
        created = row.long("created") ?: 0L,
        updated = row.long("updated") ?: row.long("created") ?: 0L,
        messageRead = row.flag("message_read"),
        isGlobal = row.flag("is_global"),
        ignored = reference?.flag("ignore") == true,
        self = reference?.flag("self") == true,
        silent = row.flag("silent"),
        deleted = row.flag("deleted") || (row.long("deleted") ?: 0L) > 0L,
        chatRoomId = reference?.text("chat_room_id").orEmpty(),
        senderId = reference?.text("sender_id").orEmpty(),
        chatUnitId = chat?.text("unit_id") ?: reference?.text("unit_id").orEmpty(),
        calendarExpiry = calendarExpiryOf(reference),
        fromApi = fromApi,
        raw = row.toString(),
    )
}

/**
 * When a calendar invite's event is over — iOS `isCalendarNotificationExpired`
 * (ZL-18722), which mirrors the calendar's own `isSeriesExpired`:
 *
 * - `calendar_data` for a one-off event: its `end_datetime`;
 * - for a series (`repeat_status > 0`): its `repeat_end_date`, and a series
 *   with no end date never expires;
 * - otherwise the legacy `reference_data.expired` stamp, which invites written
 *   before `calendar_data` existed still carry;
 * - neither: never expired.
 *
 * A zero is "not set", not the epoch. The row counts either way — iOS badges
 * every unread invite on Home, pending and expired alike; this only decides
 * which of the two the calendar files it under, and what reading the
 * Expired tab clears.
 */
internal fun calendarExpiryOf(reference: JsonObject?): Long? {
    reference ?: return null
    val calendar = reference["calendar_data"] as? JsonObject
    if (calendar != null) {
        val series = (calendar.long("repeat_status") ?: 0L) > 0L
        val end = calendar.long(if (series) "repeat_end_date" else "end_datetime")
        if (end != null && end > 0L) return end
    }
    return reference.long("expired")?.takeIf { it > 0L }
}

/** Whether a calendar invite's event is over at [nowMillis] — see [calendarExpiryOf]. */
fun NotificationRecord.isCalendarExpired(nowMillis: Long): Boolean =
    tool == NotificationRecord.CALENDAR_TOOL && calendarExpiry != null && calendarExpiry < nowMillis

/** The row as the chat listing folds it: the wire row, with the ledger's word on `message_read`. */
fun NotificationRecord.asWireRow(): JsonObject? {
    val row = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    return JsonObject(row + ("message_read" to JsonPrimitive(messageRead)))
}

internal fun JsonObject.referenceData(): JsonObject? = when (val reference = this["reference_data"]) {
    is JsonObject -> reference
    is JsonPrimitive -> if (!reference.isString) {
        null
    } else {
        runCatching { Json.parseToJsonElement(reference.content) }.getOrNull() as? JsonObject
    }
    else -> null
}

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true" || it.contentOrNull == "1") }
        ?: false

/**
 * A list of ids, however the service spelled it: a JSON array, a string
 * holding a JSON array, or a comma-separated string — the three shapes iOS
 * decodes for every `notification:silent` list (`ReferenceData.init(from:)`).
 * A parser that only opened the array dropped the whole instruction when the
 * server sent a string, and the badge simply stayed.
 */
internal fun JsonObject.strings(key: String): Set<String> = when (val value = this[key]) {
    is kotlinx.serialization.json.JsonArray -> value.idList()
    is JsonPrimitive -> if (!value.isString) {
        emptySet()
    } else {
        val text = value.content.trim()
        val parsed = if (text.startsWith("[")) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
        (parsed as? kotlinx.serialization.json.JsonArray)?.idList()
            ?: text.split(',').map { it.trim().trim('"') }.filter { it.isNotBlank() && it != "null" }.toSet()
    }
    else -> emptySet()
}

private fun kotlinx.serialization.json.JsonArray.idList(): Set<String> =
    mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.toSet()

/**
 * The record(s) inside a socket frame.
 *
 * The frame's shape is not fixed: the record itself, `{"data": {...}}`,
 * `data` as a JSON **string** (the server does send that), an array of
 * records, or `data` nested once more. A parser that only opened one shape
 * would make a badge simply never move — the failure that looks like nothing.
 */
fun JsonElement?.wireRecords(): List<JsonObject> = when (this) {
    is JsonObject -> when (val inner = this["data"]) {
        null -> listOf(this)
        is JsonObject -> inner["data"]?.wireRecords()?.takeIf { it.isNotEmpty() } ?: listOf(inner)
        is kotlinx.serialization.json.JsonArray -> inner.flatMap { it.wireRecords() }
        is JsonPrimitive -> if (!inner.isString) {
            listOf(this)
        } else {
            runCatching { Json.parseToJsonElement(inner.content) }.getOrNull()?.wireRecords() ?: listOf(this)
        }
        else -> listOf(this)
    }
    is kotlinx.serialization.json.JsonArray -> flatMap { it.wireRecords() }
    is JsonPrimitive -> if (!isString) {
        emptyList()
    } else {
        runCatching { Json.parseToJsonElement(content) }.getOrNull()?.wireRecords().orEmpty()
    }
    else -> emptyList()
}
