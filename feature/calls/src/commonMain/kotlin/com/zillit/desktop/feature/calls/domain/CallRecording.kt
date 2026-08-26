package com.zillit.desktop.feature.calls.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * What the page managed to record.
 *
 * The container is a runtime fact, not a constant: the recorder writes MP4
 * where this Chromium build has an AAC encoder and WebM where it does not
 * (see `RECORDER_TYPES` in call.js), and the difference decides both the
 * saved file's name and whether a phone can open what we post.
 */
data class RecordedAudio(
    /** The recorder's own type string, codec parameters and all. */
    val mimeType: String,
    /** The file extension that goes with [mimeType] — `m4a` or `webm`. */
    val extension: String,
    val durationMillis: Long,
) {
    /**
     * [mimeType] without its codec parameters.
     *
     * Storage and the chat wire want the media type alone; `content_type` is
     * what receivers switch on to decide a row is a voice note, and
     * `audio/mp4;codecs=mp4a.40.2` is not a type any of them match.
     */
    val contentType: String get() = mimeType.substringBefore(';').trim()
}

/** A finished recording, saved on this machine and ready to send. */
data class CallRecording(
    val path: String,
    val contentType: String,
    val durationMillis: Long,
)

/**
 * One chat thread a recording is posted into: a group room, or one person.
 */
data class CallChatTarget(val receiverId: String, val isGroup: Boolean)

/**
 * Where a finished call recording goes once it is on disk.
 *
 * A seam rather than a direct call into chat, because a recording leaving the
 * call is a chat concern and this module knows nothing about chat. The host
 * supplies the one implementation, which uploads the file and posts it as an
 * audio message — the same thing the phones and the web do when a recording
 * stops, so a recording started on a Mac reaches the conversation the crew is
 * actually reading.
 */
fun interface CallRecordingShare {

    /**
     * Uploads [recording] once and posts it to every one of [targets].
     *
     * One upload for many targets is deliberate: the object is identical, and
     * iOS re-uploading it per recipient is a cost, not a contract.
     */
    suspend fun share(recording: CallRecording, targets: List<CallChatTarget>): ZillitResult<Unit>
}
