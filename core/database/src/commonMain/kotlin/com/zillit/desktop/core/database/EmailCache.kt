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
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
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
 *
 * Every read and write names the **mailbox** the rows belong to (the address
 * they were fetched for): a production can give one user a personal mailbox
 * and a shared Accounts one, and IMAP uids restart at 1 in each, so a row is
 * only meaningful together with its mailbox. `""` is the mailbox of a build
 * that never learnt an address, which keeps old callers working unchanged.
 */
class EmailCache(database: ZillitDatabase, private val nowMillis: () -> Long) {

    private val queries = database.emailCacheQueries

    // -- folders -----------------------------------------------------------

    fun saveFolders(projectId: String, folders: List<EmailFolderSnapshot>, mailbox: String = "") {
        queries.transaction {
            // Replace rather than merge: a folder deleted on another device has
            // to disappear here too, and the server's list is the whole truth.
            queries.deleteFolders(projectId, mailbox)
            folders.forEach { folder ->
                queries.upsertFolder(
                    folderName = folder.folderName,
                    projectId = projectId,
                    mailbox = mailbox,
                    isSystem = folder.isSystem.toDb(),
                    unreadCount = folder.unreadCount.toLong(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    fun folders(projectId: String, mailbox: String = ""): List<EmailFolderSnapshot> =
        queries.selectFolders(projectId, mailbox).executeAsList().map {
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
        mailbox: String = "",
    ) {
        queries.transaction {
            dropUids.forEach { uid -> queries.deleteEmail(projectId, mailbox, folderName, uid.toLong()) }
            emails.forEach { email ->
                queries.upsertEmail(
                    uid = email.uid.toLong(),
                    projectId = projectId,
                    mailbox = mailbox,
                    folderName = folderName,
                    messageId = email.messageId,
                    threadId = email.threadId,
                    subject = email.subject,
                    sender = email.sender,
                    recipients = email.recipients.joinToString(RECIPIENT_SEPARATOR),
                    ccRecipients = email.cc.joinToString(RECIPIENT_SEPARATOR),
                    bccRecipients = email.bcc.joinToString(RECIPIENT_SEPARATOR),
                    snippet = email.snippet,
                    receivedAt = email.receivedAt,
                    isRead = email.isRead.toDb(),
                    attachmentCount = email.attachmentCount.toLong(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    /** Every folder at once, for search and threading. */
    fun allEmails(projectId: String, mailbox: String = ""): List<EmailSnapshot> =
        queries.selectAllEmails(projectId, mailbox).executeAsList().map(::toSnapshot)

    fun emails(projectId: String, folderName: String, mailbox: String = ""): List<EmailSnapshot> =
        queries.selectEmails(projectId, mailbox, folderName).executeAsList().map(::toSnapshot)

    private fun toSnapshot(row: CachedEmail) = EmailSnapshot(
        uid = row.uid.toInt(),
        folderName = row.folderName,
        messageId = row.messageId,
        threadId = row.threadId,
        subject = row.subject,
        sender = row.sender,
        recipients = row.recipients.splitAddresses(),
        snippet = row.snippet,
        receivedAt = row.receivedAt,
        isRead = row.isRead.toBool(),
        attachmentCount = row.attachmentCount.toInt(),
        cc = row.ccRecipients.splitAddresses(),
        bcc = row.bccRecipients.splitAddresses(),
    )

    private fun String.splitAddresses(): List<String> =
        split(RECIPIENT_SEPARATOR).filter(String::isNotBlank)

    /** The uids already held, which is what the sync diffs against. */
    fun cachedUids(projectId: String, folderName: String, mailbox: String = ""): Set<Int> =
        queries.selectUids(projectId, mailbox, folderName).executeAsList().map(Long::toInt).toSet()

    /**
     * Marks a message read locally.
     *
     * Written here as well as on the server so the row stops looking unread the
     * instant it is opened, rather than after the next folder sync.
     */
    fun markRead(projectId: String, folderName: String, messageId: String, mailbox: String = "") {
        queries.markRead(projectId, mailbox, folderName, messageId)
    }

    fun clearFolder(projectId: String, folderName: String, mailbox: String = "") {
        queries.deleteFolderEmails(projectId, mailbox, folderName)
    }

    /** Drops all mail held for a production, every mailbox. Called on switch and sign-out. */
    fun clearProject(projectId: String) {
        queries.transaction {
            queries.deleteEmailData(projectId)
            queries.deleteAllProjectFolders(projectId)
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
