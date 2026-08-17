package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.database.EmailCache
import com.zillit.desktop.core.database.EmailFolderSnapshot
import com.zillit.desktop.core.database.EmailSnapshot
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.MailboxCache

/**
 * [MailboxCache] over the encrypted SQLite cache.
 *
 * Only mapping lives here. The sync rules that decide *what* to store are in
 * `Mailbox`, so they can be tested without a database.
 */
class SqlMailboxCache(
    private val cache: EmailCache,
    /**
     * Resolved per call, not captured.
     *
     * The mailbox is built once at startup but a production is opened later and
     * can be switched. Holding the id would cache one project's mail under
     * another's — the one failure this cache must never produce.
     */
    private val currentProjectId: () -> String,
) : MailboxCache {

    private val projectId get() = currentProjectId()

    override fun folders(): List<EmailFolder> =
        cache.folders(projectId).map {
            EmailFolder(
                name = it.folderName,
                isSystem = it.isSystem,
                unreadCount = it.unreadCount,
            )
        }

    override fun saveFolders(folders: List<EmailFolder>) {
        cache.saveFolders(
            projectId,
            folders.map {
                EmailFolderSnapshot(
                    folderName = it.name,
                    isSystem = it.isSystem,
                    unreadCount = it.unreadCount,
                )
            },
        )
    }

    override fun messages(folderName: String): List<EmailSummary> =
        cache.emails(projectId, folderName).map(::toSummary)

    private fun toSummary(row: EmailSnapshot) = EmailSummary(
        id = row.messageId,
        threadId = row.threadId,
        subject = row.subject,
        from = row.sender,
        to = row.recipients,
        snippet = row.snippet,
        receivedAtMillis = row.receivedAt,
        isRead = row.isRead,
        hasAttachments = row.attachmentCount > 0,
        attachmentCount = row.attachmentCount,
        uid = row.uid,
        folderName = row.folderName,
    )

    override fun saveMessages(
        folderName: String,
        messages: List<EmailSummary>,
        dropUids: Set<Int>,
    ) {
        cache.saveEmails(
            projectId = projectId,
            folderName = folderName,
            emails = messages.map {
                EmailSnapshot(
                    uid = it.uid,
                    folderName = folderName,
                    messageId = it.id,
                    threadId = it.threadId,
                    subject = it.subject,
                    sender = it.from,
                    recipients = it.to,
                    snippet = it.snippet,
                    receivedAt = it.receivedAtMillis,
                    isRead = it.isRead,
                    attachmentCount = it.attachmentCount,
                )
            },
            dropUids = dropUids,
        )
    }

    override fun allMessages(): List<EmailSummary> = cache.allEmails(projectId).map(::toSummary)

    override fun cachedUids(folderName: String): Set<Int> = cache.cachedUids(projectId, folderName)

    override fun clearFolder(folderName: String) = cache.clearFolder(projectId, folderName)

    override fun markRead(folderName: String, messageId: String) {
        cache.markRead(projectId, folderName, messageId)
    }
}
