package com.zillit.desktop.feature.email.domain

/**
 * What "delete" means, which depends entirely on where you are.
 *
 * In every mail client ever made, deleting from an ordinary folder moves the
 * message to Trash, and only deleting *from Trash* destroys it. Users rely on
 * that: the first delete is casual and reversible, the second is not.
 *
 * Encoded here rather than in the UI so the rule cannot drift between the row
 * action, the reading pane and the selection toolbar.
 */
sealed interface DeleteIntent {

    /** Recoverable — the message goes to Trash. */
    data class MoveToTrash(val byFolder: Map<String, List<String>>) : DeleteIntent

    /** Irreversible. Only ever produced for mail already in Trash. */
    data class Destroy(val messageIds: List<String>) : DeleteIntent
}

/**
 * Works out what deleting these messages should do.
 *
 * Grouped by source folder because a move names one source, and a selection can
 * legitimately span folders — a thread's messages often do. Sending one call
 * per source is what both other clients do.
 */
fun deleteIntentFor(messages: List<EmailSummary>, currentFolder: String): DeleteIntent {
    val ids = messages.map { it.id }

    if (currentFolder.equals(EmailFolder.TRASH, ignoreCase = true)) {
        return DeleteIntent.Destroy(ids)
    }

    return DeleteIntent.MoveToTrash(
        // Falls back to the open folder for a message that does not know its
        // own — a move with a blank source is rejected, and silently dropping
        // the message from the batch would look like the delete half-worked.
        messages.groupBy { it.folderName.ifBlank { currentFolder } }
            .mapValues { (_, group) -> group.map { it.id } },
    )
}

/**
 * Folders a message can be moved into.
 *
 * Excludes the one it is already in, which would be a no-op, and Drafts, which
 * is not an IMAP folder this client writes to — see [EmailDraft].
 */
fun List<EmailFolder>.moveTargets(currentFolder: String?): List<EmailFolder> =
    forSidebar().filterNot { folder ->
        folder.name.equals(currentFolder, ignoreCase = true) ||
            folder.name.equals(EmailFolder.DRAFTS, ignoreCase = true)
    }
