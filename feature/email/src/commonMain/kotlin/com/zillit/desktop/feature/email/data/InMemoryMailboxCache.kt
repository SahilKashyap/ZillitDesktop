package com.zillit.desktop.feature.email.data

import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.MailboxCache

/**
 * A mailbox cache that forgets on exit.
 *
 * Used when the encrypted database cannot be opened — a machine that has never
 * signed in, or a keychain the OS refused. Mail then still works: folders sync,
 * batches load, scrolling pages. It just starts empty every launch.
 *
 * Degrading this way rather than disabling mail is deliberate. The cache is a
 * speed feature, and losing it should cost speed, not the feature.
 *
 * Also what the sync tests run against.
 */
class InMemoryMailboxCache(
    /** The address the rows belong to — partitioned like the SQL cache, so a switch shows the right mail. */
    private val currentMailbox: () -> String = { "" },
) : MailboxCache {

    private class Partition {
        var folders: List<EmailFolder> = emptyList()

        /** Keyed by folder, then by uid — the same identity the real cache uses. */
        val messages = mutableMapOf<String, MutableMap<Int, EmailSummary>>()
    }

    private val partitions = mutableMapOf<String, Partition>()

    private val partition: Partition get() = partitions.getOrPut(currentMailbox()) { Partition() }

    override fun folders(): List<EmailFolder> = partition.folders

    override fun saveFolders(folders: List<EmailFolder>) {
        partition.folders = folders
    }

    override fun messages(folderName: String): List<EmailSummary> =
        partition.messages[folderName].orEmpty().values.sortedByDescending { it.receivedAtMillis }

    override fun saveMessages(
        folderName: String,
        messages: List<EmailSummary>,
        dropUids: Set<Int>,
    ) {
        val folder = partition.messages.getOrPut(folderName) { mutableMapOf() }
        dropUids.forEach(folder::remove)
        messages.forEach { folder[it.uid] = it }
    }

    override fun allMessages(): List<EmailSummary> =
        partition.messages.values.flatMap { it.values }.sortedByDescending { it.receivedAtMillis }

    override fun cachedUids(folderName: String): Set<Int> = partition.messages[folderName]?.keys.orEmpty()

    override fun clearFolder(folderName: String) {
        partition.messages.remove(folderName)
    }

    override fun markRead(folderName: String, messageId: String) {
        val folder = partition.messages[folderName] ?: return
        folder.entries
            .firstOrNull { it.value.id == messageId }
            ?.let { (uid, message) -> folder[uid] = message.copy(isRead = true) }
    }
}
