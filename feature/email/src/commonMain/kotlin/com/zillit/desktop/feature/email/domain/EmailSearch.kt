package com.zillit.desktop.feature.email.domain

/** Which parts of a message a term is matched against. */
enum class SearchField { Subject, From, To, Body }

/** Narrowing by whether mail has been read. */
enum class ReadFilter { Any, Read, Unread }

/**
 * A mail search.
 *
 * ## Local, and honest about it
 *
 * There is no server-side mail search. Neither other client has one: Android
 * queries its Realm cache and the web filters the list it already holds, and
 * the mail API exposes no search endpoint at all — the IMAP proxy offers uid
 * listing, indexing and fetching, and nothing else.
 *
 * So this searches the mail synced to this machine, and the UI says so. The
 * consequence worth knowing: a folder the user has never opened has nothing
 * cached and therefore matches nothing, which is why [folders] defaults to
 * every folder that *has* been synced rather than every folder that exists.
 */
data class EmailQuery(
    val term: String = "",
    /** Empty means every synced folder. */
    val folders: Set<String> = emptySet(),
    val fields: Set<SearchField> = DEFAULT_FIELDS,
    val readFilter: ReadFilter = ReadFilter.Any,
    val withAttachmentsOnly: Boolean = false,
) {
    val isActive: Boolean get() = term.isNotBlank()

    /** True when anything beyond the term itself has been narrowed. */
    val hasFilters: Boolean
        get() = folders.isNotEmpty() ||
            fields != DEFAULT_FIELDS ||
            readFilter != ReadFilter.Any ||
            withAttachmentsOnly

    companion object {
        /**
         * Subject and sender by default.
         *
         * Not the body: a term that appears in a quoted reply chain matches
         * half the mailbox, and someone searching "call sheet" wants the
         * message about it, not every message that mentions it.
         */
        val DEFAULT_FIELDS = setOf(SearchField.Subject, SearchField.From)
    }
}

/**
 * Runs a query over cached mail.
 *
 * Ordered newest first, like every other list here. Returns nothing for a blank
 * term rather than everything — a search box that shows the whole mailbox the
 * moment it is focused is noise.
 */
fun List<EmailSummary>.search(query: EmailQuery): List<EmailSummary> {
    if (!query.isActive) return emptyList()
    val term = query.term.trim().lowercase()

    return asSequence()
        .filter { query.folders.isEmpty() || it.folderName in query.folders }
        .filter { query.readFilter.accepts(it.isRead) }
        .filter { !query.withAttachmentsOnly || it.hasAttachments }
        .filter { message -> query.fields.any { field -> message.matches(field, term) } }
        .sortedByDescending { it.receivedAtMillis }
        .toList()
}

private fun ReadFilter.accepts(isRead: Boolean): Boolean = when (this) {
    ReadFilter.Any -> true
    ReadFilter.Read -> isRead
    ReadFilter.Unread -> !isRead
}

/**
 * Recipients are matched as one joined string, so "crew@" finds a message
 * addressed to several people including that one.
 */
private fun EmailSummary.matches(field: SearchField, term: String): Boolean = when (field) {
    SearchField.Subject -> subject.contains(term, ignoreCase = true)
    SearchField.From -> from.contains(term, ignoreCase = true)
    SearchField.To -> to.any { it.contains(term, ignoreCase = true) }
    // The snippet, not the body: the list cache holds only the first ~140
    // characters, so a body search is honest about reaching that far and no
    // further.
    SearchField.Body -> snippet.contains(term, ignoreCase = true)
}

/** What to tell the user their search covered. */
fun searchScopeLabel(syncedFolders: Int): String = when (syncedFolders) {
    0 -> "No mail has been downloaded yet."
    1 -> "Searching mail downloaded to this computer."
    else -> "Searching mail downloaded to this computer, across $syncedFolders folders."
}
