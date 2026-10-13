package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.workspace.forward.ForwardFile
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardPlace

/**
 * A chat line as the shared forward picker understands it.
 *
 * The thread's own destinations — a person, a room — are reached through
 * [ForwardTarget] and the repository's send; this is for everywhere else (the
 * boards, the mailbox, the film tools), which the host delivers to and which
 * know nothing of a [ChatMessage].
 */
fun ChatMessage.asForwardItem(): ForwardItem = ForwardItem(
    text = body,
    kind = forwardKind(),
    file = attachment?.asForwardFile(),
    place = location?.let { ForwardPlace(it.lat, it.lng) },
)

/**
 * The wire's `message_type` for this line: a place is a place whatever it
 * carries (a phone-sent one has a map screenshot attached), then the file's
 * own kind, then plain words.
 */
fun ChatMessage.forwardKind(): ForwardKind = when {
    location != null -> ForwardKind.Location
    attachment == null -> ForwardKind.Text
    else -> when (attachment.kind) {
        "image" -> ForwardKind.Image
        "video" -> ForwardKind.Video
        "audio" -> ForwardKind.Audio
        else -> ForwardKind.Document
    }
}

/** The file by reference — nothing is re-uploaded, as on every client. */
fun ChatAttachment.asForwardFile(): ForwardFile = ForwardFile(
    media = media,
    name = name,
    contentType = contentType,
    // The chat wire carries no `content_subtype`; the name's extension is
    // what the document tools read a PDF from on this side.
    subtype = name.substringAfterLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() },
    bucket = bucket,
    region = region,
    thumbnail = thumbnail.takeIf { it.isNotBlank() },
    widthPx = widthPx.toInt(),
    heightPx = heightPx.toInt(),
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
)

/** And back, for a line arriving from elsewhere. */
fun ForwardFile.asChatAttachment(): ChatAttachment = ChatAttachment(
    media = media,
    name = name,
    contentType = contentType.orEmpty(),
    bucket = bucket.orEmpty(),
    region = region.orEmpty(),
    thumbnail = thumbnail.orEmpty(),
    widthPx = widthPx.toLong(),
    heightPx = heightPx.toLong(),
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
)
