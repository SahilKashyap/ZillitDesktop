package com.zillit.desktop.feature.home.domain

import com.zillit.desktop.core.workspace.forward.ForwardFile
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardPlace

/**
 * A board post as the shared forward picker understands it.
 *
 * The board's own units are reached through [HomeFeedRepository.forwardNotice]
 * with the post itself; this is for everywhere else — the chat halves, the
 * other boards, the film tools — which the host delivers to and which know
 * nothing of a [Notice].
 */
fun Notice.asForwardItem(): ForwardItem = ForwardItem(
    text = body,
    kind = kind.asForwardKind(),
    file = attachment?.asForwardFile(),
    place = location?.let { ForwardPlace(it.lat, it.long) },
)

/** The same word on both sides of the seam — see [NoticeKind.wire]. */
fun NoticeKind.asForwardKind(): ForwardKind = when (this) {
    NoticeKind.Text -> ForwardKind.Text
    NoticeKind.Image -> ForwardKind.Image
    NoticeKind.Video -> ForwardKind.Video
    NoticeKind.Audio -> ForwardKind.Audio
    NoticeKind.Document -> ForwardKind.Document
    NoticeKind.Location -> ForwardKind.Location
}

/** The file by reference — nothing is re-uploaded, as on every client. */
fun NoticeAttachment.asForwardFile(): ForwardFile = ForwardFile(
    media = media,
    name = fileName,
    contentType = contentType,
    subtype = contentSubtype,
    bucket = bucket,
    region = region,
    thumbnail = thumbnail,
    widthPx = widthPx,
    heightPx = heightPx,
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
)

/** And back, for a post arriving from elsewhere. */
fun ForwardItem.asNotice(): Notice = Notice(
    id = "",
    body = text,
    authorName = "",
    kind = kind.asNoticeKind(),
    attachment = file?.asNoticeAttachment(),
    location = place?.let { GeoPoint(it.latitude, it.longitude) },
)

/** The mirror of [NoticeKind.asForwardKind]. */
fun ForwardKind.asNoticeKind(): NoticeKind = when (this) {
    ForwardKind.Text -> NoticeKind.Text
    ForwardKind.Image -> NoticeKind.Image
    ForwardKind.Video -> NoticeKind.Video
    ForwardKind.Audio -> NoticeKind.Audio
    ForwardKind.Document -> NoticeKind.Document
    ForwardKind.Location -> NoticeKind.Location
}

/** The mirror of [NoticeAttachment.asForwardFile]. */
fun ForwardFile.asNoticeAttachment(): NoticeAttachment = NoticeAttachment(
    media = media,
    fileName = name,
    thumbnail = thumbnail,
    contentType = contentType,
    contentSubtype = subtype,
    widthPx = widthPx,
    heightPx = heightPx,
    durationMillis = durationMillis,
    bucket = bucket,
    region = region,
    sizeBytes = sizeBytes,
)
