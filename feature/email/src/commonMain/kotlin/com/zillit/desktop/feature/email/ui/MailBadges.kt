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
 * message id alone clears nothing and the badge outlives the mail. Rows are
 * tagged with the mailbox address they belong to (`level_1`, ZL-21025), and
 * every hook says which mailbox it is about: uids collide across the personal
 * and the shared Accounts mailbox.
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
     * Unread per folder from the ledger for one mailbox (`?section=email_label
     * &group=unit`, rows tagged with that address). Null answers a failed ask;
     * empty leaves the folder list on its IMAP counts.
     */
    val folderBadges: suspend (mailboxAddress: String?) -> Map<String, Int>? = { emptyMap() },
    /**
     * Unread per mailbox, keyed by address — the switcher's pills and the dot
     * that says the other mailbox has mail waiting. Null answers a failed ask.
     */
    val mailboxUnread: suspend () -> Map<String, Int>? = { emptyMap() },
)

/** One mail opened — every key a ledger row may carry for it. */
data class MailRead(
    val folderName: String,
    val uid: Int,
    val messageId: String,
    /** The mailbox the copy was read in; null when the module never learnt an address. */
    val mailboxAddress: String? = null,
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
    /** The mailbox the folder belongs to; null when no address is known. */
    val mailboxAddress: String? = null,
)
