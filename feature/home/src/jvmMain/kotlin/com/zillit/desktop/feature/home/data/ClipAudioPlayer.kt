package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.AudioPlayer
import com.zillit.desktop.feature.home.domain.PlaybackState
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Voice playback through `javax.sound.sampled.Clip`.
 *
 * ## What it can play
 *
 * Whatever the JVM's audio system decodes — which out of the box is PCM (WAV,
 * AIFF, AU). That covers every voice message this client records. Compressed
 * uploads from the phones (iOS records AAC) fail to decode here and come back
 * as a failure, which the UI answers by handing the file to the OS player —
 * the codecs live there.
 *
 * ## Position updates
 *
 * The clip is polled on a short tick while playing rather than evented: `Clip`
 * only raises START/STOP, and a progress bar needs the middle. The tick lives
 * in [scope] and stops itself when playback does.
 */
class ClipAudioPlayer(private val scope: CoroutineScope) : AudioPlayer {

    private val _state = MutableStateFlow<PlaybackState?>(null)
    override val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private val lock = Mutex()
    private var clip: Clip? = null
    private var clipKey: String? = null
    private var ticker: Job? = null

    override suspend fun toggle(key: String, bytes: ByteArray): ZillitResult<Unit> =
        lock.withLock {
            val current = clip
            when {
                // The playing message: pause where it is.
                current != null && clipKey == key && current.isRunning -> {
                    current.stop()
                    publish()
                    ZillitResult.Success(Unit)
                }

                // The paused message: carry on from where it stopped.
                current != null && clipKey == key -> {
                    current.start()
                    startTicker()
                    publish()
                    ZillitResult.Success(Unit)
                }

                // A different message: whatever was playing loses the speaker.
                else -> {
                    closeCurrent()
                    open(key, bytes)
                }
            }
        }

    private suspend fun open(key: String, bytes: ByteArray): ZillitResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val pcm = decodeToPcm(bytes)
                    ?: return@withContext ZillitResult.Failure(undecodable())

                val opened = AudioSystem.getClip()
                opened.open(pcm)
                // At the natural end the clip stops itself; the listener resets
                // the bar to the start rather than leaving it pinned full.
                opened.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP && !opened.isRunning &&
                        opened.framePosition >= opened.frameLength
                    ) {
                        opened.framePosition = 0
                        publish()
                    }
                }

                clip = opened
                clipKey = key
                opened.start()
                startTicker()
                publish()
                ZillitResult.Success(Unit)
            } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
                ZillitLog.w(TAG) { "playback failed to open: ${throwable::class.simpleName}" }
                closeCurrent()
                ZillitResult.Failure(undecodable())
            }
        }

    override fun seek(key: String, fraction: Float) {
        val current = clip ?: return
        if (clipKey != key) return

        val clamped = fraction.coerceIn(0f, 1f)
        current.framePosition = (current.frameLength * clamped).toInt()
        publish()
    }

    override fun stop() {
        clip?.stop()
        closeCurrent()
        _state.value = null
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (clip?.isRunning == true) {
                publish()
                delay(POLL_MILLIS)
            }
            publish()
        }
    }

    private fun publish() {
        val current = clip
        val key = clipKey
        _state.value = if (current == null || key == null) {
            null
        } else {
            PlaybackState(
                key = key,
                positionMillis = current.microsecondPosition / MICROS_PER_MILLI,
                durationMillis = current.microsecondLength / MICROS_PER_MILLI,
                isPlaying = current.isRunning,
            )
        }
    }

    private fun closeCurrent() {
        ticker?.cancel()
        runCatching { clip?.close() }
        clip = null
        clipKey = null
    }

    private companion object {
        const val TAG = "AudioPlayer"
        const val POLL_MILLIS = 200L
        const val MICROS_PER_MILLI = 1_000L

        fun undecodable() = ZillitError.Storage(
            technical = "the JVM cannot decode this audio format",
            userMessage = str(S.desktop_voice_opens_in_system_player),
        )
    }
}

/**
 * Decodes to line-ready PCM, or null for formats the JVM has no codec for.
 *
 * Separated from the player so the decode — the part with interesting failure
 * modes — is testable without an audio device.
 */
internal fun decodeToPcm(bytes: ByteArray): AudioInputStream? = try {
    val raw = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
    val format = raw.format
    val target = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        format.sampleRate,
        PCM_BITS,
        format.channels,
        format.channels * BYTES_PER_SAMPLE,
        format.sampleRate,
        false,
    )
    if (format.matches(target)) raw else AudioSystem.getAudioInputStream(target, raw)
} catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") throwable: Throwable) {
    // Unsupported format — the caller's fallback path is the answer, not a log
    // per scroll past an iOS voice note.
    null
}

private const val PCM_BITS = 16
private const val BYTES_PER_SAMPLE = 2
