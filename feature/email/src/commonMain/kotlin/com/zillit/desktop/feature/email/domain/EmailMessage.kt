package com.zillit.desktop.feature.email.domain

/** One attachment on a message. */
data class EmailAttachment(
    val id: String,
    val fileName: String,
    val contentType: String? = null,
    val sizeBytes: Long = 0,
) {
    /** "1.2 MB" — sized for a chip, not a table. */
    val readableSize: String
        get() = when {
            sizeBytes <= 0 -> ""
            sizeBytes < KB -> "$sizeBytes B"
            sizeBytes < MB -> "${sizeBytes / KB} KB"
            else -> "${(sizeBytes * 10 / MB) / 10.0} MB"
        }

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024L
    }
}

/**
 * A full message in a thread.
 *
 * Distinct from [EmailSummary]: the list needs three lines, the reading pane
 * needs the body, every recipient and the attachments.
 */
data class EmailMessage(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val body: String = "",
    val isHtml: Boolean = false,
    val receivedAtMillis: Long = 0,
    val attachments: List<EmailAttachment> = emptyList(),
) {
    val senderName: String get() = from.headerName()
    val senderAddress: String get() = from.headerAddress()

    /** Everyone on the message, for the "to" line. */
    val recipients: List<String> get() = to + cc

    /** Never prints the body or subject. */
    override fun toString(): String =
        "EmailMessage(id=$id, html=$isHtml, chars=${body.length}, attachments=${attachments.size})"
}

/**
 * A conversation, oldest first.
 *
 * Oldest at the top because a thread is read as a narrative — the reply makes
 * no sense before the message it answers. Mail clients that invert this force
 * the reader to scroll up to find the beginning.
 */
fun List<EmailMessage>.asThread(): List<EmailMessage> = sortedBy { it.receivedAtMillis }
