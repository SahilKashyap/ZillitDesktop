package com.zillit.desktop.feature.chat.domain

/**
 * A downloadable copy of every conversation in this production — `/v2/chat-export`.
 *
 * The user asks for one, the server builds it in the background, and the
 * finished thing is a single ZIP of web pages: an `index.html` listing every
 * conversation, a page per chat, and — when [includeMedia] was asked for —
 * the photos, clips, voice notes and documents beside them. It opens in any
 * browser with nothing installed, which is the point: a crew member keeps
 * their own record of what was said.
 *
 * What is in it is the server's business, but two exclusions matter on
 * screen: messages deleted for everyone and groups the user has left are not
 * there, so an export is never a complete history of the production — only
 * of the conversations this user can still open.
 */
data class ChatExport(
    val exportId: String,
    val status: ChatExportStatus,
    /** Whether the photos, clips and files travel with the text. */
    val includeMedia: Boolean = true,
    /** Zero while `queued`; set when the server starts counting — see [progress]. */
    val totalConversations: Int = 0,
    val exportedConversations: Int = 0,
    val exportedMessages: Int = 0,
    val exportedMedia: Int = 0,
    /** Attachments that could not be read. Their messages are in the export, marked. */
    val skippedMedia: Int = 0,
    /** Both set only when [ChatExportStatus.Completed]. */
    val fileName: String = "",
    val sizeBytes: Long = 0,
    /** A message key, set only on [ChatExportStatus.Failed]. */
    val error: String = "",
    val requestedOnMillis: Long = 0,
    val completedOnMillis: Long = 0,
    /** When the ZIP is deleted — seven days after it was built. */
    val expiresOnMillis: Long = 0,
) {
    /**
     * How far along, or null while the total is still unknown — the bar is
     * indeterminate then, because `total_conversations` is `0` until the
     * server has counted the conversations.
     */
    val progress: Float?
        get() = if (totalConversations <= 0) {
            null
        } else {
            (exportedConversations.toFloat() / totalConversations).coerceIn(0f, 1f)
        }

    /** Whether this is the user's one running export — see [ChatExportStatus.isInProgress]. */
    val isInProgress: Boolean get() = status.isInProgress

    val canDownload: Boolean get() = status == ChatExportStatus.Completed

    override fun toString(): String = "ChatExport($exportId, $status, media=$includeMedia)"
}

/**
 * Where an export has got to.
 *
 * `queued` and `processing` are one state to the user: a temporary failure
 * puts a running export **back** to `queued` and it retries within minutes,
 * so `processing → queued` must not surface as an error or as a step
 * backwards (the counters can dip at that moment too, which is why nothing
 * here asserts that progress only rises).
 */
enum class ChatExportStatus {
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
        fun fromWire(value: String?): ChatExportStatus =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: Queued
    }
}

/**
 * The answer to `GET /:exportId/download` — the link, and how long it lasts.
 *
 * Asked for only when the user clicks Download: the link is pre-signed for
 * [expiresInSeconds] (an hour) and must not be stored, reused or put on
 * screen on load. It is a plain S3 address and carries no `moduledata`.
 */
data class ChatExportDownload(
    val exportId: String,
    val fileName: String = "",
    val sizeBytes: Long = 0,
    val expiresInSeconds: Long = 0,
    val url: String = "",
) {
    /** Never prints the link: it is a bearer credential for every chat in the ZIP. */
    override fun toString(): String = "ChatExportDownload($exportId, $fileName, linked=${url.isNotBlank()})"
}

/**
 * What `POST /` answered.
 *
 * The server returns **HTTP 200 either way**: a new export, or the one
 * already running for this user with `cnc_chat_export_already_in_progress`.
 * The second is not an error — the running export is shown instead — so the
 * distinction is carried here rather than as a failure.
 */
data class StartedChatExport(
    val export: ChatExport,
    val alreadyRunning: Boolean = false,
    /** The server's message key, for the one line shown above the export. */
    val message: String = "",
)

/** "46 MB" — an export's size, up to the gigabytes a media export reaches. */
fun chatExportSizeLabel(bytes: Long): String = when {
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
