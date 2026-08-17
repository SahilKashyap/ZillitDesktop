package com.zillit.desktop.feature.email.domain

/**
 * One row in the message list.
 *
 * A *summary*, not the whole message: the list shows sender, subject and a
 * snippet, and pulling full bodies for a thousand-row inbox would cost megabytes
 * to render three lines each.
 */
data class EmailSummary(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: String,
    val to: List<String> = emptyList(),
    val snippet: String = "",
    val receivedAtMillis: Long = 0,
    val isRead: Boolean = false,
    val hasAttachments: Boolean = false,
    val attachmentCount: Int = 0,
    /**
     * The IMAP sequence number, and the unit of synchronisation.
     *
     * `get-folder-uids` returns every uid in the folder; whatever is not cached
     * yet is what needs fetching. Comparing on [id] instead would work but means
     * shipping message-ids over the wire to discover we already have them.
     */
    val uid: Int = 0,
    /** Which folder this row was read from — needed to fetch its body. */
    val folderName: String = "",
) {
    /** Display name from a `Name <addr@host>` header, falling back to the address. */
    val senderName: String get() = from.headerName()

    val senderAddress: String get() = from.headerAddress()

    /** Never prints subject or snippet — mail bodies are the most private thing here. */
    override fun toString(): String = "EmailSummary(id=$id, read=$isRead, attachments=$attachmentCount)"
}

/**
 * Extracts the display name from a mail header.
 *
 * `"Aisha Khan" <aisha@prod.com>` → `Aisha Khan`. Falls back to the address,
 * then to the raw value: a row with a blank sender is unusable, and mail headers
 * are written by every client ever made.
 */
internal fun String.headerName(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return "Unknown sender"

    val angle = trimmed.indexOf('<')

    // No brackets at all: the whole value is the address, and doubles as the name.
    if (angle < 0) return trimmed.removeSurrounding("\"").ifBlank { trimmed }

    // Brackets with nothing before them — `<a@b.com>` — is an address, not a
    // name, and returning the raw header would show the angle brackets.
    if (angle == 0) return trimmed.headerAddress()

    return trimmed.substring(0, angle).trim().trim('"').ifBlank { trimmed.headerAddress() }
}

/** The address inside angle brackets, or the whole value if there are none. */
internal fun String.headerAddress(): String {
    val open = indexOf('<')
    val close = indexOf('>', startIndex = open + 1)
    return if (open >= 0 && close > open) substring(open + 1, close).trim() else trim()
}

/**
 * A one-line preview.
 *
 * HTML mail is stripped rather than rendered: the list shows three lines, and a
 * marketing email's markup would otherwise fill them with tag soup.
 */
internal fun String.toSnippet(limit: Int = SNIPPET_LENGTH): String {
    val plain = replace(TAG_PATTERN, " ")
        .replace(ENTITY_PATTERN, " ")
        .replace(WHITESPACE_PATTERN, " ")
        .trim()

    return if (plain.length <= limit) plain else plain.take(limit).trimEnd() + "…"
}

private val TAG_PATTERN = Regex("<[^>]*>")
private val ENTITY_PATTERN = Regex("&[a-zA-Z#0-9]{1,8};")
private val WHITESPACE_PATTERN = Regex("\\s+")

private const val SNIPPET_LENGTH = 140
