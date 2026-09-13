package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.EmailSync
import com.zillit.desktop.feature.email.domain.MailboxCache

/**
 * One synced batch, and whether there is more behind it.
 *
 * [messages] is the whole folder as cached — not just the new batch — because
 * that is what the list renders, and recombining batches in the UI would put
 * ordering rules in two places.
 */
data class SyncedPage(
    val messages: List<EmailSummary>,
    val hasMore: Boolean,
    /**
     * Every uid the server listed for the folder — the whole folder, not the
     * page. What the badge ledger is reconciled against: a uid it still
     * badges that is not here has left the folder.
     */
    val serverUids: Set<Int> = emptySet(),
    /** Whether every listed uid is now held locally. */
    val complete: Boolean = false,
)

/**
 * Mail, cache first.
 *
 * ## The shape of a "page"
 *
 * The API cannot be asked for page 2 of a folder. It reports every uid the
 * folder holds, and a batch of those uids can be turned into summaries. So
 * paging and caching are the same mechanism here: [syncNext] fetches the newest
 * [EmailSync.BATCH] uids that are *not* cached, stores them, and reports whether
 * any remain. Scrolling calls it again.
 *
 * The consequence worth knowing: the first visit to a large folder shows 50
 * messages and fills in as the user scrolls, and every later visit renders from
 * disk immediately.
 */
class Mailbox(
    private val repository: EmailRepository,
    private val cache: MailboxCache,
) {

    /** Whatever is on disk. Instant, and possibly stale — [syncFolders] refreshes. */
    fun cachedFolders(): List<EmailFolder> = cache.folders()

    fun cachedMessages(folderName: String): List<EmailSummary> = cache.messages(folderName)

    /** Everything held, across every folder — what search runs over. */
    fun cachedMessages(): List<EmailSummary> = cache.allMessages()

    suspend fun syncFolders(): ZillitResult<List<EmailFolder>> =
        when (val result = repository.folders()) {
            is ZillitResult.Failure -> result
            is ZillitResult.Success -> {
                cache.saveFolders(result.data)
                result
            }
        }

    /**
     * Fetches the next batch of uncached mail for a folder.
     *
     * The uid list is re-fetched each call rather than remembered. It is one
     * cheap request and it is the only way to notice mail that arrived — or was
     * deleted on another device — since the last batch.
     */
    suspend fun syncNext(folderName: String): ZillitResult<SyncedPage> {
        val serverUids = when (val uids = repository.folderUids(folderName)) {
            is ZillitResult.Failure -> return uids
            is ZillitResult.Success -> uids.data
        }

        val cachedUids = cache.cachedUids(folderName)
        val stale = EmailSync.staleUids(serverUids, cachedUids)
        val batch = EmailSync.nextBatch(serverUids, cachedUids)

        if (batch.isEmpty()) {
            // Nothing new, but mail may still have been deleted elsewhere.
            if (stale.isNotEmpty()) cache.saveMessages(folderName, emptyList(), dropUids = stale)
            return ZillitResult.Success(
                SyncedPage(cache.messages(folderName), hasMore = false, serverUids = serverUids.toSet(), complete = true),
            )
        }

        return when (val page = repository.index(folderName, batch)) {
            is ZillitResult.Failure -> page
            is ZillitResult.Success -> {
                cache.saveMessages(folderName, page.data, dropUids = stale)
                if (page.data.size < batch.size) {
                    ZillitLog.w(TAG) { "asked for ${batch.size} messages, got ${page.data.size}" }
                }
                val complete = EmailSync.isComplete(serverUids, cache.cachedUids(folderName))
                ZillitResult.Success(
                    SyncedPage(
                        messages = cache.messages(folderName),
                        // Recomputed against what actually landed: a batch the
                        // server could not fully answer must not be retried
                        // forever, and those uids are simply skipped.
                        hasMore = !complete && page.data.isNotEmpty(),
                        serverUids = serverUids.toSet(),
                        complete = complete,
                    ),
                )
            }
        }
    }

    /** Forgets a deleted folder's mail, so it does not linger on disk. */
    fun forget(folderName: String) = cache.clearFolder(folderName)

    /** Marks a message read locally. The server sets its own flag on open. */
    fun markRead(folderName: String, messageId: String) = cache.markRead(folderName, messageId)

    private companion object {
        const val TAG = "Mailbox"
    }
}
