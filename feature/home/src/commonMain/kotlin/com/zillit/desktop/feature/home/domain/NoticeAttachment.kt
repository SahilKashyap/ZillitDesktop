package com.zillit.desktop.feature.home.domain

/**
 * What kind of post this is.
 *
 * The wire's `message_type`. It decides how the bubble renders — a caption
 * under an image, a document chip, plain text — so an unknown value falls back
 * to [Text] rather than to nothing: a post from a newer client should degrade
 * to its caption, not vanish.
 */
enum class NoticeKind(val wire: String) {
    Text("text"),
    Image("image"),
    Video("video"),
    Audio("audio"),
    Document("document"),
    Location("location"),
    ;

    companion object {
        fun of(wire: String?): NoticeKind =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: Text
    }
}

/**
 * A file attached to a notice.
 *
 * The wire's `attachment` object — **singular**; a post carries at most one.
 * [media] and [thumbnail] are S3 object keys, not URLs: viewing means signing a
 * GET against the bucket with the production's storage credentials, exactly as
 * the web does. Nothing here is fetchable without them, which is also why the
 * keys are safe to hold in memory.
 */
data class NoticeAttachment(
    /** The object key. Called `media` on the wire, as everywhere else. */
    val media: String,
    val fileName: String = "",
    /** A smaller rendition's key, when one was made at upload time. */
    val thumbnail: String? = null,
    val contentType: String? = null,
    /** The extension, roughly — `pdf`, `jpg`. The wire's `content_subtype`. */
    val contentSubtype: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val durationMillis: Long = 0,
    val bucket: String? = null,
    val region: String? = null,
    val sizeBytes: Long = 0,
) {
    /** What the thumbnail-first paths should fetch. */
    val previewKey: String get() = thumbnail?.takeIf { it.isNotBlank() } ?: media

    /** Whether S3 can be asked for this at all. */
    val isFetchable: Boolean get() = !bucket.isNullOrBlank() && !region.isNullOrBlank()

    /** Never prints the key or name — file names on a production are content. */
    override fun toString(): String =
        "NoticeAttachment(kindHint=$contentSubtype, bytes=$sizeBytes, fetchable=$isFetchable)"
}

/** "2.4 MB", for the bubble footer — matching the web's `formatFileSize`. */
fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < KILO -> "$bytes B"
    bytes < MEGA -> "${(bytes * TEN / KILO).toDouble() / TEN} KB"
    bytes < GIGA -> "${(bytes * TEN / MEGA).toDouble() / TEN} MB"
    else -> "${(bytes * TEN / GIGA).toDouble() / TEN} GB"
}

private const val KILO = 1024L
private const val MEGA = KILO * KILO
private const val GIGA = MEGA * KILO
private const val TEN = 10L
