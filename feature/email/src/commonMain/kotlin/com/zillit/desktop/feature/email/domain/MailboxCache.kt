package com.zillit.desktop.feature.email.domain

/**
 * Local mail storage, as this feature needs it.
 *
 * A port rather than a direct dependency on `core:database`: the sync rules are
 * the interesting part of this module and they are worth testing against an
 * in-memory fake instead of an encrypted SQLite file on disk. The real
 * implementation is a thin adapter over `EmailCache`.
 */
interface MailboxCache {

    fun folders(): List<EmailFolder>

    fun saveFolders(folders: List<EmailFolder>)

    fun messages(folderName: String): List<EmailSummary>

    /**
     * Every message held, across every folder.
     *
     * For search, which spans folders — the alternative is asking per folder
     * and stitching, which puts the ordering rules in the caller.
     */
    fun allMessages(): List<EmailSummary>

    /** Adds a synced batch and drops what the server no longer lists. */
    fun saveMessages(
        folderName: String,
        messages: List<EmailSummary>,
        dropUids: Set<Int> = emptySet(),
    )

    /** The uids already held — what the sync diffs against. */
    fun cachedUids(folderName: String): Set<Int>

    fun markRead(folderName: String, messageId: String)

    /**
     * Drops everything held for a folder.
     *
     * Called when a folder is deleted. Without it the mail stays readable on
     * disk under a folder that no longer exists — and reappears if someone
     * later creates a folder with the same name, since IMAP identifies folders
     * by name.
     */
    fun clearFolder(folderName: String)
}
