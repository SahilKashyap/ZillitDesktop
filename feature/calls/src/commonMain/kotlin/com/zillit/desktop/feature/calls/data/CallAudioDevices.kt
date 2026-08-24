package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.feature.calls.domain.CallDeviceKind
import com.zillit.desktop.feature.calls.domain.CallDevices
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which microphone and speaker the calls on this machine use.
 *
 * Separate from the call because the choice is: a headset picked during one
 * call is still the headset the user expects for the next one, so it is stored
 * outside the engine, whose page only lives as long as the call does.
 */
class CallAudioDevices(
    private val engine: CallEngine,
    private val scope: CoroutineScope,
    private val load: suspend () -> Pair<String, String>,
    private val save: suspend (microphoneId: String, speakerId: String) -> Unit,
) {
    private val _devices = MutableStateFlow(CallDevices())
    val devices: StateFlow<CallDevices> = _devices.asStateFlow()

    /**
     * Re-reads the hardware. Cheap, and worth doing whenever a picker opens: a
     * headset plugged in while the menu was shut is otherwise invisible until
     * the SDK's own change event happens to fire.
     */
    fun refresh() = engine.listDevices()

    /** Chooses the microphone, for this call and the ones after it. */
    fun chooseMicrophone(deviceId: String) {
        engine.setDevice(CallDeviceKind.Microphone, deviceId)
        _devices.value = _devices.value.copy(microphoneId = deviceId)
        scope.launch { save(deviceId, _devices.value.speakerId) }
    }

    /** Chooses where remote voices play. */
    fun chooseSpeaker(deviceId: String) {
        engine.setDevice(CallDeviceKind.Speaker, deviceId)
        _devices.value = _devices.value.copy(speakerId = deviceId)
        scope.launch { save(_devices.value.microphoneId, deviceId) }
    }

    /**
     * Applies the remembered choice, then asks for the lists.
     *
     * Called BEFORE the engine joins: the page builds its microphone track
     * during join, and a choice arriving afterwards would mean the first
     * seconds of every call came off the wrong device. Blank ids leave the OS
     * default alone.
     */
    suspend fun restoreBeforeJoin() {
        val (savedMic, savedSpeaker) = load()
        if (savedMic.isNotBlank()) engine.setDevice(CallDeviceKind.Microphone, savedMic)
        if (savedSpeaker.isNotBlank()) engine.setDevice(CallDeviceKind.Speaker, savedSpeaker)
    }

    /** Publishes what the engine reported, so an open picker fills in. */
    fun onEngineDevices(event: CallEngineEvent.Devices) {
        _devices.value = CallDevices(
            microphones = event.microphones,
            speakers = event.speakers,
            cameras = event.cameras,
            microphoneId = event.microphoneId,
            speakerId = event.speakerId,
        )
    }
}
