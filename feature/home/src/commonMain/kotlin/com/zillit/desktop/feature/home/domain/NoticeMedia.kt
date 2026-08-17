package com.zillit.desktop.feature.home.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.StateFlow

/**
 * A file the user picked to post.
 *
 * Mirrors email's `PickedFile` rather than importing it: three fields do not
 * justify a feature-to-feature dependency, and the app module adapts one to the
 * other where both are in scope.
 */
data class PickedMedia(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
    /** Playing time, for audio and video. Zero when unknown. */
    val durationMillis: Long = 0,
    /** A poster frame, JPEG, generated at pick time for videos. */
    val thumbnailBytes: ByteArray? = null,
    val thumbnailWidth: Int = 0,
    val thumbnailHeight: Int = 0,
) {
    /** A PDF document — the one document kind that gets a poster frame. */
    val isPdf: Boolean
        get() = contentType.equals("application/pdf", ignoreCase = true) ||
            name.endsWith(".pdf", ignoreCase = true)

    /** How the post will render, derived the way the web classifies uploads. */
    val kind: NoticeKind
        get() = when {
            contentType.startsWith("image/") -> NoticeKind.Image
            contentType.startsWith("video/") -> NoticeKind.Video
            contentType.startsWith("audio/") -> NoticeKind.Audio
            else -> NoticeKind.Document
        }

    override fun toString(): String = "PickedMedia(type=$contentType, size=${bytes.size})"

    /** Identity, not content: a 200 MB video should not be compared byte-wise. */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = name.hashCode()
}

/**
 * Fetches notice media from wherever the production stores it.
 *
 * The bubble asks for [preview] first — the thumbnail key when the upload made
 * one — and the lightbox asks for the full object. Implementations cache:
 * scrolling a board must not refetch every image on screen.
 */
interface NoticeMediaSource {
    suspend fun fetch(attachment: NoticeAttachment, preview: Boolean): ZillitResult<ByteArray>
}

/** A finished voice recording, ready to travel as a [PickedMedia]. */
data class RecordedAudio(val bytes: ByteArray, val durationMillis: Long) {
    fun toPickedMedia(name: String): PickedMedia =
        PickedMedia(name = name, contentType = WAV_TYPE, bytes = bytes, durationMillis = durationMillis)

    override fun toString(): String = "RecordedAudio(bytes=${bytes.size}, ms=$durationMillis)"
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = durationMillis.hashCode()

    private companion object {
        /**
         * WAV, deliberately: every other client plays received audio through
         * its platform player (`<audio>`, AVPlayer, ExoPlayer), and PCM WAV is
         * the one format none of them can be missing a codec for.
         */
        const val WAV_TYPE = "audio/wav"
    }
}

// The playback port and its state moved to the design system when the chat
// grew voice bubbles — one speaker shared by every surface. These aliases
// keep the board's many call sites untouched.
typealias PlaybackState = com.zillit.desktop.core.designsystem.component.PlaybackState

typealias AudioPlayer = com.zillit.desktop.core.designsystem.component.AudioPlayer

/**
 * A microphone, as the composer sees one.
 *
 * [stop] returns what was captured; [cancel] discards it. One recording at a
 * time — starting while started is an error surfaced to the caller, not a
 * second stream.
 */
interface AudioRecorder {
    suspend fun start(): ZillitResult<Unit>
    suspend fun stop(): ZillitResult<RecordedAudio>
    fun cancel()
}
