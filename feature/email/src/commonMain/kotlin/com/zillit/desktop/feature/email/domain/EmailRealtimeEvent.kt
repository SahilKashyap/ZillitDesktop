package com.zillit.desktop.feature.email.domain

/**
 * What the socket says happened to the mailbox.
 *
 * ## Signals, not deltas
 *
 * Deliberately coarse. The socket says *something changed in this folder* and
 * the client re-syncs it, rather than trying to insert the message the payload
 * carries. Three reasons, in order of how much they cost when ignored:
 *
 *  1. Re-syncing is nearly free here. The uid diff means one small request, and
 *     bodies are only fetched for uids not already cached — so "refresh the
 *     folder" costs one round trip, not a re-download.
 *  2. A delta applied to a folder the user has not opened would write a message
 *     into a cache that has never been synced, leaving a hole where the rest of
 *     the folder should be.
 *  3. The payloads differ between events and are not all documented. Parsing
 *     five shapes to save one request is a poor trade, and the badge layer
 *     already made this call for the same reason.
 *
 * [ReadChanged] is the exception: it carries a uid, and applying it locally
 * avoids a round trip for something as frequent as reading mail on your phone.
 */
sealed interface EmailRealtimeEvent {

    /**
     * Mail arrived, moved or was deleted.
     *
     * [folderName] is null when the payload does not say which folder — the
     * client then refreshes whatever is open, which is the folder the user
     * would notice being stale.
     */
    data class FolderChanged(val folderName: String?) : EmailRealtimeEvent

    /** A message was read or unread somewhere else. */
    data class ReadChanged(val uid: Int) : EmailRealtimeEvent

    /** A folder was created, renamed or deleted. */
    data object FoldersChanged : EmailRealtimeEvent

    /**
     * A draft was saved, edited or deleted.
     *
     * Its own case because drafts do not sync by uid — the Drafts folder is
     * reloaded from `email-draft` rather than diffed.
     */
    data object DraftsChanged : EmailRealtimeEvent
}
