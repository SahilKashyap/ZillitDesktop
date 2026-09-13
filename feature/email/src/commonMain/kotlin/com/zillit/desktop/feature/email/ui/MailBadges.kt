package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.feature.email.domain.EmailSummary

/**
 * The badge ledger's hooks for mail, gathered because they travel together
 * and none belongs to the mailbox: the mailbox's own markRead is IMAP state,
 * not the badge ledger, and the two clear independently.
 *
 * The ledger keys an email row by folder and IMAP uid, not by message id —
 * every phone reads it that way (Android `EmailInbox`, iOS
 * `updateMarkReadBySectionUnitAndReferenceIds`), so a read handed over by
 * message id alone clears nothing and the badge outlives the mail.
 */
class MailBadges(
    /** Told when a message is opened for reading. Hosts hang the ledger read here. */
    val onMessageRead: suspend (MailRead) -> Unit = {},
    /**
     * Told after a folder synced against the server. Hosts retire the rows
     * that no longer describe unread mail — mail read elsewhere, or gone
     * from the folder (Android ZL-21196).
     */
    val onFolderSynced: suspend (MailFolderSync) -> Unit = {},
    /**
     * Unread per folder from the ledger (`?section=email_label&group=unit`).
     * Null answers a failed ask; empty leaves the folder list on its IMAP counts.
     */
    val folderBadges: suspend () -> Map<String, Int>? = { emptyMap() },
)

/** One mail opened — every key a ledger row may carry for it. */
data class MailRead(
    val folderName: String,
    val uid: Int,
    val messageId: String,
)

/**
 * A folder freshly synced: the server's complete uid list and the mail held
 * for it afterwards, read flags included.
 */
data class MailFolderSync(
    val folderName: String,
    val serverUids: Set<Int>,
    val messages: List<EmailSummary>,
    /** Whether every uid the server lists is held locally. */
    val complete: Boolean,
    /** When the uid list was asked for — see the host's arrival guard. */
    val listedAt: Long,
)
