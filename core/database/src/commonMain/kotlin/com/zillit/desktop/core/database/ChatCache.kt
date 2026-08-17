package com.zillit.desktop.core.database

/** One cached DM row; the body stays cipher-hex until the reader decrypts. */
data class ChatMessageRow(
    val messageId: String,
    val peerId: String,
    val uniqueId: String,
    val senderId: String,
    val receiverId: String,
    val bodyCipher: String,
    val createdAt: Long,
    val isMine: Boolean,
)

/** The DM cache — thread replace-on-sync, plus the newest row per peer. */
class ChatCache(database: ZillitDatabase) {

    private val queries = database.chatCacheQueries

    fun replaceThread(projectId: String, peerId: String, rows: List<ChatMessageRow>) {
        queries.transaction {
            queries.deleteThread(projectId, peerId)
            rows.forEach { upsert(projectId, it) }
        }
    }

    fun upsert(projectId: String, row: ChatMessageRow) {
        queries.upsertMessage(
            messageId = row.messageId,
            projectId = projectId,
            peerId = row.peerId,
            uniqueId = row.uniqueId,
            senderId = row.senderId,
            receiverId = row.receiverId,
            bodyCipher = row.bodyCipher,
            createdAt = row.createdAt,
            isMine = if (row.isMine) 1L else 0L,
        )
    }

    /** Drops deleted messages by their server ids — deletion is by `_id`. */
    fun deleteMessages(projectId: String, messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        queries.deleteMessages(projectId, messageIds)
    }

    fun thread(projectId: String, peerId: String): List<ChatMessageRow> =
        queries.selectThread(projectId, peerId).executeAsList().map(::toRow)

    fun markReadUntil(projectId: String, peerId: String, uptoMillis: Long) {
        queries.upsertReadMark(projectId, peerId, uptoMillis)
    }

    fun readMarks(projectId: String): Map<String, Long> =
        queries.selectReadMarks(projectId).executeAsList()
            .associate { it.peerId to it.readUntilMillis }

    fun lastPerPeer(projectId: String): List<ChatMessageRow> =
        queries.selectLastPerPeer(projectId, projectId).executeAsList().map(::toRow)

    private fun toRow(row: CachedChatMessage) = ChatMessageRow(
        messageId = row.messageId,
        peerId = row.peerId,
        uniqueId = row.uniqueId,
        senderId = row.senderId,
        receiverId = row.receiverId,
        bodyCipher = row.bodyCipher,
        createdAt = row.createdAt,
        isMine = row.isMine != 0L,
    )
}
