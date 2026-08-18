package com.zillit.desktop.feature.home.domain

import com.zillit.desktop.core.common.ZillitResult

/** Home's tab strip and the notice board behind each tab. */
interface HomeFeedRepository {
    suspend fun loadUnits(): ZillitResult<List<HomeUnit>>

    /** A page of notices older than [beforeMillis]; pass "now" for the latest. */
    suspend fun loadNotices(unitId: String, beforeMillis: Long): ZillitResult<List<Notice>>

    /**
     * Posts a notice.
     *
     * [localId] is the client-generated `unique_id`, so the returned notice can
     * replace the one already shown optimistically. [attachment] is the file
     * already uploaded to storage — with one, [text] becomes the caption.
     */
    suspend fun postNotice(
        unitId: String,
        text: String,
        localId: String,
        attachment: UploadedNoticeMedia? = null,
        /** Marks the post as a location; the attachment is its map image. */
        location: GeoPoint? = null,
    ): ZillitResult<Notice>

    /**
     * [postNotice] with the call sheet's replace flag — see
     * [NoticeDraft.replacePrevious]. Same endpoint, one extra boolean; the
     * server does the archiving. Defaults to the plain post so a repository
     * without call sheets need not know the flag exists.
     */
    suspend fun postNotice(
        unitId: String,
        text: String,
        localId: String,
        attachment: UploadedNoticeMedia?,
        location: GeoPoint?,
        replacePrevious: Boolean?,
    ): ZillitResult<Notice> = postNotice(unitId, text, localId, attachment, location)

    /**
     * The watermarked rendition of a call sheet's document — the server stamps
     * a copy per reader and hands back its keys (`GET home/chat/watermark/{id}`).
     * Both phones open and download call-sheet PDFs through this rather than
     * the raw upload; on failure they fall back to the original.
     */
    suspend fun watermarkedAttachment(noticeId: String): ZillitResult<NoticeAttachment> =
        ZillitResult.Failure(
            com.zillit.desktop.core.common.ZillitError.Storage("watermarking is not wired for this board"),
        )

    /**
     * Posts a copy of [notice] to another unit — the forward on both live
     * clients is exactly this, not a dedicated endpoint. The attachment is
     * carried by reference (same storage keys, no re-upload); the body is
     * re-encrypted; [localId] becomes the copy's fresh `unique_id`.
     */
    suspend fun forwardNotice(
        unitId: String,
        notice: Notice,
        localId: String,
    ): ZillitResult<Unit>

    /** Who has and hasn't read a post — the web's `chat/readby/{id}`. */
    suspend fun readBy(noticeId: String): ZillitResult<ReadBy>

    /**
     * The same for one reply of the post — Android's `?commentId=` on the same
     * route. Defaults to the post's receipts so a repository without replies
     * need not know the parameter exists.
     */
    suspend fun readBy(noticeId: String, commentId: String?): ZillitResult<ReadBy> = readBy(noticeId)

    /** Pins a post to the top of its board, or takes it back down. */
    suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean): ZillitResult<Unit>

    /**
     * Pushes a "you have an unread post" notification to everyone still on
     * the unread list — the web's notify button (`POST home/unit/{unitId}`).
     */
    suspend fun notifyUnread(unitId: String, noticeId: String): ZillitResult<Unit>

    /** The same for one reply's unread list — iOS adds `commentId` to the call. */
    suspend fun notifyUnread(unitId: String, noticeId: String, commentId: String?): ZillitResult<Unit> =
        notifyUnread(unitId, noticeId)

    /**
     * Replies to a notice. Returns the parent's full reply list, which is what
     * the server sends back — the caller merges it into the notice on screen.
     */
    suspend fun postComment(
        noticeId: String,
        unitId: String,
        text: String,
    ): ZillitResult<List<NoticeComment>>

    /**
     * Rewrites a reply's text. Returns the server's copy of the updated reply,
     * or null when the response did not carry one back.
     */
    suspend fun editComment(
        noticeId: String,
        commentId: String,
        text: String,
    ): ZillitResult<NoticeComment?>

    /** Removes a reply. The server soft-deletes; the board just stops showing it. */
    suspend fun deleteComment(noticeId: String, commentId: String): ZillitResult<Unit>

    /**
     * Rewrites a post's text — the caption, when the post carries a file; the
     * attachment itself is untouched. Returns the server's updated copy, or
     * null when the response did not carry one.
     */
    suspend fun editNotice(noticeId: String, text: String): ZillitResult<Notice?>

    /** Removes a post. Soft-deleted server-side, with its replies. */
    suspend fun deleteNotice(noticeId: String): ZillitResult<Unit>
}

/**
 * Where a picked file landed, plus what the picker knew about it.
 *
 * The storage fields come back from the uploader; the rest travelled with the
 * pick. Together they are exactly `formatFileStructureForServer` on the web.
 */
data class UploadedNoticeMedia(
    val kind: NoticeKind,
    val media: String,
    val bucket: String,
    val region: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    /** Playing time in milliseconds, as the web sends it. Zero when unknown. */
    val durationMillis: Long = 0,
    /** The uploaded poster frame's object key, when one was made. */
    val thumbnail: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
) {
    override fun toString(): String = "UploadedNoticeMedia(kind=$kind, bytes=$sizeBytes)"
}
