package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.toSummary

/**
 * The Drafts folder.
 *
 * Its own class, like [MailActions], for [EmailViewModel]'s size — and
 * because Drafts behaves unlike every other folder. Drafts are not in IMAP:
 * they live in Zillit's own store, which hands over a page at a time, so this
 * is the one list that asks the server for more as it nears its bottom while
 * every other folder reads all its rows from the cache. The number beside
 * Drafts in the sidebar comes from the same answer's `summary.total_drafts`,
 * not from however many rows are loaded (web PR #6515).
 *
 * The drafts themselves are kept whatever folder is open — the composer needs
 * the real thing when one is reopened. Only the *list* is Drafts' own:
 * writing `messages` from a read that finished after the user moved on would
 * put drafts in the Inbox.
 */
internal class MailDrafts(private val host: EmailViewModel) {

    private val state get() = host.currentState

    /**
     * The cursor the server said nothing was pending after.
     *
     * Without it every scroll to the bottom of an exhausted list asks for the
     * same empty page again. Cleared when the list is re-read from page one.
     */
    private var exhaustedCursor: Long? = null

    /** Re-reads the folder from its newest page. */
    fun load() {
        if (state.isViewingDrafts) {
            host.update { copy(isLoadingMessages = messages.isEmpty(), error = null) }
        }
        exhaustedCursor = null
        host.launchRead(
            block = { host.drafts.drafts(host.now()) },
            onSuccess = { page ->
                host.update {
                    val common = copy(
                        drafts = page.drafts,
                        draftsTotal = page.total,
                        draftsHaveMore = page.more,
                        isLoadingMoreDrafts = false,
                    )
                    if (isViewingDrafts) {
                        common.copy(
                            isLoadingMessages = false,
                            messages = page.drafts.map(EmailDraft::toSummary),
                        ).regrouped()
                    } else {
                        common
                    }
                }
            },
            onError = { error ->
                if (state.isViewingDrafts) {
                    host.update { copy(isLoadingMessages = false, error = error.localised()) }
                }
            },
        )
    }

    /**
     * The page before the oldest draft loaded — the web's `loadMoreDrafts`.
     *
     * The cursor is that draft's `updated`, with `previous`, as v1's list and
     * iOS page it.
     */
    fun loadMore() {
        val cursor = state.drafts.lastOrNull()?.updatedAtMillis ?: return
        if (!state.isViewingDrafts || state.isLoadingMoreDrafts || cursor == exhaustedCursor) return
        host.update { copy(isLoadingMoreDrafts = true) }
        host.launchRead(
            block = { host.drafts.drafts(cursor, firstPage = false) },
            onSuccess = { page ->
                // A folder switch or a socket refetch replaced the list while
                // this ran; the older page belongs to a list that is gone.
                if (!state.isViewingDrafts || state.drafts.lastOrNull()?.updatedAtMillis != cursor) {
                    host.update { copy(isLoadingMoreDrafts = false) }
                    return@launchRead
                }
                val loaded = state.drafts.mapTo(mutableSetOf()) { it.id }
                val next = state.drafts + page.drafts.filterNot { it.id in loaded }
                if (!page.more) exhaustedCursor = next.lastOrNull()?.updatedAtMillis
                host.update {
                    copy(
                        isLoadingMoreDrafts = false,
                        drafts = next,
                        draftsTotal = if (page.total > 0) page.total else draftsTotal,
                        draftsHaveMore = page.more,
                        messages = next.map(EmailDraft::toSummary),
                    ).regrouped()
                }
            },
            onError = { error -> host.update { copy(isLoadingMoreDrafts = false, error = error.localised()) } },
        )
    }
}
