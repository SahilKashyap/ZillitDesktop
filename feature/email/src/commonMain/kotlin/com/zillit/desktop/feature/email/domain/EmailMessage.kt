package com.zillit.desktop.feature.email.domain

/** One attachment on a message. */
data class EmailAttachment(
    val id: String,
    val fileName: String,
    val contentType: String? = null,
    val sizeBytes: Long = 0,
    /**
     * The `Content-ID` an inline image is referenced by (`<img src="cid:…">`).
     * Null for an ordinary file.
     */
    val contentId: String? = null,
    /** `inline` for a picture embedded in the body, `attachment` for a file. */
    val contentDisposition: String = "attachment",
    /** Where the bytes already live, when the server told us (a forwarded file). */
    val media: String? = null,
    val bucket: String? = null,
    val region: String? = null,
) {
    /**
     * Whether this is a picture the body draws rather than a file to list.
     *
     * The web hides these from the attachment strip (`filterEmbeddedImages`):
     * a logo in a signature is part of the message, not something to download.
     */
    fun isEmbeddedIn(bodyCids: Collection<String>): Boolean =
        contentDisposition.equals("inline", ignoreCase = true) ||
            (contentId != null && contentId in bodyCids) ||
            id in bodyCids

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
    val bcc: List<String> = emptyList(),
    /**
     * Who a reply goes to — the `Reply-To` header when the sender set one,
     * the sender otherwise. Every reply rule on the web starts from this
     * field (`ComposeModal.jsx`, `currentEmail.reply_to`).
     */
    val replyTo: String = "",
    val body: String = "",
    val isHtml: Boolean = false,
    val receivedAtMillis: Long = 0,
    /** The folder the copy was read from: a reply from Sent is addressed differently. */
    val folderName: String = "",
    /** The IMAP uid of this copy, for the badge ledger. */
    val uid: Int = 0,
    val isRead: Boolean = true,
    val attachments: List<EmailAttachment> = emptyList(),
    /**
     * The `References` chain this message arrived with, oldest first.
     *
     * Carried so a reply can send it back with this message's own id appended
     * — which is what threads the reply onto the conversation. Without it a
     * reply to anything but the very first message arrives as a brand-new
     * conversation (both phones keep the chain: Android `Email.references`,
     * the web `currentEmail.references`).
     */
    val references: List<String> = emptyList(),
    /** The `In-Reply-To` header, when the server sends one. */
    val inReplyTo: String = "",
) {
    /**
     * The chain a reply to this message should carry: everything this message
     * references, then this message itself — RFC 5322, and the web's
     * `computeReferences` rule for the same reason.
     */
    val replyReferences: List<String>
        get() = (references + id).filter { it.isNotBlank() }.distinct()

    val senderName: String get() = from.headerName()
    val senderAddress: String get() = from.headerAddress()

    /** The address a reply is sent to. */
    val replyAddress: String get() = replyTo.takeIf { it.isNotBlank() }?.headerAddress() ?: senderAddress

    /** Everyone on the message, for the "to" line. */
    val recipients: List<String> get() = to + cc + bcc

    /** The `cid:` references the body makes, so inline pictures are told from files. */
    val bodyCids: Set<String> get() = CID_PATTERN.findAll(body).map { it.groupValues[1] }.toSet()

    /** Files worth listing — everything the body does not already draw. */
    val listedAttachments: List<EmailAttachment>
        get() = bodyCids.let { cids -> attachments.filterNot { it.isEmbeddedIn(cids) } }

    /** Never prints the body or subject. */
    override fun toString(): String =
        "EmailMessage(id=$id, html=$isHtml, chars=${body.length}, attachments=${attachments.size})"
}

private val CID_PATTERN = Regex("cid:([^\"'\\s>]+)")

/**
 * A conversation, oldest first.
 *
 * Oldest at the top because a thread is read as a narrative — the reply makes
 * no sense before the message it answers. Mail clients that invert this force
 * the reader to scroll up to find the beginning.
 */
fun List<EmailMessage>.asThread(): List<EmailMessage> = sortedBy { it.receivedAtMillis }
