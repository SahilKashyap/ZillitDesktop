package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.feature.email.domain.EmailQuery
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.search
import com.zillit.desktop.feature.email.domain.searchScopeLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a search is showing.
 *
 * [syncedFolderCount] is how many folders had anything to search, which is what
 * [scope] turns into the line under the box. It is not decoration: there is no
 * server-side mail search, so the user needs to know a result set covers what
 * this machine has downloaded and not the mailbox.
 */
data class SearchState(
    val query: EmailQuery = EmailQuery(),
    val results: List<EmailSummary> = emptyList(),
    val syncedFolderCount: Int = 0,
) {
    val isActive: Boolean get() = query.isActive

    val scope: String get() = searchScopeLabel(syncedFolderCount)
}

/**
 * Searching cached mail.
 *
 * Its own class rather than more of the mailbox's, following the downloader and
 * the folder editor — this owns one box, one query and no network at all.
 *
 * Synchronous on purpose. It filters rows already in memory, so a debounce
 * would add delay to something with no round trip to wait for; the cost is
 * re-reading the cache per keystroke, which is a few thousand rows.
 */
class MailSearch(private val allMessages: () -> List<EmailSummary>) {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    fun term(value: String) = run(_state.value.query.copy(term = value))

    fun filters(query: EmailQuery) = run(query)

    /** Ends the search — called when a folder is opened. */
    fun clear() {
        _state.value = SearchState()
    }

    private fun run(query: EmailQuery) {
        if (!query.isActive) {
            _state.value = SearchState(query = query)
            return
        }

        val everything = allMessages()
        _state.value = SearchState(
            query = query,
            results = everything.search(query),
            syncedFolderCount = everything.map { it.folderName }.distinct().size,
        )
    }
}
