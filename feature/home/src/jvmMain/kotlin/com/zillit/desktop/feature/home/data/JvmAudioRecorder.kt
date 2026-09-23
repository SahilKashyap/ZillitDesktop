package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.AudioRecorder
import com.zillit.desktop.feature.home.domain.RecordedAudio
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The system microphone, through `javax.sound.sampled`.
 *
 * ## The format
 *
 * 16 kHz, 16-bit, mono PCM, wrapped as WAV on stop. Voice needs no more, and
 * the arithmetic matters: this is ~32 KB/s, so a two-minute message is under
 * 4 MB through the same S3 pipeline photos use. WAV rather than a compressed
 * codec because the JVM ships no encoder — and every receiving client plays it
 * through a platform player that cannot be missing PCM.
 *
 * ## Threads
 *
 * The capture loop owns a plain thread rather than a coroutine: `line.read`
 * blocks in native code and does not cancel, so a coroutine would only dress
 * the thread up. [stop] closes the line, which unblocks the read, which lets
 * the thread finish — in that order.
 */
class JvmAudioRecorder : AudioRecorder {

    private var line: TargetDataLine? = null
    private var captured: ByteArrayOutputStream? = null
    private var capture: Thread? = null
    private var startedAtNanos: Long = 0

    override suspend fun start(): ZillitResult<Unit> = withContext(Dispatchers.IO) {
        if (line != null) {
            return@withContext ZillitResult.Failure(
                ZillitError.Validation("A recording is already running."),
            )
        }

        try {
            val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
            if (!AudioSystem.isLineSupported(info)) {
                return@withContext ZillitResult.Failure(noMicrophone())
            }

            val opened = AudioSystem.getLine(info) as TargetDataLine
            opened.open(FORMAT)
            opened.start()

            val sink = ByteArrayOutputStream()
            line = opened
            captured = sink
            startedAtNanos = System.nanoTime()

            capture = thread(name = "zillit-audio-capture") {
                val buffer = ByteArray(BUFFER_BYTES)
                // The loop ends when stop() closes the line and read returns
                // what it has; -1 or a closed line ends it.
                while (opened.isOpen) {
                    val read = opened.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                }
            }

            ZillitResult.Success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            // SecurityException here is the OS denying microphone access.
            ZillitLog.w(TAG) { "could not open the microphone: ${throwable::class.simpleName}" }
            reset()
            ZillitResult.Failure(noMicrophone())
        }
    }

    override suspend fun stop(): ZillitResult<RecordedAudio> = withContext(Dispatchers.IO) {
        val open = line
            ?: return@withContext ZillitResult.Failure(
                ZillitError.Validation(str(S.desktop_nothing_is_recording)),
            )

        try {
            open.stop()
            open.close()
            capture?.join(CAPTURE_JOIN_MILLIS)

            val pcm = captured?.toByteArray() ?: ByteArray(0)
            val durationMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI

            if (pcm.isEmpty()) {
                return@withContext ZillitResult.Failure(
                    ZillitError.Validation(str(S.desktop_nothing_captured_check_mic)),
                )
            }

            val wav = ByteArrayOutputStream().also { out ->
                AudioInputStream(
                    ByteArrayInputStream(pcm),
                    FORMAT,
                    (pcm.size / FORMAT.frameSize).toLong(),
                ).use { stream -> AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out) }
            }

            ZillitResult.Success(RecordedAudio(wav.toByteArray(), durationMillis))
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.w(TAG) { "recording failed to finish: ${throwable::class.simpleName}" }
            ZillitResult.Failure(ZillitError.Storage(str(S.desktop_recording_not_saved)))
        } finally {
            reset()
        }
    }

    override fun cancel() {
        runCatching {
            line?.stop()
            line?.close()
            capture?.join(CAPTURE_JOIN_MILLIS)
        }
        reset()
    }

    private fun reset() {
        line = null
        captured = null
        capture = null
        startedAtNanos = 0
    }

    private companion object {
        const val TAG = "AudioRecorder"

        val FORMAT = AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false)

        const val SAMPLE_RATE = 16_000f
        const val BITS = 16
        const val CHANNELS = 1
        const val BUFFER_BYTES = 4096
        const val CAPTURE_JOIN_MILLIS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L

        fun noMicrophone() = ZillitError.Storage(
            technical = "no usable TargetDataLine",
            userMessage = str(S.desktop_microphone_not_opened),
        )
    }
}
