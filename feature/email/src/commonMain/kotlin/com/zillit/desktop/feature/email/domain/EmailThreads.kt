@file:Suppress("MatchingDeclarationName") // The conversation rules; MailRow is only what they produce.

package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * One row of the message list: a message, or — with conversation view on —
 * the conversation it stands for.
 *
 * [message] is the folder's own newest copy, which is what a click opens and
 * a tick selects. [latest] is the newest message of the whole conversation
 * across every folder, which is whose name and subject the row *shows*: a
 * reply sent from here lives in Sent, and the Inbox row must not keep naming
 * the person who wrote three messages ago (web `NewEmailList.jsx`,
 * `displayByThread`, ZL-17843).
 */
data class MailRow(
    val message: EmailSummary,
    val latest: EmailSummary = message,
    /** Messages in the conversation, every folder counted. 1 without conversation view. */
    val count: Int = 1,
    /** Whether any message of the conversation is unread. */
    val hasUnread: Boolean = !message.isRead,
) {
    val id: String get() = message.id
    val threadId: String get() = message.threadId

    /** The web's `(N)` after the sender, shown only for a real conversation. */
    val showsCount: Boolean get() = count > 1
}

/**
 * Which folders a conversation is read across.
 *
 * Trash is its own world: opened from Trash, a conversation shows only its
 * trashed messages; opened from anywhere else, its trashed messages are left
 * out (web `getEmailsById`, ZL-13209). Otherwise a deleted reply would keep
 * reappearing in the Inbox thread it was deleted from.
 */
fun threadScope(currentFolder: String): (EmailSummary) -> Boolean {
    val inTrash = currentFolder.equals(EmailFolder.TRASH, ignoreCase = true)
    return { row -> row.folderName.equals(EmailFolder.TRASH, ignoreCase = true) == inTrash }
}

/**
 * The list for [folderMessages], grouped into conversations when
 * [conversationView] is on.
 *
 * Grouping follows the web's `groupEmailsIntoThreads`: within the folder, one
 * row per thread — the newest copy — and the count, the unread flag and the
 * displayed identity come from [everything] this machine holds for that thread
 * in the folders [threadScope] admits. Without conversation view every message
 * is its own row, newest first.
 */
fun groupIntoThreads(
    folderMessages: List<EmailSummary>,
    everything: List<EmailSummary>,
    conversationView: Boolean,
    currentFolder: String,
): List<MailRow> {
    val newestFirst = folderMessages
        .distinctBy { it.id }
        .sortedByDescending { it.receivedAtMillis }

    if (!conversationView) return newestFirst.map { MailRow(it) }

    val admits = threadScope(currentFolder)
    val byThread = everything.asSequence()
        .filter(admits)
        .distinctBy { it.id }
        .groupBy { it.threadId }

    val seen = HashSet<String>()
    return newestFirst.mapNotNull { message ->
        if (!seen.add(message.threadId)) return@mapNotNull null
        val members = byThread[message.threadId].orEmpty().ifEmpty { listOf(message) }
        MailRow(
            message = message,
            latest = members.maxByOrNull { it.receivedAtMillis } ?: message,
            count = members.size,
            hasUnread = !message.isRead || members.any { !it.isRead },
        )
    }
}

/**
 * Every cached message of [threadId] a click on it should open, grouped by
 * the folder each copy lives in — the trail endpoint takes one folder per
 * call, so an Inbox conversation with replies in Sent is two calls.
 */
fun threadMembersByFolder(
    threadId: String,
    everything: List<EmailSummary>,
    currentFolder: String,
): Map<String, List<EmailSummary>> {
    val admits = threadScope(currentFolder)
    return everything.asSequence()
        .filter { it.threadId == threadId && admits(it) }
        .distinctBy { it.id }
        .groupBy { it.folderName }
}

/**
 * Which messages a bulk action on ticked rows reaches.
 *
 * With conversation view on, a ticked row stands for its whole conversation —
 * moving or trashing it takes every message of the thread this machine holds,
 * newest first, as the web's `getSelectedEmails` does. Without it, only the
 * ticked messages themselves.
 */
fun selectionMembers(
    ticked: Collection<EmailSummary>,
    everything: List<EmailSummary>,
    conversationView: Boolean,
    currentFolder: String,
): List<EmailSummary> {
    if (!conversationView) return ticked.distinctBy { it.id }.sortedByDescending { it.receivedAtMillis }
    val threads = ticked.mapTo(HashSet()) { it.threadId }
    val admits = threadScope(currentFolder)
    return everything.asSequence()
        .filter { it.threadId in threads && admits(it) }
        .distinctBy { it.id }
        .sortedByDescending { it.receivedAtMillis }
        .toList()
}

/**
 * How many rows may be ticked at once for a bulk Delete or Move — the web's
 * `EMAIL_SELECTION_MAX_LIMIT`. Past it the client says so rather than
 * sending a request the server will refuse.
 */
const val SELECTION_LIMIT = 30

/** The web's `email_limit_selection_message`. */
val SELECTION_LIMIT_MESSAGE: String
    get() = str(S.desktop_email_selection_limit_message)
