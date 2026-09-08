package com.zillit.desktop.feature.calls.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallPhase
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rings while a call rings, and only then.
 *
 * Two cadences, synthesized into the bundle (`callsounds/`): a two-tone
 * 740/880 Hz ring for an incoming call, and a 425 Hz ringback while our own
 * call waits. Both are ports of the web client's WebAudio tones
 * (`zillit_web .../lineTwo/ui/callSounds.ts`) so a call sounds the same
 * whichever client you answer it on — regenerate with
 * `scripts/make-call-sounds.py` rather than editing the WAVs by hand.
 *
 * Each clip holds exactly ONE period of its cadence, trailing silence
 * included, because [Clip.LOOP_CONTINUOUSLY] below repeats the whole file:
 * the silence between rings IS part of the asset.
 *
 * Driven purely off the coordinator's phase, so there is no separate
 * "remember to stop the sound" call anywhere — teardown of the phase IS
 * teardown of the sound, which is how a ring can never outlive its call
 * (the Android bug class this design avoids).
 *
 * WAV through `javax.sound.sampled` deliberately: it needs no engine, no
 * Chromium, and no OS media framework, so a signalling-only build rings too.
 */
class CallRinger(
    coordinator: CallCoordinator,
    scope: CoroutineScope,
) {

    private enum class Sound(val resource: String) {
        Incoming("/callsounds/incoming.wav"),
        Outgoing("/callsounds/outgoing.wav"),
    }

    private var clip: Clip? = null
    private var playing: Sound? = null

    init {
        scope.launch {
            combine(coordinator.phase, coordinator.session) { phase, session ->
                when {
                    phase == CallPhase.Incoming -> Sound.Incoming
                    phase == CallPhase.Outgoing &&
                        session?.direction == CallDirection.Outgoing -> Sound.Outgoing
                    else -> null
                }
            }.collect { wanted -> retune(wanted) }
        }
    }

    private suspend fun retune(wanted: Sound?) = withContext(Dispatchers.IO) {
        if (wanted == playing) return@withContext
        stop()
        if (wanted != null) start(wanted)
    }

    private fun start(sound: Sound) {
        runCatching {
            val stream = javaClass.getResourceAsStream(sound.resource)
                ?: error("missing ${sound.resource}")
            val audio = AudioSystem.getAudioInputStream(BufferedInputStream(stream))
            clip = AudioSystem.getClip().apply {
                open(audio)
                loop(Clip.LOOP_CONTINUOUSLY)
            }
            playing = sound
        }.onFailure { t ->
            // A machine with no audio device still takes calls; it just rings
            // silently. Said once in the log rather than thrown at the user.
            ZillitLog.w(TAG) { "ringtone unavailable: ${t::class.simpleName}" }
        }
    }

    private fun stop() {
        clip?.runCatching {
            stop()
            close()
        }
        clip = null
        playing = null
    }

    private companion object {
        const val TAG = "CallRinger"
    }
}
