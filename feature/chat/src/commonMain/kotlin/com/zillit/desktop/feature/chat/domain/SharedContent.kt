package com.zillit.desktop.feature.chat.domain

/**
 * What a conversation has shared, sorted the way WhatsApp's "Media, links and
 * docs" sorts it: pictures and clips, files, and web addresses — newest first.
 *
 * No client and no endpoint lists a conversation's files (Android's profile
 * page carries only the picture and disappearing messages), so this is read
 * off the messages the thread holds. A shared place is not media — its
 * attachment is the sender's map screenshot — and a file still climbing to
 * storage has nothing to open yet, so neither is listed.
 */
data class SharedContent(
    val media: List<SharedFile> = emptyList(),
    val docs: List<SharedFile> = emptyList(),
    val links: List<SharedLink> = emptyList(),
) {
    /** The number beside "Media, links and docs". */
    val total: Int get() = media.size + docs.size + links.size
}

/**
 * The pictures and clips either side of the one open in the viewer: [older]
 * behind the left arrow, [newer] behind the right, as the thread reads top to
 * bottom. Null at either end, or both when the open file is not listed.
 */
data class MediaNeighbours(val older: ChatAttachment?, val newer: ChatAttachment?)

/** [MediaNeighbours] of the file stored under [mediaKey] among this conversation's media. */
fun SharedContent.neighboursOf(mediaKey: String): MediaNeighbours {
    // Newest first, so the older neighbour is the next one down the list.
    val at = media.indexOfFirst { it.file.media == mediaKey }
    if (at < 0) return MediaNeighbours(null, null)
    return MediaNeighbours(older = media.getOrNull(at + 1)?.file, newer = media.getOrNull(at - 1)?.file)
}

/** One shared file and the message that carried it. */
data class SharedFile(
    val messageId: String,
    val file: ChatAttachment,
    val atMillis: Long,
    val senderId: String,
)

/** One web address, with the words around it for context. */
data class SharedLink(
    val messageId: String,
    val url: String,
    val text: String,
    val body: String,
    val atMillis: Long,
    val senderId: String,
)

/** Sorts [messages] into [SharedContent]; the order they arrive in does not matter. */
fun sharedContent(messages: List<ChatMessage>): SharedContent {
    val newestFirst = messages
        .distinctBy { it.uniqueId.ifBlank { it.id } }
        .sortedByDescending { it.timestampMillis }
    val media = ArrayList<SharedFile>()
    val docs = ArrayList<SharedFile>()
    val links = ArrayList<SharedLink>()
    newestFirst.forEach { message ->
        val key = message.id.ifBlank { message.uniqueId }
        val file = message.attachment
        if (message.location == null && file != null && file.media.isNotBlank()) {
            val shared = SharedFile(key, file, message.timestampMillis, message.senderId)
            when (file.kind) {
                "image", "video" -> media += shared
                else -> docs += shared
            }
        }
        if (message.location == null && message.body.isNotBlank()) {
            mentionSpans(message.body) { null }
                .filterIsInstance<MentionSpan.Link>()
                // Web addresses only — an email address is a person, not a link.
                .filter { it.url.startsWith("http", ignoreCase = true) }
                .distinctBy { it.url }
                .forEach { link ->
                    links += SharedLink(
                        messageId = key,
                        url = link.url,
                        text = link.text,
                        body = message.body,
                        atMillis = message.timestampMillis,
                        senderId = message.senderId,
                    )
                }
        }
    }
    return SharedContent(media, docs, links)
}
