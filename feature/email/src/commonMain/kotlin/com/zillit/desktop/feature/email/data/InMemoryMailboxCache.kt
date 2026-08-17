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
class InMemoryMailboxCache : MailboxCache {

    private var folders: List<EmailFolder> = emptyList()

    /** Keyed by folder, then by uid — the same identity the real cache uses. */
    private val messages = mutableMapOf<String, MutableMap<Int, EmailSummary>>()

    override fun folders(): List<EmailFolder> = folders

    override fun saveFolders(folders: List<EmailFolder>) {
        this.folders = folders
    }

    override fun messages(folderName: String): List<EmailSummary> =
        messages[folderName].orEmpty().values.sortedByDescending { it.receivedAtMillis }

    override fun saveMessages(
        folderName: String,
        messages: List<EmailSummary>,
        dropUids: Set<Int>,
    ) {
        val folder = this.messages.getOrPut(folderName) { mutableMapOf() }
        dropUids.forEach(folder::remove)
        messages.forEach { folder[it.uid] = it }
    }

    override fun allMessages(): List<EmailSummary> =
        messages.values.flatMap { it.values }.sortedByDescending { it.receivedAtMillis }

    override fun cachedUids(folderName: String): Set<Int> = messages[folderName]?.keys.orEmpty()

    override fun clearFolder(folderName: String) {
        messages.remove(folderName)
    }

    override fun markRead(folderName: String, messageId: String) {
        val folder = messages[folderName] ?: return
        folder.entries
            .firstOrNull { it.value.id == messageId }
            ?.let { (uid, message) -> folder[uid] = message.copy(isRead = true) }
    }
}
