package com.zillit.desktop.feature.home.domain

/**
 * One crew member's relationship to one post: has read it (and when), or not.
 *
 * The wire sometimes carries the name and designation alongside the id, and
 * sometimes only the id — the UI falls back to the production's crew list for
 * the missing pieces, exactly as the web's modal does.
 */
data class ReadReceipt(
    val userId: String,
    val userName: String? = null,
    val designation: String? = null,
    /** When they read it. Zero on the unread list. */
    val readTimeMillis: Long = 0,
)

/** Who has and hasn't read a post — `message_read_by` / `message_unread_by`. */
data class ReadBy(
    val read: List<ReadReceipt> = emptyList(),
    val unread: List<ReadReceipt> = emptyList(),
)
