package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.CallAudioDevices
import com.zillit.desktop.feature.calls.domain.CallDeviceKind
import com.zillit.desktop.feature.calls.domain.CallEngine
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CallAudioDevicesTest {

    /** Records which devices the page was told to use. */
    private class DeviceEngine : CallEngine {
        val applied = mutableListOf<Pair<CallDeviceKind, String>>()
        override val events: Flow<CallEngineEvent> = emptyFlow()
        override val isReady: Boolean = true
        override suspend fun initialize(): Boolean = true
        override suspend fun join(params: CallJoin) = Unit
        override suspend fun leave() = Unit
        override fun setMicrophoneMuted(muted: Boolean) = Unit
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun setSpeakerEnabled(enabled: Boolean) = Unit
        override fun setDevice(kind: CallDeviceKind, deviceId: String) {
            applied += kind to deviceId
        }
        override fun switchCamera() = Unit
        override suspend fun destroy() = Unit
    }

    @Test
    fun `a remembered microphone the page could not open is forgotten for good`() =
        runTest(StandardTestDispatcher()) {
            var saved = "gone-mic" to "headset"
            val engine = DeviceEngine()
            val devices = CallAudioDevices(engine, backgroundScope, load = { saved }, save = { m, s -> saved = m to s })
            devices.restoreBeforeJoin()

            devices.forget(CallDeviceKind.Microphone)
            runCurrent()

            // Saved as "System default", so the next Line 1 / Line 2 call no
            // longer asks for it exactly and fails; the speaker is untouched.
            assertEquals("" to "headset", saved)
            assertEquals(CallDeviceKind.Microphone to "", engine.applied.last())
            assertEquals("", devices.devices.value.microphoneId)
        }

    @Test
    fun `a remembered speaker that is gone goes back to the system default`() =
        runTest(StandardTestDispatcher()) {
            var saved = "mic" to "gone-speaker"
            val engine = DeviceEngine()
            val devices = CallAudioDevices(engine, backgroundScope, load = { saved }, save = { m, s -> saved = m to s })
            devices.restoreBeforeJoin()
            // What the page reported once the call was up.
            devices.onEngineDevices(
                CallEngineEvent.Devices(emptyList(), emptyList(), emptyList(), "mic", "gone-speaker"),
            )

            devices.forget(CallDeviceKind.Speaker)
            runCurrent()

            assertEquals("mic" to "", saved)
            assertEquals(CallDeviceKind.Speaker to "", engine.applied.last())
        }
}
