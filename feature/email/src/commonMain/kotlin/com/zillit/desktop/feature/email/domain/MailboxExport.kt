package com.zillit.desktop.feature.email.domain

/**
 * A downloadable copy of every email in one mailbox — `/v2/email-export`.
 *
 * The user asks for one, the server builds it in the background, and the
 * finished thing is one or more ZIPs holding an `.mbox` file per mail folder
 * (Apple Mail, MBox Viewer and Thunderbird all open those). It exists so a
 * crew member can keep their mail before they leave a production or the
 * production is deleted — which is also why nothing here is reachable
 * afterwards: a member who has left cannot call these routes at all.
 */
data class MailboxExport(
    val exportId: String,
    val status: MailboxExportStatus,
    /** Always `mbox`; there is no choice of format, and the server says so anyway. */
    val format: String = MBOX,
    val mailboxEmail: String = "",
    val isAccountsMailbox: Boolean = false,
    /** Zero until the server has started on it — see [progress]. */
    val totalFolders: Int = 0,
    val totalMessages: Int = 0,
    val exportedMessages: Int = 0,
    /** Emails the mail server could not return; the export still completes. */
    val skippedMessages: Int = 0,
    /** The combined size of the ZIPs finished so far. */
    val totalSizeBytes: Long = 0,
    val parts: List<MailboxExportPart> = emptyList(),
    /** A message key, set only on [MailboxExportStatus.Failed]; see §8 of the contract. */
    val error: String = "",
    val requestedOnMillis: Long = 0,
    val completedOnMillis: Long = 0,
    val expiresOnMillis: Long = 0,
) {
    /**
     * How far along, or null while the total is still unknown — the bar is
     * indeterminate then, because `total_messages` is `0` until the server
     * has counted the mailbox.
     */
    val progress: Float?
        get() = if (totalMessages <= 0) null else (exportedMessages.toFloat() / totalMessages).coerceIn(0f, 1f)

    /** Whether this export is the mailbox's one running job — see [MailboxExportStatus.isInProgress]. */
    val isInProgress: Boolean get() = status.isInProgress

    val canDownload: Boolean get() = status == MailboxExportStatus.Completed && parts.isNotEmpty()

    /** Never prints the address: a mailbox address is personal data. */
    override fun toString(): String = "MailboxExport($exportId, $status, parts=${parts.size})"

    companion object {
        const val MBOX = "mbox"
    }
}

/**
 * Where an export has got to.
 *
 * `queued` and `processing` are one state to the user: when the server loses
 * its connection to the mail host it puts the export **back** to `queued` and
 * resumes a few minutes later from where it stopped, so a retry must not
 * surface as an error or as a step backwards (`exported_messages` can also
 * dip at that moment, which is why the bar is never asserted to only rise).
 */
enum class MailboxExportStatus {
    Queued,
    Processing,
    Completed,
    Failed,
    Cancelled,
    Expired,
    ;

    val wire: String get() = name.lowercase()

    /** Queued and processing both mean "being built"; only these can be cancelled. */
    val isInProgress: Boolean get() = this == Queued || this == Processing

    /** Finished, one way or another — a new export may be started. */
    val isSettled: Boolean get() = !isInProgress

    companion object {
        fun fromWire(value: String?): MailboxExportStatus =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: Queued
    }
}

/**
 * One ZIP of a finished export.
 *
 * A mailbox can run to several parts and the user needs all of them, so each
 * is offered on its own with its size — [url] is blank everywhere except in
 * the answer to the download route, which is the only place links exist.
 */
data class MailboxExportPart(
    val partNo: Int,
    val fileName: String,
    val sizeBytes: Long = 0,
    val messageCount: Int = 0,
    /** A signed link, valid for about an hour; blank on an export read from the list. */
    val url: String = "",
) {
    /** Never prints the link: it is a bearer credential for the mailbox's contents. */
    override fun toString(): String = "MailboxExportPart($partNo, $fileName, linked=${url.isNotBlank()})"
}

/**
 * The answer to `GET /:exportId/download` — the links, and how long they last.
 *
 * Asked for only when the user clicks Download: each link works for
 * [expiresInSeconds] (an hour) and must not be stored or put on screen on
 * load. A download already under way is not cut off when its link expires.
 */
data class MailboxExportDownload(
    val exportId: String,
    val expiresInSeconds: Long = 0,
    val parts: List<MailboxExportPart> = emptyList(),
)

/**
 * What `POST /` answered.
 *
 * The server returns **HTTP 200 either way**: a new export, or the one
 * already running for this mailbox with `email_export_already_in_progress`.
 * The second is not an error — the running export is shown instead — so the
 * distinction is carried here rather than as a failure.
 */
data class StartedMailboxExport(
    val export: MailboxExport,
    val alreadyRunning: Boolean = false,
    /** The server's message key, for the one line shown above the export. */
    val message: String = "",
)

/** "965 MB" — a part's size, up to the gigabytes an export really reaches. */
fun exportSizeLabel(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < SIZE_STEP -> "$bytes B"
    bytes < SIZE_STEP * SIZE_STEP -> "${bytes / SIZE_STEP} KB"
    bytes < SIZE_STEP * SIZE_STEP * SIZE_STEP -> "${bytes / (SIZE_STEP * SIZE_STEP)} MB"
    else -> {
        // One decimal place, worked in tenths so there is no floating point
        // in a figure read off a button.
        val tenths = bytes * TENTHS / (SIZE_STEP * SIZE_STEP * SIZE_STEP)
        "${tenths / TENTHS}.${tenths % TENTHS} GB"
    }
}

private const val SIZE_STEP = 1024L
private const val TENTHS = 10L
