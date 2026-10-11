package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.VideoSend

/**
 * A media send bound, the moment it starts, to the production it was sent in.
 *
 * Compressing and uploading a large video takes minutes, and the user may
 * switch production meanwhile. The view model's own repository and the
 * app's uploader follow whichever production is open, so a send that
 * outlived a switch used to land in the new one — the old production's
 * peer, written into the new production's chat. This holds the storage and
 * the conversation of the production the user was in when they pressed Send,
 * whatever is open by the time the bytes arrive.
 */
interface PinnedChatMedia {
    val projectId: String

    /** The production's name, for the app-wide Uploads tray. */
    val projectName: String

    /** The file into this production's storage — compressed first when [videoSend] asks. */
    suspend fun upload(
        name: String,
        contentType: String,
        bytes: ByteArray,
        videoSend: VideoSend?,
        onProgress: (Int) -> Unit,
    ): ChatAttachment?

    /** The message into this production's conversation. */
    suspend fun send(
        peerId: String,
        body: String,
        uniqueId: String,
        timestampMillis: Long,
        isGroup: Boolean,
        attachment: ChatAttachment,
    ): ZillitResult<Unit>
}
