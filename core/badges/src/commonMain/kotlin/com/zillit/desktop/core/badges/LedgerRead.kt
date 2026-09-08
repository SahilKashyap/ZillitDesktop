package com.zillit.desktop.core.badges

/**
 * What one read clears in the ledger — Android's `markReadCommon` variants,
 * one per surface (`CommonBadgesHandler.kt`, cited per case).
 *
 * None carries a time cutoff: Android flips every unread row the scope
 * names, whenever it was written.
 */
sealed interface LedgerRead {

    fun matches(row: NotificationRecord): Boolean

    /**
     * A board or unit opened — `Home`/`Default`:
     * `!isGlobal && (unit == id || chatUnitId == id)` (5396-5414, 8093-8100).
     */
    data class Board(val unitId: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            !row.isGlobal && (row.unit == unitId || row.chatUnitId == unitId)
    }

    /** A tool fronted — `Info`, `Production`, `MissedCall`…: `tool == label` (5568-5584, 6031-6043). */
    data class Tool(val wireLabel: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean = row.tool == wireLabel
    }

    /** A whole area — `SosBadge`, `GlobalRead`: `section == key` (7356-7365, 7434-7444). */
    data class Section(val section: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean = row.section == section
    }

    /**
     * One entity — an email, an approval request, an SOS alert:
     * `referenceId == id` (6054-6070), or the row's own ids.
     */
    data class Reference(val id: String, val unit: String? = null) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            (row.referenceId == id || row.id == id || row.mongoId == id) && (unit == null || row.unit == unit)
    }

    /** A conversation read — `markReadyByChatRoomIdSenderId`: room, sender or reference (2072-2109). */
    data class Conversation(val key: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.chatRoomId == key || row.senderId == key || row.referenceId == key
    }

    /** A tool's tab — `markReadCommon(isLCW = true)`: every level given is an equality (1442-1474). */
    data class Levels(
        val tool: String,
        val unit: String? = null,
        val level1: String? = null,
        val level2: String? = null,
        val level3: String? = null,
        val action: String? = null,
    ) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.tool == tool &&
                unit.isNullOr(row.unit) && level1.isNullOr(row.level1) && level2.isNullOr(row.level2) &&
                level3.isNullOr(row.level3) && action.isNullOr(row.action)

        private fun String?.isNullOr(value: String): Boolean = this == null || this == value
    }

    /** The calendar opened — `markReadExpiredEvents`: every invite whose event has ended (2028-2070). */
    data class CalendarExpired(val nowMillis: Long) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.tool == NotificationRecord.CALENDAR_TOOL && (row.calendarEnd ?: Long.MAX_VALUE) < nowMillis
    }
}

/**
 * The `notification:silent` instruction — what another device, or the
 * server, has already dropped. Every key Android's `markReadByNotificationId`
 * handles (`AppBadgeDBManager.kt:1772-1967`); the phones apply it to their
 * own rows because the server never applies it to its.
 */
data class BadgeSilence(
    /** `read_notification_ids` (and the frame's own uuid) — rows read elsewhere. */
    val readIds: Set<String> = emptySet(),
    /** `deleted_chat_ids` — chat messages since deleted. */
    val deletedChatIds: Set<String> = emptySet(),
    /** `deleted_comment_ids` — unit-chat comments since deleted. */
    val deletedCommentIds: Set<String> = emptySet(),
    /** `chat_room_no_access` — rooms left, deleted or kicked from. */
    val lostRooms: Set<String> = emptySet(),
    /** `tools_no_view_access` — wire tool labels this person may no longer see. */
    val lostTools: Set<String> = emptySet(),
    /** `unit_no_view_access` — units likewise. */
    val lostUnits: Set<String> = emptySet(),
    /** `admin_settings_no_access` — admin rights taken away; matches unit or tool. */
    val lostAdminSettings: Set<String> = emptySet(),
    /** `reference_data.self_device_id` — a frame from this very device is not news. */
    val selfDeviceId: String? = null,
    val projectId: String? = null,
) {
    val isEmpty: Boolean
        get() = readIds.isEmpty() && deletedChatIds.isEmpty() && deletedCommentIds.isEmpty() &&
            lostRooms.isEmpty() && lostTools.isEmpty() && lostUnits.isEmpty() && lostAdminSettings.isEmpty()

    private val byId: Set<String> get() = readIds + deletedChatIds + deletedCommentIds

    /** Whether one row is something this instruction drops. */
    fun matches(row: NotificationRecord): Boolean =
        namesRow(row) || row.level3 in lostRooms || row.chatRoomId in lostRooms || losesAccess(row)

    private fun namesRow(row: NotificationRecord): Boolean =
        row.id in byId || row.referenceId in byId || row.mongoId in byId

    private fun losesAccess(row: NotificationRecord): Boolean =
        row.tool in lostTools || row.unit in lostUnits || row.unit in lostAdminSettings || row.tool in lostAdminSettings
}

/** Reads a `notification:silent` frame; null when it carries no instruction. */
fun badgeSilenceFrom(payload: kotlinx.serialization.json.JsonElement?): BadgeSilence? {
    val record = payload.wireRecords().firstOrNull() ?: return null
    val reference = record.referenceData() ?: record
    val silence = BadgeSilence(
        readIds = reference.strings("read_notification_ids").ifEmpty { setOfNotNull(record.text("notification_uuid")) },
        deletedChatIds = reference.strings("deleted_chat_ids") + record.strings("deleted_chat_ids"),
        deletedCommentIds = reference.strings("deleted_comment_ids"),
        lostRooms = reference.strings("chat_room_no_access") + record.strings("chat_room_no_access"),
        lostTools = reference.strings("tools_no_view_access"),
        lostUnits = reference.strings("unit_no_view_access"),
        lostAdminSettings = reference.strings("admin_settings_no_access"),
        selfDeviceId = reference.text("self_device_id"),
        projectId = record.text("project_id"),
    )
    return silence.takeUnless { it.isEmpty }
}

/**
 * The records a `notification:save` frame carries, minus the ones no client
 * counts: a silent push, a row flagged `ignore`, this person's own action
 * (Android `insertOrUpdate` 81-168, web `AllBadges.jsx:338-341`).
 */
fun ledgerArrivalsFrom(
    payload: kotlinx.serialization.json.JsonElement?,
    fallbackProjectId: String? = null,
): List<NotificationRecord> =
    payload.wireRecords()
        .mapNotNull { notificationRecordFrom(it, fromApi = false, fallbackProjectId = fallbackProjectId) }
        .filterNot { it.silent || it.ignored || it.self }
