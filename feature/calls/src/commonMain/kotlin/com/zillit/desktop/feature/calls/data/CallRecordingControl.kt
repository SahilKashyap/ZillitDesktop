package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallChatTarget
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Call recording, as its own collaborator.
 *
 * Shares nothing with the phase machine but the live session — the same
 * reasoning that put reactions in [InCallDataChannel]. The engine records;
 * this owns who is recording and mirrors the fact the way iOS does:
 * `isRecording` on our own row, and the recorded-flag pair stamped on every
 * participant's row so each client finds it on its own.
 */
class CallRecordingControl(
    private val engine: CallEngine,
    private val plane: CallStatusPlane,
    private val scope: CoroutineScope,
    private val selfDeviceId: () -> String?,
) {

    /** This machine is recording the call. */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Who else is recording — their display name, or blank for nobody. */
    private val _recordedBy = MutableStateFlow("")
    val recordedBy: StateFlow<String> = _recordedBy.asStateFlow()

    /** The device/user currently credited with the remote recording. */
    private var remoteRecorderKey: String = ""

    /**
     * The call the running — or most recently finished — recording belongs to.
     *
     * Held because the file outlives the call. The page delivers it after the
     * recorder has stopped, and a recording ended by hanging up arrives once
     * the coordinator has already cleared the live session, so who the
     * recording gets sent to must be read from here rather than from whatever
     * call is live by then. Deliberately survives [reset] for that reason.
     */
    var recordedSession: CallSession? = null
        private set

    /**
     * Starts or stops recording on this machine.
     *
     * Refused while somebody else records: one recording per call is the rule
     * every platform enforces, and the banner already names the holder.
     */
    fun toggle(session: CallSession, mark: suspend (recording: Boolean) -> Boolean = { true }) {
        if (_recording.value) {
            _recording.value = false
            scope.launch {
                engine.stopAudioRecording()
                // Best effort, after the fact: leaving clears the mark server-side anyway.
                mark(false)
                plane.announceSelf(session, CallStatus.InCall, mapOf(FIELD_IS_RECORDING to false))
                stampRoster(session, active = false)
            }
            return
        }
        if (_recordedBy.value.isNotBlank()) return
        scope.launch {
            // The server's say-so first, where the line has one (Line 3's
            // `mark-started`): a second recorder is refused there, and the
            // local recorder must not run for a recording nobody else sees.
            if (!mark(true)) return@launch
            if (!engine.startAudioRecording()) {
                ZillitLog.w(TAG) { "recording refused: engine cannot record" }
                mark(false)
                return@launch
            }
            recordedSession = session
            _recording.value = true
            plane.announceSelf(session, CallStatus.InCall, mapOf(FIELD_IS_RECORDING to true))
            stampRoster(session, active = true)
        }
    }

    /**
     * Tracks who holds the remote recording, whichever channel said so.
     *
     * Keyed so a stop only clears the credit its own start earned — two
     * people toggling in sequence must not blank each other's banner.
     */
    fun onRemoteFlag(key: String, recording: Boolean, name: String) {
        if (key.isBlank()) return
        if (recording) {
            remoteRecorderKey = key
            _recordedBy.value = name.ifBlank { str(S.history_someone) }
        } else if (remoteRecorderKey == key) {
            remoteRecorderKey = ""
            _recordedBy.value = ""
        }
    }

    /**
     * A recording does not carry into the next call. The engine's leave
     * already stopped the recorder, and its file is delivered regardless of
     * how the call ended.
     */
    fun reset() {
        // recordedSession is NOT cleared here: see its own comment — the file
        // it names is still in flight when a hung-up call resets.
        _recording.value = false
        _recordedBy.value = ""
        remoteRecorderKey = ""
    }

    private suspend fun stampRoster(session: CallSession, active: Boolean) {
        val fields = mapOf(
            FIELD_CALL_RECORDED to active,
            FIELD_RECORDING_BY to if (active) selfDeviceId().orEmpty() else "",
        )
        session.participants
            .map(CallParticipant::deviceId)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { deviceId -> plane.updateUserFields(session, deviceId, fields) }
    }

    private companion object {
        const val TAG = "CallRecordingControl"

        /** iOS's spellings, verbatim — see its start/stopRecording writes. */
        const val FIELD_IS_RECORDING = "isRecording"
        const val FIELD_CALL_RECORDED = "call_is_being_recorded"
        const val FIELD_RECORDING_BY = "recording_by"
    }
}

/**
 * The chat threads a finished recording is posted into.
 *
 * A group call has one thread — the room it was struck from — and one message
 * goes there, which is what iOS does and what stops a ten-person call
 * producing ten copies of the same file. A private call has no room, so the
 * recording goes to each other person's own thread.
 *
 * Participants the server only ever named by device id are dropped rather than
 * guessed at: the chat server silently discards a message addressed to a
 * device id, so a "sent" recording would simply never arrive. The two
 * fallbacks are for exactly that roster — who we rang on an outgoing call, and
 * who rang us on an incoming one — which is the same pair of sources iOS
 * reaches for when its own participant list has no user id to offer.
 */
internal fun recordingTargets(session: CallSession, selfUserId: String?): List<CallChatTarget> {
    if (session.mode == CallMode.Group && session.chatRoomId.isNotBlank()) {
        return listOf(CallChatTarget(receiverId = session.chatRoomId, isGroup = true))
    }
    val others = session.participants
        .map(CallParticipant::userId)
        .filter { it.isNotBlank() && it != selfUserId }
        .distinct()
    val recipients = others.ifEmpty {
        listOf(session.receiverUserId, session.displayUserId)
            .filter { it.isNotBlank() && it != selfUserId }
            .distinct()
            .take(1)
    }
    return recipients.map { CallChatTarget(receiverId = it, isGroup = false) }
}
