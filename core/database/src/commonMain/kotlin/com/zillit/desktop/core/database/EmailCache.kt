package com.zillit.desktop.core.database

/**
 * One cached message summary.
 *
 * Recipients are a joined string rather than a table: the list shows them, it
 * never queries by them, and a join table for a field that is only ever read
 * whole would cost a query per row.
 */
data class EmailSnapshot(
    val uid: Int,
    val folderName: String,
    val messageId: String,
    val threadId: String,
    val subject: String,
    val sender: String,
    val recipients: List<String>,
    val snippet: String,
    val receivedAt: Long,
    val isRead: Boolean,
    val attachmentCount: Int,
)

data class EmailFolderSnapshot(
    val folderName: String,
    val isSystem: Boolean,
    val unreadCount: Int,
)

/**
 * The mail cache.
 *
 * Separate from [ProjectCache] because it is written on a different rhythm —
 * continuously as folders sync, rather than once when a production opens — but
 * cleared alongside it, by [clearProject].
 */
class EmailCache(database: ZillitDatabase, private val nowMillis: () -> Long) {

    private val queries = database.emailCacheQueries

    // -- folders -----------------------------------------------------------

    fun saveFolders(projectId: String, folders: List<EmailFolderSnapshot>) {
        queries.transaction {
            // Replace rather than merge: a folder deleted on another device has
            // to disappear here too, and the server's list is the whole truth.
            queries.deleteFolders(projectId)
            folders.forEach { folder ->
                queries.upsertFolder(
                    folderName = folder.folderName,
                    projectId = projectId,
                    isSystem = folder.isSystem.toDb(),
                    unreadCount = folder.unreadCount.toLong(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    fun folders(projectId: String): List<EmailFolderSnapshot> =
        queries.selectFolders(projectId).executeAsList().map {
            EmailFolderSnapshot(
                folderName = it.folderName,
                isSystem = it.isSystem.toBool(),
                unreadCount = it.unreadCount.toInt(),
            )
        }

    // -- messages ----------------------------------------------------------

    /**
     * Adds a batch of summaries.
     *
     * Merges rather than replaces, unlike every other cache here: a folder
     * arrives 50 messages at a time and replacing would leave only the last
     * batch. [dropUids] removes what the server no longer lists, in the same
     * transaction, so the list never shows a half-synced folder.
     */
    fun saveEmails(
        projectId: String,
        folderName: String,
        emails: List<EmailSnapshot>,
        dropUids: Set<Int> = emptySet(),
    ) {
        queries.transaction {
            dropUids.forEach { uid -> queries.deleteEmail(projectId, folderName, uid.toLong()) }
            emails.forEach { email ->
                queries.upsertEmail(
                    uid = email.uid.toLong(),
                    projectId = projectId,
                    folderName = folderName,
                    messageId = email.messageId,
                    threadId = email.threadId,
                    subject = email.subject,
                    sender = email.sender,
                    recipients = email.recipients.joinToString(RECIPIENT_SEPARATOR),
                    snippet = email.snippet,
                    receivedAt = email.receivedAt,
                    isRead = email.isRead.toDb(),
                    attachmentCount = email.attachmentCount.toLong(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    /** Every folder at once, for search. */
    fun allEmails(projectId: String): List<EmailSnapshot> =
        queries.selectAllEmails(projectId).executeAsList().map(::toSnapshot)

    fun emails(projectId: String, folderName: String): List<EmailSnapshot> =
        queries.selectEmails(projectId, folderName).executeAsList().map(::toSnapshot)

    private fun toSnapshot(row: CachedEmail) = EmailSnapshot(
        uid = row.uid.toInt(),
        folderName = row.folderName,
        messageId = row.messageId,
        threadId = row.threadId,
        subject = row.subject,
        sender = row.sender,
        recipients = row.recipients.split(RECIPIENT_SEPARATOR).filter(String::isNotBlank),
        snippet = row.snippet,
        receivedAt = row.receivedAt,
        isRead = row.isRead.toBool(),
        attachmentCount = row.attachmentCount.toInt(),
    )

    /** The uids already held, which is what the sync diffs against. */
    fun cachedUids(projectId: String, folderName: String): Set<Int> =
        queries.selectUids(projectId, folderName).executeAsList().map(Long::toInt).toSet()

    /**
     * Marks a message read locally.
     *
     * Written here as well as on the server so the row stops looking unread the
     * instant it is opened, rather than after the next folder sync.
     */
    fun markRead(projectId: String, folderName: String, messageId: String) {
        queries.markRead(projectId, folderName, messageId)
    }

    fun clearFolder(projectId: String, folderName: String) {
        queries.deleteFolderEmails(projectId, folderName)
    }

    /** Drops all mail held for a production. Called on switch and sign-out. */
    fun clearProject(projectId: String) {
        queries.transaction {
            queries.deleteEmailData(projectId)
            queries.deleteFolders(projectId)
        }
    }

    private companion object {
        /**
         * Newline, not comma: mail display names legitimately contain commas
         * (`"Khan, Aisha" <a@b.com>`) and splitting on one would tear a single
         * recipient into two.
         */
        const val RECIPIENT_SEPARATOR = "\n"
    }
}

private fun Boolean.toDb(): Long = if (this) 1 else 0
private fun Long.toBool(): Boolean = this != 0L
