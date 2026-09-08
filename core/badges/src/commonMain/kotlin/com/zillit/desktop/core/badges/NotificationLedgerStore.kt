package com.zillit.desktop.core.badges

import com.zillit.desktop.core.database.NotificationLedger
import com.zillit.desktop.core.database.ZillitDatabase

/**
 * Where the ledger's rows rest between launches.
 *
 * The rows are the badges: a read applied here outlives the app, exactly as
 * Android's Realm flag does, and a row the server would hand back as unread
 * stays read ("local read wins", `AppBadgeDBManager.insertOrUpdateFromApi`).
 */
interface NotificationLedgerStore {
    fun rows(projectId: String): List<NotificationRecord>
    fun row(id: String): NotificationRecord?
    fun upsert(records: Collection<NotificationRecord>)
    fun markRead(ids: Collection<String>)

    /** The newest `updated` among API-sourced rows — 0 when the project has none; blank asks every production. */
    fun watermark(projectId: String): Long

    /** Unread per production across the whole ledger; [deviceId] blank counts every device's rows. */
    fun unreadByProject(deviceId: String = ""): Map<String, Int>
    fun deleteProject(projectId: String)
    fun deleteAll()
}

/** For tests and for a launch whose database could not be opened. */
class InMemoryNotificationLedgerStore : NotificationLedgerStore {
    private val rows = mutableMapOf<String, NotificationRecord>()

    override fun rows(projectId: String): List<NotificationRecord> = rows.values.filter { it.projectId == projectId }
    override fun row(id: String): NotificationRecord? = rows[id]
    override fun upsert(records: Collection<NotificationRecord>) = records.forEach { rows[it.id] = it }
    override fun markRead(ids: Collection<String>) = ids.forEach { id ->
        rows[id]?.let { rows[id] = it.copy(messageRead = true) }
    }
    override fun watermark(projectId: String): Long =
        rows.values.filter { (projectId.isBlank() || it.projectId == projectId) && it.fromApi }
            .maxOfOrNull { it.updated } ?: 0L
    override fun unreadByProject(deviceId: String): Map<String, Int> =
        rows.values.filter { it.counts && (deviceId.isBlank() || it.deviceId == deviceId) }
            .groupingBy { it.projectId }.eachCount()
    override fun deleteProject(projectId: String) {
        rows.values.removeAll { it.projectId == projectId }
    }
    override fun deleteAll() = rows.clear()
}

/** The ledger table in the encrypted application database. */
class SqlNotificationLedgerStore(database: ZillitDatabase) : NotificationLedgerStore {

    private val queries = database.notificationLedgerQueries

    override fun rows(projectId: String): List<NotificationRecord> =
        queries.selectProject(projectId).executeAsList().map(::toRecord)

    override fun row(id: String): NotificationRecord? = queries.selectById(id).executeAsOneOrNull()?.let(::toRecord)

    override fun upsert(records: Collection<NotificationRecord>) {
        if (records.isEmpty()) return
        queries.transaction { records.forEach(::write) }
    }

    override fun markRead(ids: Collection<String>) {
        if (ids.isEmpty()) return
        // A long IN list is chunked so a wholesale clear stays inside SQLite's
        // bound-variable limit.
        queries.transaction { ids.chunked(CHUNK).forEach { queries.markRead(it) } }
    }

    override fun watermark(projectId: String): Long =
        queries.selectWatermark(projectId).executeAsOneOrNull()?.MAX ?: 0L

    override fun unreadByProject(deviceId: String): Map<String, Int> =
        queries.unreadByProject(deviceId).executeAsList().associate { it.projectId to it.unread.toInt() }

    override fun deleteProject(projectId: String) {
        queries.deleteProject(projectId)
    }

    override fun deleteAll() {
        queries.deleteAll()
    }

    private fun write(record: NotificationRecord) = queries.upsert(
        id = record.id,
        projectId = record.projectId,
        deviceId = record.deviceId,
        mongoId = record.mongoId,
        section = record.section,
        tool = record.tool,
        unit = record.unit,
        level1 = record.level1,
        level2 = record.level2,
        level3 = record.level3,
        actionName = record.action,
        referenceId = record.referenceId,
        sender = record.sender,
        receiverId = record.receiver,
        created = record.created,
        updated = record.updated,
        messageRead = record.messageRead.bit(),
        isGlobal = record.isGlobal.bit(),
        ignored = record.ignored.bit(),
        isSelf = record.self.bit(),
        silent = record.silent.bit(),
        deleted = record.deleted.bit(),
        chatRoomId = record.chatRoomId,
        senderId = record.senderId,
        chatUnitId = record.chatUnitId,
        calendarEnd = record.calendarEnd,
        fromApi = record.fromApi.bit(),
        raw = record.raw,
    )

    private fun toRecord(row: NotificationLedger) = NotificationRecord(
        id = row.id,
        projectId = row.projectId,
        deviceId = row.deviceId,
        mongoId = row.mongoId,
        section = row.section,
        tool = row.tool,
        unit = row.unit,
        level1 = row.level1,
        level2 = row.level2,
        level3 = row.level3,
        action = row.actionName,
        referenceId = row.referenceId,
        sender = row.sender,
        receiver = row.receiverId,
        created = row.created,
        updated = row.updated,
        messageRead = row.messageRead != 0L,
        isGlobal = row.isGlobal != 0L,
        ignored = row.ignored != 0L,
        self = row.isSelf != 0L,
        silent = row.silent != 0L,
        deleted = row.deleted != 0L,
        chatRoomId = row.chatRoomId,
        senderId = row.senderId,
        chatUnitId = row.chatUnitId,
        calendarEnd = row.calendarEnd,
        fromApi = row.fromApi != 0L,
        raw = row.raw,
    )

    private fun Boolean.bit(): Long = if (this) 1L else 0L

    private companion object {
        const val CHUNK = 500
    }
}
