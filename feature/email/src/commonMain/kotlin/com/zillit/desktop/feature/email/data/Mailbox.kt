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
                SyncedPage(
                    messages = cache.messages(folderName),
                    hasMore = false,
                    serverUids = serverUids.toSet(),
                    complete = true,
                ),
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

    /**
     * Brings every folder up to date — the web's open-and-refresh pass
     * (`useEmailSync.fetchInitialData`): each folder's uid list, then every
     * uid this machine lacks in batches of [EmailSync.BATCH], newest first,
     * folder after folder. [onProgress] is told the share of batches done,
     * which is what the "Syncing emails… 45%" strip shows; [onFolderChanged]
     * fires after each batch lands so the open folder repaints as it fills.
     *
     * Why the whole mailbox and not just the open folder: conversations span
     * folders. The Inbox row for a thread counts the replies in Sent, and a
     * click on it opens them — neither is possible while Sent has never been
     * synced. Drafts are skipped: they are not IMAP (see `EmailDraft`).
     */
    suspend fun syncEverything(
        folders: List<EmailFolder>,
        onProgress: suspend (percent: Int) -> Unit = {},
        onFolderChanged: suspend (FolderSyncResult) -> Unit = {},
        /** The clock the uid lists are stamped with, for the badge ledger's arrival guard. */
        nowMillis: () -> Long = { 0L },
    ) {
        val plans = folders
            .filterNot { it.name.equals(EmailFolder.DRAFTS, ignoreCase = true) }
            .mapNotNull { folder -> planFor(folder.name, nowMillis()) }

        val total = plans.sumOf { it.batches.size }
        var done = 0
        onProgress(0)
        for (plan in plans) {
            if (plan.stale.isNotEmpty() || plan.batches.isEmpty()) {
                if (plan.stale.isNotEmpty()) cache.saveMessages(plan.folderName, emptyList(), dropUids = plan.stale)
                onFolderChanged(
                    FolderSyncResult(
                        plan.folderName,
                        plan.serverUids,
                        complete = plan.batches.isEmpty(),
                        plan.listedAt,
                    ),
                )
            }
            for ((index, batch) in plan.batches.withIndex()) {
                when (val page = repository.index(plan.folderName, batch)) {
                    is ZillitResult.Success -> cache.saveMessages(plan.folderName, page.data)
                    is ZillitResult.Failure -> ZillitLog.w(TAG) {
                        "sync of ${plan.folderName} batch ${index + 1}/${plan.batches.size} failed: " +
                            page.error.technical
                    }
                }
                done++
                onFolderChanged(
                    FolderSyncResult(
                        plan.folderName,
                        plan.serverUids,
                        complete = EmailSync.isComplete(plan.serverUids.toList(), cache.cachedUids(plan.folderName)),
                        listedAt = plan.listedAt,
                    ),
                )
                if (total > 0) onProgress(done * PERCENT / total)
            }
        }
        onProgress(PERCENT)
    }

    /** What a folder needs: the server's list, the stale rows, and the batches to fetch. */
    private suspend fun planFor(folderName: String, listedAt: Long): SyncPlan? {
        val serverUids = when (val uids = repository.folderUids(folderName)) {
            is ZillitResult.Failure -> {
                ZillitLog.w(TAG) { "could not list $folderName: ${uids.error.technical}" }
                return null
            }
            is ZillitResult.Success -> uids.data
        }
        val cachedUids = cache.cachedUids(folderName)
        val missing = serverUids.filter { it !in cachedUids }.sortedDescending()
        return SyncPlan(
            folderName = folderName,
            serverUids = serverUids.toSet(),
            stale = EmailSync.staleUids(serverUids, cachedUids),
            batches = missing.chunked(EmailSync.BATCH),
            listedAt = listedAt,
        )
    }

    private class SyncPlan(
        val folderName: String,
        val serverUids: Set<Int>,
        val stale: Set<Int>,
        val batches: List<List<Int>>,
        val listedAt: Long,
    )

    /** Forgets a deleted folder's mail, so it does not linger on disk. */
    fun forget(folderName: String) = cache.clearFolder(folderName)

    /** Marks a message read locally. The server sets its own flag on open. */
    fun markRead(folderName: String, messageId: String) = cache.markRead(folderName, messageId)

    private companion object {
        const val TAG = "Mailbox"
        const val PERCENT = 100
    }
}

/**
 * One folder's state after a batch of the full sync landed: the server's
 * complete uid list and whether every uid it names is now held — what the
 * badge ledger squares itself against.
 */
data class FolderSyncResult(
    val folderName: String,
    val serverUids: Set<Int>,
    val complete: Boolean,
    /** When the folder's uid list was asked for. */
    val listedAt: Long = 0L,
)
