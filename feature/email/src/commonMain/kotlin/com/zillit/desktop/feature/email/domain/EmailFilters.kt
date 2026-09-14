package com.zillit.desktop.feature.email.domain

/** The Filters popover's read-status row. */
enum class ReadStatus { All, Read, Unread }

/**
 * The listing's filters — the web's `emailFilters`
 * (`AdvancedEmailFilters.jsx`): a read status, an attachments switch and a
 * free-text match per address field, all applied to the open folder's rows.
 *
 * Distinct from the search box, which spans folders and matches subjects:
 * these narrow the folder you are looking at, and stay on until cleared.
 */
data class EmailFilters(
    val readStatus: ReadStatus = ReadStatus.All,
    val hasAttachments: Boolean = false,
    val from: String = "",
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
) {
    /** True while any filter is set — what lights the dot on the Filters button. */
    val isActive: Boolean
        get() = readStatus != ReadStatus.All ||
            hasAttachments ||
            from.isNotBlank() ||
            to.isNotBlank() ||
            cc.isNotBlank() ||
            bcc.isNotBlank()

    companion object {
        val None = EmailFilters()
    }
}

/**
 * Narrows [this] to the rows [filters] admit — the web's in-memory pass in
 * `groupEmailsIntoThreads`: read status by the row's flag, attachments by the
 * count, and each address field by a case-insensitive `contains` over its
 * entries.
 */
fun List<EmailSummary>.applyFilters(filters: EmailFilters): List<EmailSummary> {
    if (!filters.isActive) return this
    val from = filters.from.trim().lowercase()
    val to = filters.to.trim().lowercase()
    val cc = filters.cc.trim().lowercase()
    val bcc = filters.bcc.trim().lowercase()

    return filter { row ->
        filters.readStatus.admits(row) &&
            (!filters.hasAttachments || row.hasAttachments) &&
            listOf(row.from).matchesAddress(from) &&
            row.to.matchesAddress(to) &&
            row.cc.matchesAddress(cc) &&
            row.bcc.matchesAddress(bcc)
    }
}

private fun ReadStatus.admits(row: EmailSummary): Boolean = when (this) {
    ReadStatus.All -> true
    ReadStatus.Read -> row.isRead
    ReadStatus.Unread -> !row.isRead
}

/** An empty term admits everything; otherwise any entry has to contain it. */
private fun List<String>.matchesAddress(term: String): Boolean =
    term.isEmpty() || any { it.lowercase().contains(term) }

/**
 * The header's search box, over the open folder's rows — the web's
 * `useEmailListData` filter: Sent matches recipients and subject, everything
 * else matches sender, recipients and subject.
 */
fun List<EmailSummary>.matchingSearch(term: String): List<EmailSummary> {
    val needle = term.trim().lowercase()
    if (needle.isEmpty()) return this
    return filter { row ->
        row.subject.lowercase().contains(needle) ||
            row.from.lowercase().contains(needle) ||
            row.recipients.any { it.lowercase().contains(needle) }
    }
}
