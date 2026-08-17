package com.zillit.desktop.feature.email.domain

/**
 * A saved, unsent message.
 *
 * ## Not IMAP, unlike everything else in this module
 *
 * Mail is synced by uid from an IMAP server. Drafts are not: they live in
 * Zillit's own store behind `email-draft`, paged by timestamp. The Drafts folder
 * still appears in `imap-folders` — IMAP has one — but it is *not* where this
 * client's drafts are, so syncing it by uid would show an empty folder while
 * the drafts sat somewhere else entirely.
 *
 * Hence [EmailFolder.DRAFTS] is special-cased everywhere it is loaded.
 */
data class EmailDraft(
    val id: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val updatedAtMillis: Long = 0,
) {
    /** Never prints the body. */
    override fun toString(): String = "EmailDraft(id=$id, to=${to.size}, chars=${body.length})"
}

/**
 * How a draft shows in the message list.
 *
 * The sender column shows the recipients instead — a draft has no sender worth
 * showing, and "who is this going to" is the only thing that distinguishes one
 * unsent message from another. Both other clients do the same.
 */
fun EmailDraft.toSummary(): EmailSummary = EmailSummary(
    id = id,
    threadId = id,
    subject = subject.ifBlank { "(no subject)" },
    from = to.joinToString(", ").ifBlank { "(no recipient)" },
    to = to,
    snippet = body.toSnippet(),
    receivedAtMillis = updatedAtMillis,
    // A draft is something you wrote; it cannot be unread.
    isRead = true,
    folderName = EmailFolder.DRAFTS,
)

/** The draft this composer is editing, as something sendable. */
fun EmailDraft.toOutgoing(): OutgoingEmail = OutgoingEmail(
    to = to,
    cc = cc,
    bcc = bcc,
    subject = subject,
    body = body,
    draftId = id,
)

/**
 * Whether there is anything worth saving.
 *
 * An empty composer opened and closed must not litter the Drafts folder — which
 * it would, since closing saves. Matches Android's check exactly.
 */
val OutgoingEmail.isWorthSaving: Boolean
    get() = to.isNotEmpty() || subject.isNotBlank() || body.isNotBlank()
