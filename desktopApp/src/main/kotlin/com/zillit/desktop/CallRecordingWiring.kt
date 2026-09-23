package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.calls.domain.CallChatTarget
import com.zillit.desktop.feature.calls.domain.CallRecording
import com.zillit.desktop.feature.calls.domain.CallRecordingShare
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.StoredFile
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where a finished call recording goes: up to storage, then into the call's
 * chat as an audio message.
 *
 * Lives here rather than in the calls module because it is the join between
 * two features that know nothing of each other — the call owns the recording,
 * chat owns the thread it belongs in. The pipeline is the one chat voice notes
 * already use, so a recording arrives as an ordinary voice message on every
 * client rather than as a file type only this app understands.
 */
internal fun callRecordingShare(
    uploader: AttachmentUploader,
    chat: ChatRepository,
    now: () -> Long = { System.currentTimeMillis() },
    newUniqueId: () -> String = { UUID.randomUUID().toString() },
): CallRecordingShare = CallRecordingShare { recording, targets ->
    val file = File(recording.path)
    val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
    if (bytes == null || bytes.isEmpty()) {
        // The path came from our own save a moment ago, so this is a disk
        // fault or a user who moved the file — either way there is nothing to
        // send, and saying so beats posting an empty voice note.
        ZillitLog.w(TAG) { "call recording unreadable at ${recording.path}" }
        return@CallRecordingShare ZillitResult.Failure(
            ZillitError.Storage(
                technical = "call recording unreadable at ${recording.path}",
                userMessage = str(S.desktop_recording_unreadable),
            ),
        )
    }
    when (val stored = uploader.upload(file.name, recording.contentType, bytes)) {
        is ZillitResult.Failure -> stored
        is ZillitResult.Success -> post(chat, stored.data, recording, targets, now, newUniqueId)
    }
}

/**
 * Posts one uploaded recording into every thread it belongs in.
 *
 * Every target is attempted even after one fails: a group room being down is
 * no reason for the other people on the call not to get the recording. The
 * first failure is what comes back, so the user is told something went wrong
 * without being told it all went wrong.
 */
@Suppress("LongParameterList") // The upload, the file it came from, and where it goes.
private suspend fun post(
    chat: ChatRepository,
    stored: StoredFile,
    recording: CallRecording,
    targets: List<CallChatTarget>,
    now: () -> Long,
    newUniqueId: () -> String,
): ZillitResult<Unit> {
    val attachment = ChatAttachment(
        media = stored.media,
        name = stored.fileName,
        // The recorder's type, not the store's guess: `content_type` is what
        // every receiver switches on to draw a row as a voice note.
        contentType = recording.contentType,
        bucket = stored.bucket,
        region = stored.region,
        durationMillis = recording.durationMillis,
    )
    var firstFailure: ZillitResult.Failure? = null
    targets.forEach { target ->
        val outcome = chat.send(
            receiverId = target.receiverId,
            // iOS's caption, verbatim, so one thread does not name the same
            // thing two ways depending on who recorded it.
            body = CAPTION,
            uniqueId = newUniqueId(),
            nowMillis = now(),
            isGroup = target.isGroup,
            attachment = attachment,
        )
        if (outcome is ZillitResult.Failure) {
            ZillitLog.w(TAG) { "recording not delivered to ${target.receiverId}: ${outcome.error}" }
            if (firstFailure == null) firstFailure = outcome
        } else {
            ZillitLog.i(TAG) { "recording sent to ${target.receiverId} (group=${target.isGroup})" }
        }
    }
    return firstFailure ?: ZillitResult.Success(Unit)
}

private const val TAG = "CallRecording"

/** What the message says above the player. iOS's wording. */
private const val CAPTION = "Call Recording"
