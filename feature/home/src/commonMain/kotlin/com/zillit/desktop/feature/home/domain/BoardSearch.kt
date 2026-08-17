package com.zillit.desktop.feature.home.domain

/**
 * Find-in-board, over what is loaded.
 *
 * Client-side deliberately: neither reference client has a search endpoint —
 * the web decrypts and filters the loaded list, iOS filters its rows — and the
 * bodies only exist in clear on the client anyway.
 *
 * A match is a *post*: a hit in a reply or the sender's name still lands on the
 * parent bubble, because that is the thing on screen to scroll to.
 */
fun List<Notice>.searchMatches(query: String): List<String> {
    val needle = query.trim()
    // Two characters minimum, as iOS requires — one letter matches everything
    // and calls it success.
    if (needle.length < MIN_QUERY_LENGTH) return emptyList()

    return filter { notice -> notice.matches(needle) }.map { it.id }
}

private fun Notice.matches(needle: String): Boolean =
    body.contains(needle, ignoreCase = true) ||
        authorName.contains(needle, ignoreCase = true) ||
        attachment?.fileName?.contains(needle, ignoreCase = true) == true ||
        comments.any { it.body.contains(needle, ignoreCase = true) }

/** Steps through matches, wrapping at both ends — a find bar never dead-ends. */
fun nextMatchIndex(current: Int, count: Int, forward: Boolean): Int = when {
    count <= 0 -> 0
    forward -> (current + 1) % count
    else -> (current - 1 + count) % count
}

const val MIN_QUERY_LENGTH = 2
