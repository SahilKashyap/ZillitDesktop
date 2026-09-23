package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A mailbox folder.
 *
 * ## Identified by name, not by id
 *
 * The mail API is IMAP-backed, and in IMAP a folder *is* its name: every call
 * that touches mail — `get-folder-uids`, `get-email-index`, `get-emails`,
 * `get-attachment` — takes `folder_name`. The server does send a `folder_id`,
 * but nothing accepts it back, so keying on it would mean holding an identifier
 * that cannot be used to ask a question.
 *
 * [name] is therefore the wire name (`INBOX`, `Sent`), and [displayName] is what
 * the user reads.
 */
data class EmailFolder(
    val name: String,
    val isSystem: Boolean = false,
    val unreadCount: Int = 0,
) {
    /**
     * The label for the sidebar.
     *
     * IMAP shouts `INBOX`; every mail client on earth shows "Inbox". User
     * folders keep whatever capitalisation their owner chose — correcting those
     * would rename someone's folder behind their back.
     */
    val displayName: String
        get() = when {
            name.equals(INBOX, ignoreCase = true) -> str(S.inbox_text)
            isSystem -> SYSTEM_LABELS[name.lowercase()]?.let { str(it) } ?: name.replaceFirstChar(Char::uppercaseChar)
            else -> name
        }

    /** Where a system folder sits, or [LAST] for user folders. */
    val systemRank: Int
        get() = SYSTEM_ORDER.indexOfFirst { it.equals(name, ignoreCase = true) }
            .takeIf { it >= 0 } ?: LAST

    companion object {
        /**
         * The order every mail client uses. Inbox first because that is where
         * people start; Trash and Spam last because they are where things go to
         * be ignored.
         *
         * `Junk` and `Spam` are the same folder under two names — servers differ,
         * and both must rank alongside each other rather than one falling
         * through to the user-folder block.
         */
        val SYSTEM_ORDER = listOf(
            "INBOX", "Starred", "Sent", "Drafts", "Archive", "Spam", "Junk", "Trash",
        )

        const val LAST = Int.MAX_VALUE

        /** The catalogue key for each system folder's label, by lower-cased IMAP name. */
        private val SYSTEM_LABELS = mapOf(
            "starred" to S.desktop_email_folder_starred,
            "sent" to S.txt_sent,
            "drafts" to S.draft_text,
            "archive" to S.archive_text,
            "spam" to S.span_text,
            "junk" to S.desktop_email_folder_junk,
            "trash" to S.trash_text,
        )

        const val INBOX = "INBOX"
        const val SENT = "Sent"
        const val DRAFTS = "Drafts"
        const val TRASH = "Trash"
    }
}

/**
 * System folders in their conventional order, then the user's alphabetically.
 *
 * The server returns creation order, which puts Trash above Inbox in at least
 * one production. A mail sidebar that does not open with Inbox at the top reads
 * as broken before the user has even looked at it.
 */
fun List<EmailFolder>.forSidebar(): List<EmailFolder> =
    sortedWith(
        compareBy<EmailFolder> { it.systemRank }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
    )

/** The folder to open first. */
fun List<EmailFolder>.defaultFolder(): EmailFolder? =
    firstOrNull { it.name.equals(EmailFolder.INBOX, ignoreCase = true) } ?: forSidebar().firstOrNull()
