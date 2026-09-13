package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The mailbox.
 *
 * Shaped by the IMAP API rather than by what a mail UI would ideally ask for:
 * there is no "give me page 2 of the inbox" call. Instead the server hands over
 * every uid in a folder and the client works out which it is missing — see
 * `EmailSync`, where that dance lives.
 */
interface EmailRepository {

    suspend fun folders(): ZillitResult<List<EmailFolder>>

    /** Every uid currently in [folderName]. */
    suspend fun folderUids(folderName: String): ZillitResult<List<Int>>

    /**
     * Summaries for [uids].
     *
     * Batched by the caller: the server rejects very large uid lists, and both
     * other clients settled on 50 per request.
     */
    suspend fun index(folderName: String, uids: List<Int>): ZillitResult<List<EmailSummary>>

    /** Full messages, bodies and all, for a conversation. */
    suspend fun trail(folderName: String, messageIds: List<String>): ZillitResult<List<EmailMessage>>

    /** Sends. Returns once the server has accepted it, not once it is delivered. */
    suspend fun send(message: OutgoingEmail): ZillitResult<Unit>

    /** An attachment's bytes, base64 as the server sends them. */
    suspend fun attachment(
        attachmentId: String,
        messageId: String,
        folderName: String,
    ): ZillitResult<String>

    /**
     * Moves mail between folders.
     *
     * Also how mail is deleted: in IMAP, deleting from an ordinary folder means
     * moving to Trash. Only [deletePermanently] actually destroys anything.
     */
    suspend fun move(
        messageIds: List<String>,
        fromFolder: String,
        toFolder: String,
    ): ZillitResult<Unit>

    /**
     * Destroys mail. Irreversible.
     *
     * Reserved for messages already in Trash — deleting from anywhere else
     * moves to Trash instead, which is what every mail client does and what the
     * user expects to be able to undo.
     */
    suspend fun deletePermanently(messageIds: List<String>): ZillitResult<Unit>

    /** Destroys everything in Trash. Irreversible. */
    suspend fun emptyTrash(): ZillitResult<Unit>
}

/**
 * Saved, unsent messages.
 *
 * Its own port because it is its own API: everything in [EmailRepository] talks
 * to an IMAP server and syncs by uid, while drafts live in Zillit's own store
 * and page by timestamp. One implementation serves both, but the boundary is
 * real and worth naming.
 */
interface DraftRepository {

    /** Saved drafts, newest first. Pass "now" for the newest page. */
    suspend fun drafts(beforeMillis: Long): ZillitResult<List<EmailDraft>>

    /**
     * Saves a new draft and returns its id.
     *
     * [uniqueId] is the compose session's key, minted once when the composer
     * opens and sent unchanged on every attempt to create its draft. The
     * server folds a repeated create onto the first record it made for that
     * key, so a create whose answer was lost on a slow link can be tried
     * again without leaving a second copy in Drafts.
     */
    suspend fun saveDraft(message: OutgoingEmail, uniqueId: String): ZillitResult<String>

    /** Overwrites an existing draft. */
    suspend fun updateDraft(draftId: String, message: OutgoingEmail): ZillitResult<Unit>

    suspend fun deleteDrafts(draftIds: List<String>): ZillitResult<Unit>
}

/**
 * The user's own address book.
 *
 * Its own port for the same reason as [DraftRepository]: `email-contact` is
 * Zillit's store, not IMAP. Crew suggestions do **not** come from here — they
 * come from the project users this app already caches when a production opens,
 * so the composer costs one request rather than two.
 */
interface ContactRepository {
    suspend fun contacts(): ZillitResult<List<EmailContact>>
}

/**
 * Creating and renaming mail folders.
 *
 * Its own port, like [DraftRepository] and [ContactRepository] — partly because
 * folder management is a distinct concern, and partly because [EmailRepository]
 * is already at the size where one more method makes it a grab bag.
 */
interface FolderRepository {

    suspend fun createFolder(name: String): ZillitResult<Unit>

    /** Renames [name] to [newName]. Both are wire names, not display names. */
    suspend fun renameFolder(name: String, newName: String): ZillitResult<Unit>

    /**
     * Deletes a folder. Irreversible, and takes whatever is in it.
     *
     * Some IMAP servers refuse to delete a folder that still holds mail and
     * others delete it along with the folder; this client does not try to tell
     * them apart, so the user is warned about the contents either way.
     */
    suspend fun deleteFolder(name: String): ZillitResult<Unit>
}
