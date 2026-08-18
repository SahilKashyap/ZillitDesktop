package com.zillit.desktop.feature.home.domain

/**
 * The unit's library — Android's *Gallery* on every post's menu
 * (`HomeChatLibraryActivity`): the board's posts sorted into three tabs,
 * newest first, built from what is already loaded rather than fetched.
 *
 * Buckets follow `scanList` there: pictures and videos with a file are
 * *Media*; documents (and anything else with a file that is not a picture,
 * a video, a voice note or a map) are *Docs*; a text post whose words hold
 * a URL is a *Link* — the first URL, trailing punctuation trimmed. Voice
 * notes and locations belong to no tab, as there.
 */
data class NoticeLibrary(
    val media: List<LibraryEntry.Media>,
    val docs: List<LibraryEntry.Document>,
    val links: List<LibraryEntry.Link>,
) {
    val isEmpty: Boolean get() = media.isEmpty() && docs.isEmpty() && links.isEmpty()

    companion object {
        val Empty = NoticeLibrary(emptyList(), emptyList(), emptyList())
    }
}

/** One row of the library; every variant remembers the post it came from. */
sealed interface LibraryEntry {
    val notice: Notice

    data class Media(override val notice: Notice, val attachment: NoticeAttachment, val isVideo: Boolean) : LibraryEntry

    data class Document(override val notice: Notice, val attachment: NoticeAttachment) : LibraryEntry

    data class Link(override val notice: Notice, val url: String) : LibraryEntry
}

/** Sorts sent posts into the three tabs, newest first. */
fun List<Notice>.toLibrary(): NoticeLibrary {
    val media = mutableListOf<LibraryEntry.Media>()
    val docs = mutableListOf<LibraryEntry.Document>()
    val links = mutableListOf<LibraryEntry.Link>()
    filter { it.sendState == NoticeSendState.Sent }
        .sortedByDescending { it.createdAtMillis }
        .forEach { notice ->
            val file = notice.attachment
            when (notice.kind) {
                NoticeKind.Image, NoticeKind.Video ->
                    if (file != null && file.media.isNotBlank()) {
                        media += LibraryEntry.Media(notice, file, isVideo = notice.kind == NoticeKind.Video)
                    }

                NoticeKind.Document ->
                    if (file != null && file.media.isNotBlank()) docs += LibraryEntry.Document(notice, file)

                NoticeKind.Text -> firstUrlIn(notice.body)?.let { links += LibraryEntry.Link(notice, it) }

                // Voice notes and shared locations have no tab on Android either.
                NoticeKind.Audio, NoticeKind.Location -> Unit
            }
        }
    return NoticeLibrary(media, docs, links)
}

/**
 * The first web address in a text, or null. Android's pattern
 * (`(?i)\b(?:https?://|www\.)[^\s<>"']+`) with the trailing punctuation a
 * sentence leaves on a link (`.`, `,`, `)` …) trimmed off.
 */
fun firstUrlIn(text: String): String? {
    val match = URL_PATTERN.find(text) ?: return null
    return match.value.replace(TRAILING_PUNCTUATION, "").takeIf { it.isNotBlank() }
}

/** A `www.` link as written carries no scheme; a browser launcher needs one. */
fun String.asWebHref(): String = if (startsWith("www.", ignoreCase = true)) "https://$this" else this

private val URL_PATTERN = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
private val TRAILING_PUNCTUATION = Regex("""[.,;:!?)\]]+$""")
