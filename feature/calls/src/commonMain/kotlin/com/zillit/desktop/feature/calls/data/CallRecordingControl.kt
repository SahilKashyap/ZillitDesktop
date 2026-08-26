package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.CallEngine
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
     * Starts or stops recording on this machine.
     *
     * Refused while somebody else records: one recording per call is the rule
     * every platform enforces, and the banner already names the holder.
     */
    fun toggle(session: CallSession) {
        if (_recording.value) {
            _recording.value = false
            scope.launch {
                engine.stopAudioRecording()
                plane.announceSelf(session, CallStatus.InCall, mapOf(FIELD_IS_RECORDING to false))
                stampRoster(session, active = false)
            }
            return
        }
        if (_recordedBy.value.isNotBlank()) return
        scope.launch {
            if (!engine.startAudioRecording()) {
                ZillitLog.w(TAG) { "recording refused: engine cannot record" }
                return@launch
            }
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
            _recordedBy.value = name.ifBlank { "Someone" }
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
